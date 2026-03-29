package io.github.marianciuc.streamingservice.subscription.config;

import io.github.marianciuc.streamingservice.subscription.entity.Currency;
import io.github.marianciuc.streamingservice.subscription.entity.RecordStatus;
import io.github.marianciuc.streamingservice.subscription.entity.Resolution;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.entity.SubscriptionStatus;
import io.github.marianciuc.streamingservice.subscription.entity.UserSubscriptions;
import io.github.marianciuc.streamingservice.subscription.repository.ResolutionRepository;
import io.github.marianciuc.streamingservice.subscription.repository.SubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Configuration
@Profile("broadcast-demo")
@RequiredArgsConstructor
public class DemoSubscriptionBootstrap {

    private static final ResolutionSeed HD = new ResolutionSeed("HD", "720p adaptive stream");
    private static final ResolutionSeed FHD = new ResolutionSeed("FHD", "1080p adaptive stream");
    private static final ResolutionSeed UHD = new ResolutionSeed("UHD", "2160p premium stream");
    private static final List<ResolutionSeed> REQUIRED_RESOLUTIONS = List.of(HD, FHD, UHD);
    private static final List<ActiveSubscriptionSeed> ACTIVE_SUBSCRIPTIONS = List.of(
            new ActiveSubscriptionSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5001"),
                    uuid("00000000-0000-0000-0000-000000006101"),
                    "Broadcast Basic",
                    LocalDate.parse("2026-03-01"),
                    LocalDate.parse("2026-03-31")
            ),
            new ActiveSubscriptionSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5003"),
                    uuid("00000000-0000-0000-0000-000000006105"),
                    "Event Pass",
                    LocalDate.parse("2026-03-09"),
                    LocalDate.parse("2026-03-23")
            )
    );

    private final ResolutionRepository resolutionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

    @Bean
    ApplicationRunner seedDemoSubscriptions() {
        return args -> {
            Map<String, Resolution> resolutions = ensureRequiredResolutions();
            seedPlansIfNeeded(resolutions);
            seedActiveSubscriptions();
        };
    }

    private Map<String, Resolution> ensureRequiredResolutions() {
        Map<String, Resolution> resolutionsByName = new LinkedHashMap<>();
        resolutionRepository.findAll().forEach(resolution -> resolutionsByName.put(resolutionKey(resolution.getName()), resolution));

        for (ResolutionSeed requiredResolution : REQUIRED_RESOLUTIONS) {
            Resolution resolution = ensureResolution(resolutionsByName, requiredResolution);
            resolutionsByName.put(resolutionKey(resolution.getName()), resolution);
        }

        Map<String, Resolution> requiredResolutions = new LinkedHashMap<>();
        for (ResolutionSeed requiredResolution : REQUIRED_RESOLUTIONS) {
            requiredResolutions.put(requiredResolution.name(), requireResolution(resolutionsByName, requiredResolution));
        }
        return requiredResolutions;
    }

    private void seedPlansIfNeeded(Map<String, Resolution> resolutionsByName) {
        if (subscriptionRepository.count() > 0) {
            return;
        }

        Resolution hd = requireResolution(resolutionsByName, HD);
        Resolution fhd = requireResolution(resolutionsByName, FHD);
        Resolution uhd = requireResolution(resolutionsByName, UHD);

        Subscription basic = Subscription.builder()
                .name("Broadcast Basic")
                .description("Single-screen standard playback for everyday viewers.")
                .durationInDays(30)
                .price(new BigDecimal("9.99"))
                .currency(Currency.USD)
                .allowedActiveSessions(1)
                .recordStatus(RecordStatus.ACTIVE)
                .resolutions(Set.of(hd, fhd))
                .isTemporary(false)
                .build();
        Subscription premium = Subscription.builder()
                .name("Broadcast Premium")
                .description("Multi-screen plan with full-resolution playback for premium households.")
                .durationInDays(30)
                .price(new BigDecimal("19.99"))
                .currency(Currency.USD)
                .allowedActiveSessions(4)
                .recordStatus(RecordStatus.ACTIVE)
                .resolutions(Set.of(hd, fhd, uhd))
                .isTemporary(false)
                .build();
        Subscription eventPass = Subscription.builder()
                .name("Event Pass")
                .description("Short-term event package for temporary access during premiere windows.")
                .durationInDays(7)
                .price(new BigDecimal("4.99"))
                .currency(Currency.USD)
                .allowedActiveSessions(2)
                .recordStatus(RecordStatus.ACTIVE)
                .resolutions(Set.of(hd, fhd))
                .isTemporary(false)
                .build();

        subscriptionRepository.saveAll(List.of(basic, premium, eventPass));
    }

    private void seedActiveSubscriptions() {
        Map<String, Subscription> subscriptionsByName = new LinkedHashMap<>();
        subscriptionRepository.findAll().forEach(subscription -> subscriptionsByName.put(subscriptionKey(subscription.getName()), subscription));

        for (ActiveSubscriptionSeed seed : ACTIVE_SUBSCRIPTIONS) {
            if (userSubscriptionRepository.findByOrderId(seed.orderId()).isPresent()) {
                continue;
            }

            if (userSubscriptionRepository.findFirstByUserIdAndStatusOrderByEndDateDesc(seed.userId(), SubscriptionStatus.ACTIVE).isPresent()) {
                continue;
            }

            Subscription subscription = subscriptionsByName.get(subscriptionKey(seed.subscriptionName()));
            if (subscription == null) {
                throw new IllegalStateException("Demo subscription seed is incomplete");
            }

            userSubscriptionRepository.save(UserSubscriptions.builder()
                    .userId(seed.userId())
                    .orderId(seed.orderId())
                    .subscription(subscription)
                    .startDate(seed.startDate())
                    .endDate(seed.endDate())
                    .status(SubscriptionStatus.ACTIVE)
                    .build());
        }
    }

    private Resolution requireResolution(Map<String, Resolution> resolutionsByName, ResolutionSeed requiredResolution) {
        Resolution resolution = resolutionsByName.get(resolutionKey(requiredResolution.name()));
        if (resolution == null) {
            throw new IllegalStateException("Demo resolution seed is incomplete");
        }
        return resolution;
    }

    private Resolution ensureResolution(Map<String, Resolution> resolutionsByName, ResolutionSeed requiredResolution) {
        String key = resolutionKey(requiredResolution.name());
        Resolution existing = resolutionsByName.get(key);
        if (existing == null) {
            existing = resolutionRepository.findByNameIgnoreCase(requiredResolution.name()).orElse(null);
        }
        if (existing != null) {
            if (!Objects.equals(existing.getDescription(), requiredResolution.description())) {
                existing.setDescription(requiredResolution.description());
                return resolutionRepository.save(existing);
            }
            return existing;
        }

        try {
            return resolutionRepository.save(Resolution.builder()
                    .name(requiredResolution.name())
                    .description(requiredResolution.description())
                    .build());
        } catch (DataIntegrityViolationException exception) {
            return resolutionRepository.findByNameIgnoreCase(requiredResolution.name())
                    .orElseThrow(() -> exception);
        }
    }

    private String resolutionKey(String name) {
        return String.valueOf(name).trim().toUpperCase(Locale.ROOT);
    }

    private String subscriptionKey(String name) {
        return String.valueOf(name).trim().toUpperCase(Locale.ROOT);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private record ResolutionSeed(String name, String description) {
    }

    private record ActiveSubscriptionSeed(
            UUID userId,
            UUID orderId,
            String subscriptionName,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
}
