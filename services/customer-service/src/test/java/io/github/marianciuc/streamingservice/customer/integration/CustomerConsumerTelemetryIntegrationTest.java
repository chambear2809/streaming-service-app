package io.github.marianciuc.streamingservice.customer.integration;

import com.sun.net.httpserver.HttpServer;
import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import io.github.marianciuc.streamingservice.customer.configs.KafkaMetricsConfig;
import io.github.marianciuc.streamingservice.customer.kafka.CustomerConsumer;
import io.github.marianciuc.streamingservice.customer.kafka.messages.CreateUserMessage;
import io.github.marianciuc.streamingservice.customer.model.Customer;
import io.github.marianciuc.streamingservice.customer.repository.CustomerRepository;
import io.github.marianciuc.streamingservice.customer.security.services.UserService;
import io.github.marianciuc.streamingservice.customer.services.EmailVerificationService;
import io.github.marianciuc.streamingservice.customer.services.impl.CustomerServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
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
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = CustomerConsumerTelemetryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=customer-service-telemetry-it",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.consumer.group-id=customer-telemetry-it",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
                "spring.kafka.consumer.properties.spring.json.type.mapping=createUserMessage:io.github.marianciuc.streamingservice.customer.kafka.messages.CreateUserMessage",
                "spring.kafka.consumer.properties.spring.json.trusted.packages=io.github.marianciuc.streamingservice.customer.kafka.messages",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
                "spring.kafka.producer.properties.spring.json.type.mapping=createUserMessage:io.github.marianciuc.streamingservice.customer.kafka.messages.CreateUserMessage",
                "spring.kafka.listener.observation-enabled=true",
                "spring.kafka.template.observation-enabled=true"
        }
)
@EmbeddedKafka(partitions = 1, topics = "user-created-topic")
@DirtiesContext
class CustomerConsumerTelemetryIntegrationTest {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("\"traceId\":\"([^\"]+)\"");
    private static final LinkedBlockingQueue<String> EXPORTED_SPANS = new LinkedBlockingQueue<>();
    private static final java.util.List<String> EXPORTED_SPAN_BATCHES = new CopyOnWriteArrayList<>();
    private static HttpServer collectorServer;

    @Autowired
    private KafkaTemplate<String, CreateUserMessage> kafkaTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaListenerEndpointRegistry listenerEndpointRegistry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private AsyncZipkinSpanHandler zipkinSpanHandler;

    @MockBean
    private CustomerRepository repository;

    @MockBean
    private EmailVerificationService emailVerificationService;

    @MockBean
    private UserService userService;

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
    void setUp() {
        EXPORTED_SPANS.clear();
        EXPORTED_SPAN_BATCHES.clear();
        for (MessageListenerContainer container : listenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, embeddedKafkaBroker.getPartitionsPerTopic());
        }
        when(repository.existsById(ArgumentMatchers.any(UUID.class))).thenReturn(false);
        when(repository.save(ArgumentMatchers.any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void consumesFromKafkaAndExportsMetricsAndTraces() throws Exception {
        CreateUserMessage message = new CreateUserMessage(UUID.randomUUID(), "demo@example.com", "demo");

        kafkaTemplate.send("user-created-topic", message).get(5, TimeUnit.SECONDS);

        verify(repository, timeout(10_000)).save(ArgumentMatchers.any(Customer.class));
        zipkinSpanHandler.flush();
        assertTrue(hasMeterStartingWith("kafka.consumer"));
        assertTrue(hasMeterStartingWith("streaming.customer.provisioning"));

        String provisioningSpan = awaitExportContaining(
                "\"name\":\"provision customer from user-created event\"",
                "\"source\":\"user-created-topic\""
        );
        String consumerSpan = awaitExportContaining(
                "\"kind\":\"CONSUMER\"",
                "\"name\":\"user-created-topic receive\""
        );
        assertNotNull(provisioningSpan);
        assertNotNull(consumerSpan);
        assertTrue(extractTraceId(provisioningSpan).equals(extractTraceId(consumerSpan)));
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

        throw new AssertionError("Expected a trace export containing " + String.join(", ", needles)
                + " but collected " + EXPORTED_SPAN_BATCHES);
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
            throw new AssertionError("Expected an exported trace id in collector body " + body);
        }
        return matcher.group(1);
    }

    private boolean hasMeterStartingWith(String prefix) {
        return meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .anyMatch(name -> name.startsWith(prefix));
    }

    @SpringBootConfiguration
    @EnableKafka
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
            CustomerConsumer.class,
            CustomerServiceImpl.class,
            CustomerConsumerTelemetryIntegrationTest.TestTracingConfig.class
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
                    .localServiceName("customer-service-telemetry-it")
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
        public int messageSizeInBytes(java.util.List<byte[]> encodedSpans) {
            return encoding().listSizeInBytes(encodedSpans);
        }

        @Override
        public int messageSizeInBytes(int spanCount) {
            return encoding().listSizeInBytes(spanCount);
        }

        @Override
        public void send(java.util.List<byte[]> encodedSpans) throws IOException {
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
