/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: PlaylistServiceImpl.java
 *
 */

package io.github.marianciuc.streamingservice.media.services.impl;

import io.github.marianciuc.streamingservice.media.dto.ResolutionDto;
import io.github.marianciuc.streamingservice.media.dto.VideoDto;
import io.github.marianciuc.streamingservice.media.kafka.KafkaPlaylistProducer;
import io.github.marianciuc.streamingservice.media.kafka.messages.MasterPlaylistMessage;
import io.github.marianciuc.streamingservice.media.services.PlaylistService;
import io.github.marianciuc.streamingservice.media.services.VideoService;
import io.github.marianciuc.streamingservice.media.services.VideoStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlaylistServiceImpl implements PlaylistService {

    private final VideoStorageService videoStorage;
    private final VideoService videoService;
    private final KafkaPlaylistProducer kafkaPlaylistProducer;
    private static final String HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl";
    private static final int HLS_TARGET_DURATION_SECONDS = 10;

    @Override
    public void createMasterPlaylist(UUID videoId) {
        VideoDto video = videoService.getVideoById(videoId);

        String content = video.files().stream()
                .map(file -> String.format("#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d\n%s\n",
                        file.resolution().bitrate(),
                        file.resolution().width(),
                        file.resolution().height(),
                        file.playlistPath()))
                .collect(Collectors.joining("", "#EXTM3U\n", ""));

        String storagePath = String.format(VideoStorageService.PATH_MASTER_PLAYLIST_TEMPLATE, video.id());
        String playbackPath = buildMasterPlaylistPlaybackPath(video.id());

        try {
            byte[] playlistBytes = content.getBytes(StandardCharsets.UTF_8);
            this.videoStorage.uploadFile(storagePath, new ByteArrayInputStream(playlistBytes), playlistBytes.length, HLS_CONTENT_TYPE);
            this.videoService.updateMasterPlaylistUrl(storagePath, video.id());
        } catch (Exception e) {
            throw new RuntimeException("Failed to save master playlist", e);
        }

        try {
            this.kafkaPlaylistProducer.sendMasterPlaylistCreated(
                    new MasterPlaylistMessage(video.contentId(), video.mediaType(), video.id(), playbackPath)
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to publish master playlist update for video {}", video.id(), exception);
        }
    }

    @Override
    public Resource getMasterPlaylistResource(UUID videoId) {
        String masterPlaylistPath = this.videoService.getVideoById(videoId).masterPlaylistPath();
        return new InputStreamResource(videoStorage.getFileInputStream(masterPlaylistPath));
    }

    @Override
    public Resource getResolutionPlaylistResource(UUID videoId, int resolution) {
        String playlistPath = String.format(VideoStorageService.PATH_RESOLUTION_PLAYLIST_TEMPLATE, videoId, resolution);
        return new InputStreamResource(videoStorage.getFileInputStream(playlistPath));
    }

    @Override
    public Resource getVideoSegmentResource(UUID videoId, int resolution, int chunkIndex) {
        String segmentPath = String.format(VideoStorageService.PATH_CHUNK_TEMPLATE, videoId, resolution, chunkIndex);
        return new InputStreamResource(videoStorage.getFileInputStream(segmentPath));
    }

    @Override
    public StringBuilder generateResolutionPlaylist() {
        return new StringBuilder()
                .append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-TARGETDURATION:").append(HLS_TARGET_DURATION_SECONDS).append('\n')
                .append("#EXT-X-MEDIA-SEQUENCE:0\n");
    }

    @Override
    public void appendResolutionPlaylist(int resolution, UUID videoId, int chunkIndex, StringBuilder playlist, int chunkDurationSeconds) {
        playlist.append("#EXTINF:")
                .append(chunkDurationSeconds)
                .append(",\n")
                .append(buildSegmentPlaybackPath(videoId, resolution, chunkIndex))
                .append('\n');
    }

    @Override
    public String buildResolutionPlaylist(UUID videoId, StringBuilder playlist, ResolutionDto resolution) {
        String storagePath = String.format(VideoStorageService.PATH_RESOLUTION_PLAYLIST_TEMPLATE, videoId, resolution.height());
        String playbackPath = buildResolutionPlaylistPlaybackPath(videoId, resolution.height());

        playlist.append("#EXT-X-ENDLIST\n");
        byte[] playlistBytes = playlist.toString().getBytes(StandardCharsets.UTF_8);

        try {
            videoStorage.uploadFile(storagePath, new ByteArrayInputStream(playlistBytes), playlistBytes.length, HLS_CONTENT_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save resolution playlist", e);
        }

        return playbackPath;
    }

    private String buildMasterPlaylistPlaybackPath(UUID videoId) {
        return "/api/v1/stream/playlist/" + videoId + ".m3u8";
    }

    private String buildResolutionPlaylistPlaybackPath(UUID videoId, int resolution) {
        return "/api/v1/stream/playlist/" + videoId + "/" + resolution + ".m3u8";
    }

    private String buildSegmentPlaybackPath(UUID videoId, int resolution, int chunkIndex) {
        return "/api/v1/stream/segment/" + resolution + "/" + videoId + "/segment" + chunkIndex + ".ts";
    }
}
