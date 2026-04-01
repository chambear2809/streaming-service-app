/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: KafkaUserProducer.java
 *
 */

package io.github.marianciuc.streamingservice.user.kafka;

import io.github.marianciuc.streamingservice.user.kafka.messages.CreateUserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
@Component
public class KafkaUserProducer {

    public static final String USER_CREATED_TOPIC = "user-created-topic";

    private final KafkaTemplate<String, CreateUserMessage> kafkaTemplate;
    
    public void sendUserCreatedMessage(CreateUserMessage createUserMessage) {
        Message<CreateUserMessage> message = MessageBuilder
                .withPayload(createUserMessage)
                .setHeader(KafkaHeaders.TOPIC, USER_CREATED_TOPIC)
                .build();
        try {
            kafkaTemplate.send(message).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing user-created Kafka event.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Unable to publish user-created Kafka event.", exception);
        }
    }
}
