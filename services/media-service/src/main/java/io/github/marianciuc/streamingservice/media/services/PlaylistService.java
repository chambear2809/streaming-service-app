/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: PlaylistService.java
 *
 */

package io.github.marianciuc.streamingservice.media.services;

import io.github.marianciuc.streamingservice.media.dto.ResolutionDto;
import org.springframework.core.io.Resource;

import java.util.UUID;

/**
 * This interface provides methods for creating and retrieving video playlists.
 */
public interface PlaylistService {

    /**
     * This method creates a master playlist for the video and stores it via VideoStorageService and save link to
     * Video entity
     * @param videoId the unique identifier of the video
     * @throws RuntimeException if an error occurs during the master playlist creation process
     */
    void createMasterPlaylist(UUID videoId);

    /**
     * Returns the master playlist resource for the specified video.
     * @param videoId the unique identifier of the video
     * @return the master playlist resource
     */
    Resource getMasterPlaylistResource(UUID videoId);

    /**
     * Returns the variant playlist resource for a given video and resolution.
     *
     * @param videoId the unique identifier of the video
     * @param resolution the resolution height
     * @return the variant playlist resource
     */
    Resource getResolutionPlaylistResource(UUID videoId, int resolution);

    /**
     * @param videoId the unique identifier of the video
     * @param resolution the resolution height of the video
     * @param chunkIndex the index of the video chunk
     * @return the video segment resource
     */
    Resource getVideoSegmentResource(UUID videoId, int resolution, int chunkIndex);

    /**
     * Creates a playlist builder pre-populated with the HLS headers required for a variant playlist.
     *
     * @return a playlist builder
     */
    StringBuilder generateResolutionPlaylist();

    /**
     * Appends a single transport stream segment entry to the variant playlist.
     *
     * @param resolution the resolution height of the segment
     * @param videoId the unique identifier of the video
     * @param chunkIndex the index of the segment
     * @param playlist the playlist builder to update
     * @param chunkDurationSeconds the duration of the segment
     */
    void appendResolutionPlaylist(int resolution, UUID videoId, int chunkIndex, StringBuilder playlist, int chunkDurationSeconds);

    /**
     * Finalizes and uploads a variant playlist for a given resolution.
     *
     * @param videoId the unique identifier of the video
     * @param playlist the playlist contents
     * @param resolution the target resolution
     * @return the public playback path for the uploaded variant playlist
     */
    String buildResolutionPlaylist(UUID videoId, StringBuilder playlist, ResolutionDto resolution);
}
