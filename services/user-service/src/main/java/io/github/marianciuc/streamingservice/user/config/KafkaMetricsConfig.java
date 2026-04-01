package io.github.marianciuc.streamingservice.user.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.MicrometerProducerListener;

@Configuration
public class KafkaMetricsConfig {

    @Bean
    DefaultKafkaProducerFactoryCustomizer kafkaProducerFactoryCustomizer(MeterRegistry meterRegistry) {
        return producerFactory -> producerFactory.addListener(new MicrometerProducerListener<>(meterRegistry));
    }
}
