package io.github.marianciuc.streamingservice.user.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.marianciuc.streamingservice.user.kafka.KafkaUserProducer;
import io.github.marianciuc.streamingservice.user.kafka.messages.CreateUserMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserOutboxService {

    static final String USER_CREATED_EVENT_TYPE = "user-created";
    private static final int PUBLISH_BATCH_SIZE = 25;

    private final UserOutboxEventRepository repository;
    private final KafkaUserProducer kafkaUserProducer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    @PostConstruct
    void registerGauges() {
        Gauge.builder("streaming.user.outbox.pending.events", repository, UserOutboxEventRepository::countByPublishedAtIsNull)
                .description("Pending user outbox events waiting for publish.")
                .register(meterRegistry);
        Gauge.builder(
                        "streaming.user.outbox.ready.events",
                        repository,
                        repo -> repo.countByPublishedAtIsNullAndNextAttemptAtLessThanEqual(LocalDateTime.now())
                )
                .description("Pending user outbox events ready to publish now.")
                .register(meterRegistry);
    }

    public void enqueueUserCreated(CreateUserMessage message) {
        repository.save(UserOutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(message.id())
                .eventType(USER_CREATED_EVENT_TYPE)
                .payload(serialize(message))
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now())
                .build());
        meterRegistry.counter("streaming.user.outbox.enqueue.total", "event.type", USER_CREATED_EVENT_TYPE).increment();
    }

    @Scheduled(fixedDelayString = "${user.outbox.publish-fixed-delay-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<UserOutboxEvent> pendingEvents = repository.lockPendingEventsForPublish(
                LocalDateTime.now(),
                PUBLISH_BATCH_SIZE
        );

        for (UserOutboxEvent pendingEvent : pendingEvents) {
            publish(pendingEvent);
        }
    }

    private void publish(UserOutboxEvent pendingEvent) {
        Observation observation = Observation.createNotStarted("user.outbox.publish", observationRegistry)
                .contextualName("publish user outbox event")
                .lowCardinalityKeyValue("event.type", pendingEvent.getEventType())
                .lowCardinalityKeyValue("transport", "kafka");
        Timer.Sample sample = Timer.start(meterRegistry);

        observation.start();
        try {
            try (Observation.Scope scope = observation.openScope()) {
                kafkaUserProducer.sendUserCreatedMessage(deserialize(pendingEvent.getPayload()));
            }
            pendingEvent.markPublished(LocalDateTime.now());
            meterRegistry.counter(
                    "streaming.user.outbox.publish.total",
                    "event.type", pendingEvent.getEventType(),
                    "outcome", "success"
            ).increment();
        } catch (RuntimeException exception) {
            pendingEvent.markFailed(LocalDateTime.now(), exception.getMessage());
            observation.error(exception);
            meterRegistry.counter(
                    "streaming.user.outbox.publish.total",
                    "event.type", pendingEvent.getEventType(),
                    "outcome", "failure"
            ).increment();
            meterRegistry.counter("streaming.user.outbox.publish.failures.total", "event.type", pendingEvent.getEventType()).increment();
            meterRegistry.counter("streaming.user.outbox.retry.scheduled.total", "event.type", pendingEvent.getEventType()).increment();
            log.warn(
                    "Failed to publish user outbox event {} for aggregate {}",
                    pendingEvent.getId(),
                    pendingEvent.getAggregateId(),
                    exception
            );
        } finally {
            sample.stop(Timer.builder("streaming.user.outbox.publish.duration")
                    .description("Latency of publishing user outbox events to Kafka.")
                    .tag("event.type", pendingEvent.getEventType())
                    .tag("outcome", pendingEvent.getPublishedAt() == null ? "failure" : "success")
                    .register(meterRegistry));
            observation.stop();
        }
    }

    private String serialize(CreateUserMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize user-created outbox payload.", exception);
        }
    }

    private CreateUserMessage deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, CreateUserMessage.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize user-created outbox payload.", exception);
        }
    }
}
