package io.github.marianciuc.streamingservice.media.services.impl;

import io.github.marianciuc.streamingservice.media.dto.RtspIngestRequestDto;
import io.github.marianciuc.streamingservice.media.dto.RtspIngestStatusDto;
import io.github.marianciuc.streamingservice.media.dto.VideoDto;
import io.github.marianciuc.streamingservice.media.enums.VideoSourceType;
import io.github.marianciuc.streamingservice.media.enums.VideoStatues;
import io.github.marianciuc.streamingservice.media.exceptions.RtspIngestException;
import io.github.marianciuc.streamingservice.media.services.RtspIngestService;
import io.github.marianciuc.streamingservice.media.services.VideoProcessingService;
import io.github.marianciuc.streamingservice.media.services.VideoService;
import io.github.marianciuc.streamingservice.media.services.VideoStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class RtspIngestServiceImpl implements RtspIngestService {

    private static final String RTSP_CONTENT_TYPE = "application/x-rtsp";
    private static final int DEFAULT_FRAME_RATE = 25;
    private static final long RTSP_TIMEOUT_MICROS = 10_000_000L;

    private final VideoService videoService;
    private final VideoStorageService videoStorageService;
    private final VideoProcessingService videoProcessingService;

    private final Map<UUID, CompletableFuture<Void>> activeIngests = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(new RtspThreadFactory());

    @Override
    public RtspIngestStatusDto startIngest(RtspIngestRequestDto request) {
        VideoDto video = videoService.createVideoEntity(
                request.contentId(),
                request.mediaType(),
                RTSP_CONTENT_TYPE,
                VideoSourceType.RTSP,
                redactRtspUrl(request.rtspUrl()),
                false
        );

        CompletableFuture<Void> ingestTask = CompletableFuture
                .runAsync(() -> ingestRtspSource(video.id(), request), executor)
                .whenComplete((unused, throwable) -> activeIngests.remove(video.id()));

        activeIngests.put(video.id(), ingestTask);

        return RtspIngestStatusDto.fromVideo(video, true);
    }

    @Override
    public RtspIngestStatusDto getStatus(UUID videoId) {
        VideoDto video = videoService.getVideoById(videoId);
        return RtspIngestStatusDto.fromVideo(video, activeIngests.containsKey(videoId));
    }

    private void ingestRtspSource(UUID videoId, RtspIngestRequestDto request) {
        Path tempFile = null;
        try {
            videoService.updateVideoStatus(videoId, VideoStatues.PREPARING);
            tempFile = captureRtspToFile(request.rtspUrl(), request.captureDurationSeconds());

            long fileSize = Files.size(tempFile);
            try (InputStream inputStream = Files.newInputStream(tempFile)) {
                videoStorageService.uploadSourceVideo(videoId, inputStream, fileSize, "video/mp4");
            }

            videoService.updateVideoStatus(videoId, VideoStatues.UPLOADED);
            videoProcessingService.startVideoProcessing(videoId);
        } catch (Exception e) {
            log.error("RTSP ingest failed for video {}", videoId, e);
            videoService.updateVideoStatus(videoId, VideoStatues.ERROR);
            throw new RtspIngestException("Failed to ingest RTSP source", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("Failed to delete temporary RTSP file {}", tempFile, e);
                }
            }
        }
    }

    private Path captureRtspToFile(String rtspUrl, int captureDurationSeconds) {
        Path outputFile = createTemporaryOutputFile();
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(captureDurationSeconds).toNanos();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
            configureGrabber(grabber);
            grabber.start();

            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if (width <= 0 || height <= 0) {
                throw new RtspIngestException("RTSP source did not provide a video stream");
            }
            int audioChannels = Math.max(grabber.getAudioChannels(), 0);

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile.toFile(), width, height, audioChannels)) {
                configureRecorder(recorder, grabber);
                recorder.start();

                boolean receivedFrame = false;
                Frame frame;
                while (System.nanoTime() < deadlineNanos && (frame = grabber.grabFrame()) != null) {
                    recorder.record(frame);
                    receivedFrame = true;
                }

                recorder.stop();

                if (!receivedFrame) {
                    throw new RtspIngestException("No frames received from RTSP source");
                }
            }

            grabber.stop();
            return outputFile;
        } catch (Exception e) {
            throw new RtspIngestException("Failed to capture RTSP stream", e);
        }
    }

    private void configureGrabber(FFmpegFrameGrabber grabber) {
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("stimeout", String.valueOf(RTSP_TIMEOUT_MICROS));
        grabber.setOption("rw_timeout", String.valueOf(RTSP_TIMEOUT_MICROS));
    }

    private void configureRecorder(FFmpegFrameRecorder recorder, FFmpegFrameGrabber grabber) {
        recorder.setFormat("mp4");
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        recorder.setFrameRate(Math.max((int) grabber.getFrameRate(), DEFAULT_FRAME_RATE));

        if (grabber.getAudioChannels() > 0) {
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.setAudioChannels(grabber.getAudioChannels());
        }

        if (grabber.getSampleRate() > 0) {
            recorder.setSampleRate(grabber.getSampleRate());
        }
    }

    private Path createTemporaryOutputFile() {
        try {
            return Files.createTempFile("rtsp-capture-", ".mp4");
        } catch (Exception e) {
            throw new RtspIngestException("Failed to create a temporary RTSP capture file", e);
        }
    }

    private String redactRtspUrl(String rtspUrl) {
        return rtspUrl.replaceAll("(?i)(?<=rtsp://)[^/@\\s]+@", "***@");
    }

    private static final class RtspThreadFactory implements ThreadFactory {
        private final AtomicInteger threadCounter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "rtsp-ingest-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
