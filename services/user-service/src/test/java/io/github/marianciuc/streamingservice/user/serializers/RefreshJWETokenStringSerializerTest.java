/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: RefreshJWETokenStringSerializerTest.java
 *
 */

package io.github.marianciuc.streamingservice.user.serializers;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.DirectEncrypter;
import io.github.marianciuc.streamingservice.user.dto.common.Token;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiredArgsConstructor
@Setter
@Slf4j
public class RefreshJWETokenStringSerializerTest {
    
    private JWEEncrypter jweEncrypter;
    private RefreshJWETokenStringSerializer serializer;

    @BeforeEach
    void setUp() throws KeyLengthException {
        byte[] sharedSecret = new byte[32];
        jweEncrypter = new DirectEncrypter(sharedSecret);
        serializer = new RefreshJWETokenStringSerializer(jweEncrypter)
                .jweAlgorithm(JWEAlgorithm.DIR)
                .encryptionMethod(EncryptionMethod.A256GCM);
    }

    @Test
    public void apply_shouldSerializeToken_whenGivenValidToken() throws JOSEException {
        Token token = new Token(UUID.randomUUID(), UUID.randomUUID(),
                "subject", "issuer",
                Arrays.asList("admin", "user"),
                Instant.now(), Instant.now().plusSeconds(300L));

        String serializedToken = serializer.apply(token);

        assertNotNull(serializedToken);
        log.info("Serialized token: {}", serializedToken);
    }
}
