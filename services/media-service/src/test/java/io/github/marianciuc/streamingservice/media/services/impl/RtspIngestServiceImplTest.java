package io.github.marianciuc.streamingservice.media.services.impl;

import io.github.marianciuc.streamingservice.media.dto.RtspIngestRequestDto;
import io.github.marianciuc.streamingservice.media.dto.RtspIngestStatusDto;
import io.github.marianciuc.streamingservice.media.dto.VideoDto;
import io.github.marianciuc.streamingservice.media.enums.MediaType;
import io.github.marianciuc.streamingservice.media.enums.VideoSourceType;
import io.github.marianciuc.streamingservice.media.enums.VideoStatues;
import io.github.marianciuc.streamingservice.media.exceptions.RtspIngestException;
import io.github.marianciuc.streamingservice.media.services.VideoProcessingService;
import io.github.marianciuc.streamingservice.media.services.VideoService;
import io.github.marianciuc.streamingservice.media.services.VideoStorageService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class RtspIngestServiceImplTest {

    private final VideoService videoService = mock(VideoService.class);
    private final VideoStorageService videoStorageService = mock(VideoStorageService.class);
    private final VideoProcessingService videoProcessingService = mock(VideoProcessingService.class);

    @Test
    void getStatusClearsCompletedImmediateIngest() {
        UUID contentId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        RtspIngestRequestDto request = new RtspIngestRequestDto(contentId, MediaType.MOVIE, "rtsp://camera/live", 10);
        VideoDto video = video(videoId, contentId, VideoStatues.UPLOADED);

        when(videoService.createVideoEntity(contentId, MediaType.MOVIE, "application/x-rtsp", VideoSourceType.RTSP, "rtsp://camera/live", false))
                .thenReturn(video);
        when(videoService.getVideoById(videoId)).thenReturn(video);

        RtspIngestServiceImpl service = spy(new RtspIngestServiceImpl(
                videoService,
                videoStorageService,
                videoProcessingService,
                new DirectExecutorService()
        ));
        doNothing().when(service).ingestRtspSource(eq(videoId), any(RtspIngestRequestDto.class));

        service.startIngest(request);
        RtspIngestStatusDto status = service.getStatus(videoId);

        Assertions.assertFalse(status.ingestActive());
    }

    @Test
    void getStatusClearsFailedImmediateIngest() {
        UUID contentId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        RtspIngestRequestDto request = new RtspIngestRequestDto(contentId, MediaType.MOVIE, "rtsp://camera/live", 10);
        VideoDto video = video(videoId, contentId, VideoStatues.ERROR);

        when(videoService.createVideoEntity(contentId, MediaType.MOVIE, "application/x-rtsp", VideoSourceType.RTSP, "rtsp://camera/live", false))
                .thenReturn(video);
        when(videoService.getVideoById(videoId)).thenReturn(video);

        RtspIngestServiceImpl service = spy(new RtspIngestServiceImpl(
                videoService,
                videoStorageService,
                videoProcessingService,
                new DirectExecutorService()
        ));
        doThrow(new RtspIngestException("boom")).when(service).ingestRtspSource(eq(videoId), any(RtspIngestRequestDto.class));

        service.startIngest(request);
        RtspIngestStatusDto status = service.getStatus(videoId);

        Assertions.assertFalse(status.ingestActive());
    }

    private VideoDto video(UUID videoId, UUID contentId, VideoStatues status) {
        return new VideoDto(
                videoId,
                contentId,
                "application/x-rtsp",
                MediaType.MOVIE,
                0,
                status,
                VideoSourceType.RTSP,
                "rtsp://camera/live",
                false,
                null,
                Set.of(),
                Set.of()
        );
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
