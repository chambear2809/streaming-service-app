package io.github.marianciuc.streamingservice.subscription.integration;

import io.github.marianciuc.streamingservice.subscription.clients.OrderClient;
import io.github.marianciuc.streamingservice.subscription.entity.Currency;
import io.github.marianciuc.streamingservice.subscription.entity.RecordStatus;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.entity.SubscriptionStatus;
import io.github.marianciuc.streamingservice.subscription.entity.UserSubscriptions;
import io.github.marianciuc.streamingservice.subscription.mapper.UserSubscriptionMapper;
import io.github.marianciuc.streamingservice.subscription.repository.SubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.repository.UserSubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.service.SubscriptionService;
import io.github.marianciuc.streamingservice.subscription.service.impl.UserSubscriptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.config.client.ConfigClientAutoConfiguration;
import org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = SubscriptionRenewalRetryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=subscription-renewal-retry-it",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.flyway.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:subscription_renewal_retry_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        }
)
@DirtiesContext
class SubscriptionRenewalRetryIntegrationTest {

    @Autowired
    private UserSubscriptionServiceImpl userSubscriptionService;

    @Autowired
    private UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @MockBean
    private SubscriptionService subscriptionService;

    @MockBean
    private OrderClient orderClient;

    @MockBean
    private UserSubscriptionMapper mapper;

    @BeforeEach
    void setUp() {
        userSubscriptionRepository.deleteAll();
        subscriptionRepository.deleteAll();
    }

    @Test
    void extendSubscriptionFinalizesTrackedRenewalWithoutCreatingAnotherRemoteOrder() throws Exception {
        Subscription permanentSubscription = subscriptionRepository.save(Subscription.builder()
                .name("Premium")
                .description("Premium plan")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .durationInDays(30)
                .price(BigDecimal.valueOf(29.99))
                .currency(Currency.USD)
                .allowedActiveSessions(4)
                .recordStatus(RecordStatus.ACTIVE)
                .build());
        Subscription temporarySubscription = subscriptionRepository.save(Subscription.builder()
                .name("Trial")
                .description("Trial plan")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .durationInDays(7)
                .price(BigDecimal.ZERO)
                .currency(Currency.USD)
                .allowedActiveSessions(1)
                .recordStatus(RecordStatus.ACTIVE)
                .isTemporary(true)
                .nextSubscription(permanentSubscription)
                .build());

        UUID renewalOrderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserSubscriptions claimedSubscription = userSubscriptionRepository.save(UserSubscriptions.builder()
                .userId(userId)
                .orderId(UUID.randomUUID())
                .subscription(temporarySubscription)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now().minusDays(7))
                .endDate(LocalDate.now())
                .renewalClaimedAt(LocalDateTime.now().minusMinutes(5))
                .renewalOrderId(renewalOrderId)
                .build());

        userSubscriptionService.extendSubscription(claimedSubscription);

        List<UserSubscriptions> persistedSubscriptions = userSubscriptionRepository.findAll();
        assertEquals(2, persistedSubscriptions.size());

        UserSubscriptions expiredSubscription = persistedSubscriptions.stream()
                .filter(subscription -> claimedSubscription.getId().equals(subscription.getId()))
                .findFirst()
                .orElseThrow();
        UserSubscriptions activeSubscription = persistedSubscriptions.stream()
                .filter(subscription -> renewalOrderId.equals(subscription.getOrderId()))
                .findFirst()
                .orElseThrow();

        assertEquals(SubscriptionStatus.EXPIRED, expiredSubscription.getStatus());
        assertNull(expiredSubscription.getRenewalClaimedAt());
        assertNull(expiredSubscription.getRenewalOrderId());
        assertEquals(SubscriptionStatus.ACTIVE, activeSubscription.getStatus());
        assertEquals(permanentSubscription.getId(), activeSubscription.getSubscription().getId());
        verifyNoInteractions(orderClient);
    }

    @Test
    void extendSubscriptionReclaimsExpiredRenewalClaim() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Subscription subscription = subscriptionRepository.save(Subscription.builder()
                .name("Standard")
                .description("Standard plan")
                .createdAt(now)
                .durationInDays(30)
                .price(BigDecimal.valueOf(19.99))
                .currency(Currency.USD)
                .allowedActiveSessions(2)
                .recordStatus(RecordStatus.ACTIVE)
                .updatedAt(now)
                .build());

        UUID renewalOrderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserSubscriptions staleClaimedSubscription = userSubscriptionRepository.save(UserSubscriptions.builder()
                .userId(userId)
                .orderId(UUID.randomUUID())
                .subscription(subscription)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now().minusDays(30))
                .endDate(LocalDate.now())
                .renewalClaimedAt(LocalDateTime.now().minusMinutes(10))
                .build());
        when(orderClient.createOrder(any())).thenReturn(ResponseEntity.ok(
                new io.github.marianciuc.streamingservice.subscription.dto.OrderResponse(
                        renewalOrderId,
                        userId,
                        subscription.getPrice(),
                        subscription.getId(),
                        LocalDateTime.now(),
                        io.github.marianciuc.streamingservice.subscription.entity.OrderStatus.CREATED
                )
        ));

        userSubscriptionService.extendSubscription(staleClaimedSubscription);

        List<UserSubscriptions> persistedSubscriptions = userSubscriptionRepository.findAll();
        assertEquals(2, persistedSubscriptions.size());

        UserSubscriptions expiredSubscription = persistedSubscriptions.stream()
                .filter(subscriptionRecord -> staleClaimedSubscription.getId().equals(subscriptionRecord.getId()))
                .findFirst()
                .orElseThrow();
        UserSubscriptions activeSubscription = persistedSubscriptions.stream()
                .filter(subscriptionRecord -> renewalOrderId.equals(subscriptionRecord.getOrderId()))
                .findFirst()
                .orElseThrow();

        assertEquals(SubscriptionStatus.EXPIRED, expiredSubscription.getStatus());
        assertNull(expiredSubscription.getRenewalClaimedAt());
        assertNull(expiredSubscription.getRenewalOrderId());
        assertEquals(SubscriptionStatus.ACTIVE, activeSubscription.getStatus());
        assertEquals(subscription.getId(), activeSubscription.getSubscription().getId());
        assertNotNull(activeSubscription.getStartDate());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ConfigClientAutoConfiguration.class,
            EurekaClientAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = {Subscription.class, UserSubscriptions.class})
    @EnableJpaRepositories(basePackageClasses = {SubscriptionRepository.class, UserSubscriptionRepository.class})
    @Import(UserSubscriptionServiceImpl.class)
    static class TestApplication {

        @Bean
        TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }
}
