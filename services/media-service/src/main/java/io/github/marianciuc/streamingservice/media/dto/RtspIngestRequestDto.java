package io.github.marianciuc.streamingservice.media.dto;

import io.github.marianciuc.streamingservice.media.enums.MediaType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RtspIngestRequestDto(
        @NotNull(message = "contentId is required")
        UUID contentId,

        @NotNull(message = "mediaType is required")
        MediaType mediaType,

        @NotBlank(message = "rtspUrl is required")
        String rtspUrl,

        @NotNull(message = "captureDurationSeconds is required")
        @Min(value = 10, message = "captureDurationSeconds must be at least 10")
        @Max(value = 86400, message = "captureDurationSeconds must be at most 86400")
        Integer captureDurationSeconds
) {
}
