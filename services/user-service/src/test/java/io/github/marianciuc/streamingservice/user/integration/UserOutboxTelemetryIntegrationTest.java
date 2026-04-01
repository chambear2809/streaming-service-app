package io.github.marianciuc.streamingservice.user.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import io.github.marianciuc.streamingservice.user.config.KafkaMetricsConfig;
import io.github.marianciuc.streamingservice.user.kafka.KafkaUserProducer;
import io.github.marianciuc.streamingservice.user.kafka.messages.CreateUserMessage;
import io.github.marianciuc.streamingservice.user.outbox.UserOutboxEvent;
import io.github.marianciuc.streamingservice.user.outbox.UserOutboxEventRepository;
import io.github.marianciuc.streamingservice.user.outbox.UserOutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.config.client.ConfigClientAutoConfiguration;
import org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.kafka.test.utils.KafkaTestUtils.consumerProps;

@SpringBootTest(
        classes = UserOutboxTelemetryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=user-service-telemetry-it",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.template.observation-enabled=true",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
                "spring.kafka.producer.properties.spring.json.type.mapping=createUserMessage:io.github.marianciuc.streamingservice.user.kafka.messages.CreateUserMessage"
        }
)
@EmbeddedKafka(partitions = 1, topics = KafkaUserProducer.USER_CREATED_TOPIC)
@DirtiesContext
class UserOutboxTelemetryIntegrationTest {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("\"traceId\":\"([^\"]+)\"");
    private static final LinkedBlockingQueue<String> EXPORTED_SPANS = new LinkedBlockingQueue<>();
    private static final List<String> EXPORTED_SPAN_BATCHES = new CopyOnWriteArrayList<>();
    private static HttpServer collectorServer;

    @Autowired
    private UserOutboxService userOutboxService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private AsyncZipkinSpanHandler zipkinSpanHandler;

    @MockBean
    private UserOutboxEventRepository repository;

    @BeforeAll
    static void startCollector() throws IOException {
        ensureCollectorStarted();
    }

    private static synchronized void ensureCollectorStarted() {
        if (collectorServer != null) {
            return;
        }

        try {
        collectorServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collectorServer.createContext("/api/v2/spans", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            EXPORTED_SPAN_BATCHES.add(requestBody);
            EXPORTED_SPANS.offer(requestBody);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        collectorServer.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start test Zipkin collector.", exception);
        }
    }

    @AfterAll
    static void stopCollector() {
        if (collectorServer != null) {
            collectorServer.stop(0);
        }
    }

    @BeforeEach
    void clearCollector() {
        EXPORTED_SPANS.clear();
        EXPORTED_SPAN_BATCHES.clear();
    }

    @Test
    void publishesViaKafkaAndExportsMetricsAndTraces() throws Exception {
        CreateUserMessage message = new CreateUserMessage(UUID.randomUUID(), "demo@example.com", "demo");
        UserOutboxEvent event = UserOutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(message.id())
                .eventType("user-created")
                .payload(new ObjectMapper().writeValueAsString(message))
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(repository.lockPendingEventsForPublish(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(event));

        userOutboxService.publishPendingEvents();
        zipkinSpanHandler.flush();

        ConsumerRecord<String, String> record = consumeSingleRecord(KafkaUserProducer.USER_CREATED_TOPIC);
        assertTrue(record.value().contains(message.email()));
        assertNotNull(event.getPublishedAt());
        assertTrue(hasMeterStartingWith("kafka.producer"));
        assertTrue(hasMeterStartingWith("streaming.user.outbox.publish"));

        String outboxSpan = awaitExportContaining(
                "\"name\":\"publish user outbox event\"",
                "\"event.type\":\"user-created\""
        );
        String producerSpan = awaitExportContaining(
                "\"kind\":\"PRODUCER\"",
                "\"name\":\"user-created-topic send\""
        );
        assertNotNull(outboxSpan);
        assertNotNull(producerSpan);
        assertTrue(extractTraceId(outboxSpan).equals(extractTraceId(producerSpan)));
    }

    private ConsumerRecord<String, String> consumeSingleRecord(String topic) {
        Map<String, Object> props = consumerProps("user-outbox-telemetry-it", "false", embeddedKafkaBroker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, topic);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records.records(topic)) {
                    return record;
                }
            }
        }

