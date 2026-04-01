package io.github.marianciuc.streamingservice.user.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.marianciuc.streamingservice.user.outbox.UserOutboxEvent;
import io.github.marianciuc.streamingservice.user.outbox.UserOutboxEventRepository;
import io.github.marianciuc.streamingservice.user.outbox.UserOutboxService;
import io.github.marianciuc.streamingservice.user.kafka.KafkaUserProducer;
import io.github.marianciuc.streamingservice.user.kafka.messages.CreateUserMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.config.client.ConfigClientAutoConfiguration;
import org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(
        classes = UserOutboxClaimIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=user-outbox-claim-it",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.flyway.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:user_outbox_claim_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        }
)
@DirtiesContext
class UserOutboxClaimIntegrationTest {

    @Autowired
    private UserOutboxService userOutboxService;

    @Autowired
    private UserOutboxEventRepository repository;

    @MockBean
    private KafkaUserProducer kafkaUserProducer;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        executorService = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void concurrentPublishersDoNotPublishTheSameOutboxRowTwice() throws Exception {
        CreateUserMessage message = new CreateUserMessage(UUID.randomUUID(), "demo@example.com", "demo");
        repository.save(UserOutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(message.id())
                .eventType("user-created")
                .payload(new ObjectMapper().writeValueAsString(message))
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(1))
                .build());

        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch allowFirstSendToFinish = new CountDownLatch(1);
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(invocation -> {
            sendCount.incrementAndGet();
            firstSendStarted.countDown();
            assertTrue(allowFirstSendToFinish.await(5, TimeUnit.SECONDS));
            return null;
        }).when(kafkaUserProducer).sendUserCreatedMessage(any());

        Future<?> firstPublisher = executorService.submit(() -> userOutboxService.publishPendingEvents());
        assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS));

        Future<?> secondPublisher = executorService.submit(() -> userOutboxService.publishPendingEvents());
        secondPublisher.get(5, TimeUnit.SECONDS);

        allowFirstSendToFinish.countDown();
        firstPublisher.get(5, TimeUnit.SECONDS);

        assertEquals(1, sendCount.get());
        UserOutboxEvent persistedEvent = repository.findAll().getFirst();
        assertNotNull(persistedEvent.getPublishedAt());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ConfigClientAutoConfiguration.class,
            EurekaClientAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = UserOutboxEvent.class)
    @EnableJpaRepositories(basePackageClasses = UserOutboxEventRepository.class)
    @Import(UserOutboxService.class)
    static class TestApplication {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry(MeterRegistry meterRegistry) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
            return registry;
        }
    }
}
