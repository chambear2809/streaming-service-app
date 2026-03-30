package io.github.marianciuc.streamingservice.media.demo;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DemoMediaRtspWorkflowTest {

    @TempDir
    Path tempDir;

    private TestDemoMediaController controller;

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.shutdownRtspWorkers();
        }
    }

    @Test
    void bundledDemoCaptureHonorsRequestedCaptureDuration() throws Exception {
        controller = controllerWithTempRoots(tempDir);
        Path bundledFeed = createSyntheticVideo(tempDir.resolve("bundled-demo.mp4"), 40, 25);
        ReflectionTestUtils.setField(controller, "demoMediaFile", bundledFeed.toString());

        DemoMediaController.RtspJobResponse created = controller.createRtspJob(new DemoMediaController.RtspJobRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "MOVIE",
                "Synthetic Demo Feed",
                "rtsp://demo.acmebroadcasting.local/live",
                30,
                "ops@acmebroadcasting.com"
        ));

        DemoMediaController.RtspJobResponse ready = awaitReadyJob(created.jobId(), Duration.ofSeconds(30));
        Path capturePath = tempDir.resolve("review").resolve(created.jobId() + ".mp4");

        assertEquals("READY", ready.status());
        assertTrue(Files.exists(capturePath));

        double sourceDurationSeconds = mediaDurationSeconds(bundledFeed);
        double capturedDurationSeconds = mediaDurationSeconds(capturePath);

        assertTrue(sourceDurationSeconds >= 39.0, "expected a deterministic bundled source longer than the requested capture");
        assertTrue(capturedDurationSeconds >= 28.0, "captured review should preserve most of the requested 30-second window");
        assertTrue(capturedDurationSeconds <= 31.5, "captured review should not include the full bundled movie");
        assertTrue(capturedDurationSeconds < sourceDurationSeconds - 5.0, "captured review should be materially shorter than the source movie");
    }

    @Test
    void activateBroadcastJobUsesSavedCaptureForReadyContribution() throws Exception {
        controller = controllerWithTempRoots(tempDir);

        UUID jobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Path capturePath = Files.writeString(tempDir.resolve("ready-capture.mp4"), "ready");
        insertRtspJob(restoredRtspJob(
                jobId,
                "READY",
                "Ready Contribution",
                "rtsp://uplink.example/live",
                "/api/v1/demo/media/rtsp/jobs/" + jobId + "/playback.mp4",
                capturePath
        ));

        DemoMediaController.BroadcastStatusResponse response = controller.activateBroadcastJob(jobId);
        DemoMediaController.BroadcastSelection selection = controller.resolveBroadcastSelection();

        assertEquals("ON_AIR", response.status());
        assertEquals("RTSP_CONTRIBUTION", response.sourceType());
        assertEquals(capturePath, selection.mediaPath());
        assertNull(selection.liveSourceUrl());
        assertEquals("/api/v1/demo/media/rtsp/jobs/" + jobId + "/playback.mp4", selection.operatorPlaybackUrl());
    }

    @Test
    void activateBroadcastJobKeepsCapturingContributionOnRtspPassthrough() throws Exception {
        controller = controllerWithTempRoots(tempDir);

        UUID jobId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String ingestSourceUrl = "rtsp://uplink.example/live";
        insertRtspJob(restoredRtspJob(
                jobId,
                "CAPTURING",
                "Live Contribution",
                ingestSourceUrl,
                null,
                null
        ));

        DemoMediaController.BroadcastStatusResponse response = controller.activateBroadcastJob(jobId);
        DemoMediaController.BroadcastSelection selection = controller.resolveBroadcastSelection();

        assertEquals("ON_AIR", response.status());
        assertEquals("RTSP_CONTRIBUTION", response.sourceType());
        assertNull(selection.mediaPath());
        assertEquals(ingestSourceUrl, selection.liveSourceUrl());
        assertEquals("Live RTSP contribution is routed to the external channel while the review capture runs.", selection.detail());
    }

    private TestDemoMediaController controllerWithTempRoots(Path root) {
        TestDemoMediaController testController = new TestDemoMediaController(new InMemoryDemoMediaStateRepository());
        ReflectionTestUtils.setField(testController, "demoMediaRoot", root.resolve("library").toString());
        ReflectionTestUtils.setField(testController, "demoBroadcastRoot", root.resolve("broadcast").toString());
        ReflectionTestUtils.setField(testController, "demoReviewRoot", root.resolve("review").toString());
        ReflectionTestUtils.setField(testController, "demoMediaFile", root.resolve("demo.mp4").toString());
        return testController;
    }

    private DemoMediaController.RtspJobResponse awaitReadyJob(UUID jobId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        DemoMediaController.RtspJobResponse latest = controller.getRtspJob(jobId);
        while (Instant.now().isBefore(deadline)) {
            latest = controller.getRtspJob(jobId);
            if ("READY".equals(latest.status())) {
                return latest;
            }
            if ("ERROR".equals(latest.status())) {
                fail("bundled demo capture failed: " + latest.errorMessage());
            }
            Thread.sleep(100);
        }

        fail("timed out waiting for RTSP job " + jobId + " to reach READY; last status was " + latest.status());
        return latest;
    }

    private Path createSyntheticVideo(Path outputPath, int durationSeconds, int framesPerSecond) throws Exception {
        int width = 64;
        int height = 36;
        Files.createDirectories(outputPath.getParent());

        Java2DFrameConverter converter = new Java2DFrameConverter();
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath.toFile(), width, height, 0)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(framesPerSecond);
            recorder.start();

            int totalFrames = durationSeconds * framesPerSecond;
            for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(new Color((frameIndex * 17) % 255, 64, 180));
                graphics.fillRect(0, 0, width, height);
                graphics.dispose();

                recorder.setTimestamp(frameIndex * 1_000_000L / framesPerSecond);
                recorder.record(converter.convert(image));
            }

            recorder.stop();
        }

        return outputPath;
    }

    private double mediaDurationSeconds(Path mediaPath) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaPath.toFile())) {
            grabber.start();
            double durationSeconds = grabber.getLengthInTime() / 1_000_000d;
            grabber.stop();
            return durationSeconds;
        }
    }

    private void insertRtspJob(Object job) {
        @SuppressWarnings("unchecked")
        Map<UUID, Object> rtspJobs = (Map<UUID, Object>) ReflectionTestUtils.getField(controller, "rtspJobs");
        assertNotNull(rtspJobs);
        rtspJobs.put(jobId(job), job);
    }

    private UUID jobId(Object job) {
        try {
            Method method = job.getClass().getDeclaredMethod("jobId");
            method.setAccessible(true);
            return (UUID) method.invoke(job);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read RTSP job id", exception);
        }
    }

    private Object restoredRtspJob(
            UUID jobId,
            String status,
            String title,
            String ingestSourceUrl,
            String playbackUrl,
            Path capturePath
    ) {
        try {
            Class<?> jobClass = Class.forName(DemoMediaController.class.getName() + "$RtspJob");
            Method restored = jobClass.getDeclaredMethod(
                    "restored",
                    UUID.class,
                    long.class,
                    UUID.class,
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    int.class,
                    Instant.class,
                    Instant.class,
                    String.class,
                    String.class,
                    String.class,
                    Path.class
            );
            restored.setAccessible(true);
            Instant createdAt = Instant.now().minusSeconds(10);
            return restored.invoke(
                    null,
                    jobId,
                    4101L,
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "MOVIE",
                    title,
                    ingestSourceUrl,
                    ingestSourceUrl,
                    "ops@acmebroadcasting.com",
                    90,
                    createdAt,
                    createdAt.plusSeconds(5),
                    status,
                    playbackUrl,
                    null,
                    capturePath
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create restored RTSP job", exception);
        }
    }

    private static final class TestDemoMediaController extends DemoMediaController {

        private TestDemoMediaController(DemoMediaStateRepository stateRepository) {
            super(stateRepository);
        }

        @Override
        synchronized void ensureBroadcastRelayConfigured(BroadcastSelection selection) {
        }

        @Override
        BroadcastAdStatusResponse resolveAdStatus(BroadcastSelection selection) {
            return new BroadcastAdStatusResponse(
                    "ARMED",
                    "Sponsor pod A",
                    "North Coast Sports Network",
                    "Test ad status",
                    null,
                    null,
                    "Test ad status"
            );
        }
    }

    private static final class InMemoryDemoMediaStateRepository extends DemoMediaStateRepository {

        private final Map<String, String> state = new LinkedHashMap<>();
        private final Map<UUID, PersistedRtspJob> jobs = new LinkedHashMap<>();

        private InMemoryDemoMediaStateRepository() {
            super(null);
        }

        @Override
        List<PersistedRtspJob> findAllJobs() {
            return List.copyOf(jobs.values());
        }

        @Override
        void saveJob(PersistedRtspJob job) {
            jobs.put(job.jobId(), job);
        }

        @Override
        Optional<String> loadStateJson(String key) {
            return Optional.ofNullable(state.get(key));
        }

        @Override
        void saveStateJson(String key, String json) {
            state.put(key, json);
        }
    }
}
