/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: VideoProcessingServiceImpl.java
 *
 */

package io.github.marianciuc.streamingservice.media.services.impl;

import jakarta.annotation.PreDestroy;
import io.github.marianciuc.streamingservice.media.dto.ResolutionDto;
import io.github.marianciuc.streamingservice.media.dto.VideoDto;
import io.github.marianciuc.streamingservice.media.dto.VideoFileMetadataDto;
import io.github.marianciuc.streamingservice.media.kafka.messages.StartConvertingMessage;
import io.github.marianciuc.streamingservice.media.enums.VideoStatues;
import io.github.marianciuc.streamingservice.media.services.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessingServiceImpl implements VideoProcessingService {

    private final VideoService videoService;
    private final VideoStorageService videoStorage;
    private final VideoCompressingService videoCompressingService;
    private final VideoFileMetadataService videoFileMetadataService;
    private final ResolutionService resolutionService;
    private final PlaylistService playlistService;
    private final ExecutorService processingExecutor = Executors.newFixedThreadPool(3, new VideoProcessingThreadFactory());

    @PreDestroy
    void shutdownProcessingExecutor() {
        processingExecutor.shutdownNow();
    }

    @Override
    public void processMediaFile(StartConvertingMessage message) {
        try {
            String playlist = this.videoCompressingService.compressVideoAndUploadToStorage(message.resolution(), message.videoId());
            videoFileMetadataService.updateMetadata(message.metadataId(), true, playlist);

            VideoDto refreshedVideo = this.videoService.getVideoById(message.videoId());
            if (isVideoProcessingFinished(refreshedVideo)) {
                finalizeVideoProcessing(refreshedVideo);
            }
        } catch (RuntimeException exception) {
            log.error("Video processing failed for video {}", message.videoId(), exception);
            videoService.updateVideoStatus(message.videoId(), VideoStatues.ERROR);
            throw exception;
        }
    }

    @Override
    public void startVideoProcessing(UUID id) {
        VideoDto video = this.videoService.getVideoById(id);
        this.videoService.updateVideoStatus(id, VideoStatues.PROCESSING);
        List<ResolutionDto> resolutions = resolutionService.getAllResolutions();
        if (resolutions.isEmpty()) {
            videoService.updateVideoStatus(id, VideoStatues.ERROR);
            throw new IllegalStateException("No resolutions are configured for video processing.");
        }

        for (ResolutionDto resolution : resolutions) {
            VideoFileMetadataDto metadata = videoFileMetadataService.createMetadata(video, resolution);
            StartConvertingMessage message = new StartConvertingMessage(metadata.id(), video.id(), resolution);
            CompletableFuture.runAsync(() -> processMediaFile(message), processingExecutor)
                    .exceptionally(exception -> {
                        log.error("Async processing failed for video {}", video.id(), exception);
                        return null;
                    });
        }
    }

    private boolean isVideoProcessingFinished(VideoDto videoDto) {
        VideoDto video = videoService.getVideoById(videoDto.id());
        return video.files().stream().allMatch(VideoFileMetadataDto::isProcessed);
    }

    private void finalizeVideoProcessing(VideoDto videoDto) {
        VideoDto currentVideo = videoService.getVideoById(videoDto.id());
        if (currentVideo.status() == VideoStatues.PROCESSED) {
            return;
        }

        playlistService.createMasterPlaylist(videoDto.id());
        videoService.updateVideoStatus(videoDto.id(), VideoStatues.PROCESSED);
        videoStorage.deleteVideo(videoDto.id());
    }

    private static final class VideoProcessingThreadFactory implements ThreadFactory {
        private final AtomicInteger threadCounter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "video-processing-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