        fail("Expected a Kafka record on topic " + topic);
        return null;
    }

    private String awaitExportContaining(String... needles) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String existingBatch = findCollectedBatchContaining(needles);
            if (existingBatch != null) {
                return existingBatch;
            }

            String body = EXPORTED_SPANS.poll(500, TimeUnit.MILLISECONDS);
            if (body != null && containsAll(body, needles)) {
                return body;
            }
        }

        fail("Expected a trace export containing " + String.join(", ", needles)
                + " but collected " + EXPORTED_SPAN_BATCHES);
        return null;
    }

    private String findCollectedBatchContaining(String... needles) {
        for (String body : EXPORTED_SPAN_BATCHES) {
            if (containsAll(body, needles)) {
                return body;
            }
        }
        return null;
    }

    private boolean containsAll(String body, String... needles) {
        for (String needle : needles) {
            if (!body.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    private String extractTraceId(String body) {
        Matcher matcher = TRACE_ID_PATTERN.matcher(body);
        if (!matcher.find()) {
            fail("Expected an exported trace id in collector body " + body);
        }
        return matcher.group(1);
    }

    private boolean hasMeterStartingWith(String prefix) {
        return meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .anyMatch(name -> name.startsWith(prefix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ConfigClientAutoConfiguration.class,
            EurekaClientAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @Import({
            KafkaMetricsConfig.class,
            KafkaUserProducer.class,
            UserOutboxService.class,
            UserOutboxTelemetryIntegrationTest.TestTracingConfig.class
    })
    static class TestApplication {
    }

    @Configuration(proxyBeanMethods = false)
    static class TestTracingConfig {

        @Bean(destroyMethod = "close")
        AsyncZipkinSpanHandler asyncZipkinSpanHandler() {
            ensureCollectorStarted();
            return AsyncZipkinSpanHandler.create(new TestCollectorSender(collectorEndpoint()));
        }

        @Bean(destroyMethod = "close")
        Tracing tracing(AsyncZipkinSpanHandler zipkinSpanHandler) {
            return Tracing.newBuilder()
                    .localServiceName("user-service-telemetry-it")
                    .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder().build())
                    .sampler(Sampler.ALWAYS_SAMPLE)
                    .addSpanHandler(zipkinSpanHandler)
                    .build();
        }

        @Bean
        io.micrometer.tracing.Tracer tracer(Tracing tracing) {
            return new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()));
        }

        @Bean
        ObservationRegistry observationRegistry(MeterRegistry meterRegistry, io.micrometer.tracing.Tracer tracer) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
            registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
            return registry;
        }
    }

    private static String collectorEndpoint() {
        ensureCollectorStarted();
        return "http://127.0.0.1:" + collectorServer.getAddress().getPort() + "/api/v2/spans";
    }

    private static final class TestCollectorSender implements BytesMessageSender {

        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final URI endpoint;

        private TestCollectorSender(String endpoint) {
            this.endpoint = URI.create(endpoint);
        }

        @Override
        public Encoding encoding() {
            return Encoding.JSON;
        }

        @Override
        public int messageMaxBytes() {
            return 5_000_000;
        }

        @Override
        public int messageSizeInBytes(List<byte[]> encodedSpans) {
            return encoding().listSizeInBytes(encodedSpans);
        }

        @Override
        public int messageSizeInBytes(int spanCount) {
            return encoding().listSizeInBytes(spanCount);
        }

        @Override
        public void send(List<byte[]> encodedSpans) throws IOException {
            byte[] body = encoding().encode(encodedSpans);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", encoding().mediaType())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Unexpected collector status: " + response.statusCode());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while exporting spans.", exception);
            }
        }

        @Override
        public void close() {
        }
    }
}
