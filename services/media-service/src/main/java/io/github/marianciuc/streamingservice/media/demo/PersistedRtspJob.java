package io.github.marianciuc.streamingservice.media.demo;

import java.time.Instant;
import java.util.UUID;

record PersistedRtspJob(
        UUID jobId,
        long jobNumber,
        UUID contentId,
        String mediaType,
        String targetTitle,
        String ingestSourceUrl,
        String sourceUrl,
        String operatorEmail,
        int captureDurationSeconds,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String playbackUrl,
        String playbackFormat,
        String errorMessage,
        String capturePath
) {
}
