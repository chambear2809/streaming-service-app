package io.github.marianciuc.streamingservice.payment.config;

import io.github.marianciuc.streamingservice.payment.entity.Address;
import io.github.marianciuc.streamingservice.payment.entity.CardHolder;
import io.github.marianciuc.streamingservice.payment.enums.CardStatus;
import io.github.marianciuc.streamingservice.payment.repository.CardHolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.UUID;

@Configuration
@Profile("broadcast-demo")
@RequiredArgsConstructor
public class DemoPaymentBootstrap {

    private static final List<CardHolderSeed> DEMO_CARD_HOLDERS = List.of(
            new CardHolderSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5001"),
                    "cus_demo_northcoast",
                    "finance@northcoastsports.example",
                    "North Coast Sports Network",
                    "+1 216 555 0144",
                    new AddressSeed("255 Harbor Exchange", "Suite 410", "Cleveland", "44114", "OH", "United States")
            ),
            new CardHolderSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5002"),
                    "cus_demo_metroweather",
                    "ap@metroweather.example",
                    "Metro Weather Desk",
                    "+1 416 555 0133",
                    new AddressSeed("84 Front Street", "Floor 9", "Toronto", "M5J 2X2", "ON", "Canada")
            ),
            new CardHolderSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5003"),
                    "cus_demo_cityarts",
                    "billing@cityarts.example",
                    "City Arts Channel",
                    "+44 20 7946 0112",
                    new AddressSeed("12 Market Square", "Studio 2", "Manchester", "M1 1AA", "ENG", "United Kingdom")
            ),
            new CardHolderSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5004"),
                    "cus_demo_popupeast",
                    "controller@popupeast.example",
                    "Pop-Up Event East",
                    "+1 917 555 0199",
                    new AddressSeed("410 Atlantic Hall", "Dock 7", "New York", "10013", "NY", "United States")
            )
    );

    private final CardHolderRepository cardHolderRepository;

    @Bean
    ApplicationRunner seedDemoCardHolders() {
        return args -> {
            for (CardHolderSeed seed : DEMO_CARD_HOLDERS) {
                if (cardHolderRepository.existsById(seed.userId())) {
                    continue;
                }

                cardHolderRepository.save(CardHolder.builder()
                        .userId(seed.userId())
                        .stripeCustomerId(seed.stripeCustomerId())
                        .email(seed.email())
                        .cardHolderName(seed.cardHolderName())
                        .phoneNumber(seed.phoneNumber())
                        .cardStatus(CardStatus.ACTIVE)
                        .address(Address.builder()
                                .line1(seed.address().line1())
                                .line2(seed.address().line2())
                                .city(seed.address().city())
                                .postalCode(seed.address().postalCode())
                                .state(seed.address().state())
                                .country(seed.address().country())
                                .build())
                        .build());
            }
        };
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private record CardHolderSeed(
            UUID userId,
            String stripeCustomerId,
            String email,
            String cardHolderName,
            String phoneNumber,
            AddressSeed address
    ) {
    }

    private record AddressSeed(
            String line1,
            String line2,
            String city,
            String postalCode,
            String state,
            String country
    ) {
    }
}
