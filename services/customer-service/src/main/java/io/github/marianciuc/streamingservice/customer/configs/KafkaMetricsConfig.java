package io.github.marianciuc.streamingservice.customer.configs;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;

@Configuration
public class KafkaMetricsConfig {

    @Bean
    DefaultKafkaConsumerFactoryCustomizer kafkaConsumerFactoryCustomizer(MeterRegistry meterRegistry) {
        return consumerFactory -> consumerFactory.addListener(new MicrometerConsumerListener<>(meterRegistry));
    }

    @Bean
    DefaultKafkaProducerFactoryCustomizer kafkaProducerFactoryCustomizer(MeterRegistry meterRegistry) {
        return producerFactory -> producerFactory.addListener(new MicrometerProducerListener<>(meterRegistry));
    }
}
