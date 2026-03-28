package io.github.marianciuc.streamingservice.billing.security.serialization;

import io.github.marianciuc.streamingservice.billing.security.dto.Token;

import java.util.function.Function;

public interface TokenDeserializer extends Function<String, Token> {
}
