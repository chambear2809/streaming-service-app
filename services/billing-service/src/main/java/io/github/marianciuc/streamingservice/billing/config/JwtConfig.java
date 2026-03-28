package io.github.marianciuc.streamingservice.billing.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import java.text.ParseException;

@Configuration
@Profile("!broadcast-demo")
public class JwtConfig {

    @Bean
    public JWSVerifier jwsVerifier(
            WebClient webClient,
            @Value("${application.config.jwt-public-key-url}") String publicKeyUrl
    ) throws ParseException, JOSEException {
        String publicKey = webClient.get()
                .uri(publicKeyUrl)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalStateException("JWT public key response was empty.");
        }

        return new RSASSAVerifier(RSAKey.parse(publicKey));
    }
}
