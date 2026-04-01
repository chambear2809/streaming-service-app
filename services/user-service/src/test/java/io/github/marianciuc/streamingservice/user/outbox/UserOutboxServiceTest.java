package io.github.marianciuc.streamingservice.user.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.marianciuc.streamingservice.user.kafka.KafkaUserProducer;
import io.github.marianciuc.streamingservice.user.kafka.messages.CreateUserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserOutboxServiceTest {

    @Mock
    private UserOutboxEventRepository repository;

    @Mock
    private KafkaUserProducer kafkaUserProducer;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private UserOutboxService userOutboxService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        userOutboxService = new UserOutboxService(
                repository,
                kafkaUserProducer,
                objectMapper,
                meterRegistry,
                ObservationRegistry.create()
        );
        userOutboxService.registerGauges();
    }

    @Test
    void enqueueUserCreatedStoresPendingOutboxEvent() {
        CreateUserMessage message = new CreateUserMessage(UUID.randomUUID(), "demo@example.com", "demo");

        userOutboxService.enqueueUserCreated(message);

        ArgumentCaptor<UserOutboxEvent> eventCaptor = ArgumentCaptor.forClass(UserOutboxEvent.class);
        verify(repository).save(eventCaptor.capture());
        assertEquals(message.id(), eventCaptor.getValue().getAggregateId());
        assertEquals(UserOutboxService.USER_CREATED_EVENT_TYPE, eventCaptor.getValue().getEventType());
        assertEquals(0, eventCaptor.getValue().getAttemptCount());
        assertNull(eventCaptor.getValue().getPublishedAt());
        assertNotNull(eventCaptor.getValue().getNextAttemptAt());
        assertTrue(eventCaptor.getValue().getPayload().contains(message.email()));
        assertEquals(1.0, meterRegistry.get("streaming.user.outbox.enqueue.total").counter().count());
    }

    @Test
    void publishPendingEventsMarksEventPublishedAfterSuccessfulSend() throws Exception {
        CreateUserMessage message = new CreateUserMessage(UUID.randomUUID(), "demo@example.com", "demo");
        UserOutboxEvent event = pendingEvent(message);
        when(repository.lockPendingEventsForPublish(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(event));

        userOutboxService.publishPendingEvents();

        verify(kafkaUserProducer).sendUserCreatedMessage(message);
        assertNotNull(event.getPublishedAt());
        assertNull(event.getLastError());
        assertEquals(1.0, meterRegistry.get("streaming.user.outbox.publish.total").tag("outcome", "success").counter().count());
        assertEquals(1L, meterRegistry.get("streaming.user.outbox.publish.duration").tag("outcome", "success").timer().count());
    }

    @Test
    void publishPendingEventsSchedulesRetryWhenSendFails() throws Exception {
        CreateUserMessage message = new CreateUserMessage(UUID.randomUUID(), "demo@example.com", "demo");
        UserOutboxEvent event = pendingEvent(message);
        when(repository.lockPendingEventsForPublish(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker unavailable")).when(kafkaUserProducer).sendUserCreatedMessage(message);

        userOutboxService.publishPendingEvents();

        assertEquals(1, event.getAttemptCount());
        assertNull(event.getPublishedAt());
        assertEquals("broker unavailable", event.getLastError());
        assertTrue(event.getNextAttemptAt().isAfter(LocalDateTime.now().minusSeconds(1)));
        assertEquals(1.0, meterRegistry.get("streaming.user.outbox.publish.total").tag("outcome", "failure").counter().count());
        assertEquals(1.0, meterRegistry.get("streaming.user.outbox.publish.failures.total").counter().count());
        assertEquals(1.0, meterRegistry.get("streaming.user.outbox.retry.scheduled.total").counter().count());
        assertEquals(1L, meterRegistry.get("streaming.user.outbox.publish.duration").tag("outcome", "failure").timer().count());
    }

    @Test
    void gaugesReflectPendingAndReadyBacklog() {
        when(repository.countByPublishedAtIsNull()).thenReturn(3L);
        when(repository.countByPublishedAtIsNullAndNextAttemptAtLessThanEqual(any(LocalDateTime.class))).thenReturn(2L);

        assertEquals(3.0, meterRegistry.get("streaming.user.outbox.pending.events").gauge().value());
        assertEquals(2.0, meterRegistry.get("streaming.user.outbox.ready.events").gauge().value());
    }

    private UserOutboxEvent pendingEvent(CreateUserMessage message) throws Exception {
        return UserOutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(message.id())
                .eventType(UserOutboxService.USER_CREATED_EVENT_TYPE)
                .payload(objectMapper.writeValueAsString(message))
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(1))
                .build();
    }
}
