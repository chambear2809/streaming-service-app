package io.github.marianciuc.streamingservice.subscription.config;

import io.github.marianciuc.streamingservice.subscription.entity.Resolution;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.repository.ResolutionRepository;
import io.github.marianciuc.streamingservice.subscription.repository.SubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoSubscriptionBootstrapTest {

    private final ResolutionRepository resolutionRepository = mock(ResolutionRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final UserSubscriptionRepository userSubscriptionRepository = mock(UserSubscriptionRepository.class);
    private final DemoSubscriptionBootstrap bootstrap =
            new DemoSubscriptionBootstrap(resolutionRepository, subscriptionRepository, userSubscriptionRepository);

    @Test
    void reusesExistingNamedResolutions() throws Exception {
        Resolution existingHd = resolution("HD", "720p adaptive stream");
        Resolution existingFhd = resolution("FHD", "1080p adaptive stream");
        Resolution existingUhd = resolution("UHD", "2160p premium stream");

        when(resolutionRepository.findAll()).thenReturn(List.of(existingHd, existingFhd, existingUhd));
        when(subscriptionRepository.count()).thenReturn(0L);
        when(subscriptionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptionRepository.findAll()).thenReturn(List.of(subscription("Broadcast Basic"), subscription("Event Pass")));
        when(userSubscriptionRepository.findByOrderId(any())).thenReturn(Optional.empty());
        when(userSubscriptionRepository.findFirstByUserIdAndStatusOrderByEndDateDesc(any(), any())).thenReturn(Optional.empty());

        bootstrap.seedDemoSubscriptions().run(new DefaultApplicationArguments(new String[0]));

        verify(resolutionRepository, never()).save(any(Resolution.class));
        verify(subscriptionRepository).saveAll(argThat(iterable -> {
            List<Subscription> subscriptions = StreamSupport.stream(iterable.spliterator(), false).toList();
            return subscriptions.size() == 3
                    && subscriptions.stream().allMatch(subscription -> subscription.getResolutions() != null && !subscription.getResolutions().isEmpty());
        }));
    }

    @Test
    void createsOnlyMissingRequiredResolution() throws Exception {
        Resolution existingHd = resolution("HD", "720p adaptive stream");
        Resolution existingFhd = resolution("FHD", "1080p adaptive stream");
        Resolution createdUhd = resolution("UHD", "2160p premium stream");

        when(resolutionRepository.findAll()).thenReturn(List.of(existingHd, existingFhd));
        when(resolutionRepository.findByNameIgnoreCase("UHD")).thenReturn(Optional.empty());
        when(resolutionRepository.save(any(Resolution.class))).thenReturn(createdUhd);
        when(subscriptionRepository.count()).thenReturn(0L);
        when(subscriptionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptionRepository.findAll()).thenReturn(List.of(subscription("Broadcast Basic"), subscription("Event Pass")));
        when(userSubscriptionRepository.findByOrderId(any())).thenReturn(Optional.empty());
        when(userSubscriptionRepository.findFirstByUserIdAndStatusOrderByEndDateDesc(any(), any())).thenReturn(Optional.empty());

        bootstrap.seedDemoSubscriptions().run(new DefaultApplicationArguments(new String[0]));

        verify(resolutionRepository).save(argThat(resolution -> "UHD".equals(resolution.getName())));
        verify(subscriptionRepository).saveAll(any());
    }

    @Test
    void recoversWhenConcurrentInsertWinsTheRace() throws Exception {
        Resolution existingHd = resolution("HD", "720p adaptive stream");
        Resolution existingFhd = resolution("FHD", "1080p adaptive stream");
        Resolution existingUhd = resolution("UHD", "2160p premium stream");

        when(resolutionRepository.findAll()).thenReturn(List.of());
        when(resolutionRepository.findByNameIgnoreCase("HD")).thenReturn(Optional.empty(), Optional.of(existingHd));
        when(resolutionRepository.findByNameIgnoreCase("FHD")).thenReturn(Optional.of(existingFhd));
        when(resolutionRepository.findByNameIgnoreCase("UHD")).thenReturn(Optional.of(existingUhd));
        when(resolutionRepository.save(any(Resolution.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(subscriptionRepository.count()).thenReturn(1L);
        when(subscriptionRepository.findAll()).thenReturn(List.of(subscription("Broadcast Basic"), subscription("Event Pass")));
        when(userSubscriptionRepository.findByOrderId(any())).thenReturn(Optional.empty());
        when(userSubscriptionRepository.findFirstByUserIdAndStatusOrderByEndDateDesc(any(), any())).thenReturn(Optional.empty());

        bootstrap.seedDemoSubscriptions().run(new DefaultApplicationArguments(new String[0]));

        verify(resolutionRepository, times(1)).save(any(Resolution.class));
        verify(subscriptionRepository, never()).saveAll(any());
    }

    private Resolution resolution(String name, String description) {
        return Resolution.builder()
                .id(UUID.randomUUID())
                .name(name)
                .description(description)
                .build();
    }

    private Subscription subscription(String name) {
        return Subscription.builder()
                .id(UUID.randomUUID())
                .name(name)
                .build();
    }
}
