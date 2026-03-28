/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: VideoService.java
 *
 */

package io.github.marianciuc.streamingservice.media.services;

import io.github.marianciuc.streamingservice.media.dto.VideoDto;
import io.github.marianciuc.streamingservice.media.enums.VideoSourceType;
import io.github.marianciuc.streamingservice.media.enums.VideoStatues;
import io.github.marianciuc.streamingservice.media.enums.MediaType;

import java.util.UUID;

public interface VideoService {
    void deleteVideoByContent(UUID contentId);
    void updateMasterPlaylistUrl(String url, UUID videoId);
    VideoDto getVideoById(UUID videoId);
    VideoDto createVideoEntity(UUID contentId, Integer totalChunks, MediaType mediaType);
    VideoDto createVideoEntity(UUID contentId, MediaType mediaType, String contentType, VideoSourceType sourceType, String sourceUrl, boolean liveStream);
    void updateVideoStatus(UUID videoId, VideoStatues status);
}
