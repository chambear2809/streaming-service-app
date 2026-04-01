package io.github.marianciuc.streamingservice.user.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaMetricsConfigTest {

    @Mock
    private DefaultKafkaProducerFactory<Object, Object> producerFactory;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void kafkaProducerFactoryCustomizerAddsMicrometerListener() {
        KafkaMetricsConfig config = new KafkaMetricsConfig();

        config.kafkaProducerFactoryCustomizer(new SimpleMeterRegistry()).customize(producerFactory);

        ArgumentCaptor<ProducerFactory.Listener> listenerCaptor = ArgumentCaptor.forClass(ProducerFactory.Listener.class);
        verify(producerFactory).addListener(listenerCaptor.capture());
        assertInstanceOf(org.springframework.kafka.core.MicrometerProducerListener.class, listenerCaptor.getValue());
    }
}
