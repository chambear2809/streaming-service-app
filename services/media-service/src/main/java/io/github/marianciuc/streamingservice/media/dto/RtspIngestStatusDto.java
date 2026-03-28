package io.github.marianciuc.streamingservice.media.dto;

import io.github.marianciuc.streamingservice.media.enums.MediaType;
import io.github.marianciuc.streamingservice.media.enums.VideoSourceType;
import io.github.marianciuc.streamingservice.media.enums.VideoStatues;

import java.util.UUID;

public record RtspIngestStatusDto(
        UUID videoId,
        UUID contentId,
        MediaType mediaType,
        VideoSourceType sourceType,
        String sourceUrl,
        VideoStatues status,
        Boolean ingestActive,
        Boolean streamReady,
        String playbackUrl
) {
    public static RtspIngestStatusDto fromVideo(VideoDto video, boolean ingestActive) {
        String playbackUrl = video.masterPlaylistPath() == null || video.masterPlaylistPath().isBlank()
                ? null
                : "/api/v1/stream/playlist/" + video.id() + ".m3u8";

        return new RtspIngestStatusDto(
                video.id(),
                video.contentId(),
                video.mediaType(),
                video.sourceType(),
                video.sourceUrl(),
                video.status(),
                ingestActive,
                playbackUrl != null,
                playbackUrl
        );
    }
}
