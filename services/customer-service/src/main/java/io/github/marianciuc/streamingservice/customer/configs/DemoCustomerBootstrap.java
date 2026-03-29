package io.github.marianciuc.streamingservice.customer.configs;

import io.github.marianciuc.streamingservice.customer.model.Customer;
import io.github.marianciuc.streamingservice.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Configuration
@Profile("broadcast-demo")
@RequiredArgsConstructor
public class DemoCustomerBootstrap {

    private static final List<CustomerSeed> DEMO_CUSTOMERS = List.of(
            new CustomerSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5001"),
                    "finance@northcoastsports.example",
                    "stadium",
                    LocalDate.parse("1988-03-14"),
                    "United States",
                    "northcoastsports",
                    "en-US",
                    true,
                    true,
                    true,
                    LocalDateTime.parse("2025-11-14T15:30:00"),
                    LocalDateTime.parse("2026-03-26T10:20:00")
            ),
            new CustomerSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5002"),
                    "ap@metroweather.example",
                    "forecast",
                    LocalDate.parse("1990-08-02"),
                    "Canada",
                    "metroweatherdesk",
                    "en-CA",
                    true,
                    false,
                    true,
                    LocalDateTime.parse("2025-10-06T12:10:00"),
                    LocalDateTime.parse("2026-03-24T18:45:00")
            ),
            new CustomerSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5003"),
                    "billing@cityarts.example",
                    "festival",
                    LocalDate.parse("1992-01-19"),
                    "United Kingdom",
                    "cityartschannel",
                    "en-GB",
                    true,
                    true,
                    false,
                    LocalDateTime.parse("2025-09-18T08:50:00"),
                    LocalDateTime.parse("2026-03-21T11:05:00")
            ),
            new CustomerSeed(
                    uuid("8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5004"),
                    "controller@popupeast.example",
                    "events",
                    LocalDate.parse("1986-05-24"),
                    "United States",
                    "popupeastlive",
                    "en-US",
                    false,
                    false,
                    true,
                    LocalDateTime.parse("2026-01-03T09:00:00"),
                    LocalDateTime.parse("2026-03-28T12:00:00")
            )
    );

    private final CustomerRepository customerRepository;

    @Bean
    ApplicationRunner seedDemoCustomers() {
        return args -> {
            for (CustomerSeed seed : DEMO_CUSTOMERS) {
                if (customerRepository.existsById(seed.id())) {
                    continue;
                }

                customerRepository.save(Customer.builder()
                        .id(seed.id())
                        .email(seed.email())
                        .theme(seed.theme())
                        .birthDate(seed.birthDate())
                        .country(seed.country())
                        .username(seed.username())
                        .profilePicture("")
                        .preferredLanguage(seed.preferredLanguage())
                        .isEmailVerified(seed.isEmailVerified())
                        .profileIsCompleted(true)
                        .receiveNewsletter(seed.receiveNewsletter())
                        .enableNotifications(seed.enableNotifications())
                        .createdAt(seed.createdAt())
                        .updatedAt(seed.updatedAt())
                        .build());
            }
        };
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private record CustomerSeed(
            UUID id,
            String email,
            String theme,
            LocalDate birthDate,
            String country,
            String username,
            String preferredLanguage,
            boolean isEmailVerified,
            boolean receiveNewsletter,
            boolean enableNotifications,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
