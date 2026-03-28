package io.github.marianciuc.streamingservice.billing.security.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Token(
        UUID tokenId,
        UUID userId,
        String subject,
        String issuer,
        List<String> roles,
        Instant createdAt,
        Instant expiresAt
) {
}
