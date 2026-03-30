package io.github.marianciuc.streamingservice.media.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@RestController
@CrossOrigin(origins = "*")
@Profile("broadcast-demo")
public class DemoMediaController {

    private static final String ACTIVE_BROADCAST_STATE_KEY = "active-broadcast";
    private static final String DEMO_MONKEY_STATE_KEY = "demo-monkey";

    private static final String READY_STATUS = "READY";
    private static final String ERROR_STATUS = "ERROR";
    private static final String QUEUED_STATUS = "QUEUED";
    private static final String CONNECTING_STATUS = "CONNECTING";
    private static final String CAPTURING_STATUS = "CAPTURING";
    private static final String TRANSCODING_STATUS = "TRANSCODING";
    private static final String RTSP_PREFIX = "rtsp://";
    private static final String MP4_FORMAT = "MP4";
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_FRAME_RATE = 25;
    private static final long RTSP_TIMEOUT_MICROS = 10_000_000L;
    private static final int MAX_STARTUP_DELAY_MS = 15_000;
    private static final int MAX_THROTTLE_KBPS = 10_000;
    private static final int MAX_DISCONNECT_AFTER_KB = 16_384;
    private static final int MAX_PACKET_LOSS_PERCENT = 100;
    private static final Duration TRACE_MAP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PUBLIC_BROADCAST_PROXY_TIMEOUT = Duration.ofSeconds(3);
    private static final int PUBLIC_BROADCAST_PROXY_RETRIES = 3;
    private static final Duration PUBLIC_BROADCAST_PROXY_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration PUBLIC_BROADCAST_PLAYLIST_WARMUP_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration PUBLIC_BROADCAST_PLAYLIST_WARMUP_POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration RTSP_TERMINAL_JOB_RETENTION = Duration.ofHours(1);
    private static final Duration RTSP_IN_PROGRESS_GRACE_PERIOD = Duration.ofMinutes(15);
    private static final Duration RTSP_CLEANUP_INTERVAL = Duration.ofMinutes(5);
    private static final long FORCED_DEPENDENCY_TIMEOUT_BUFFER_MS = 250L;
    private static final String PLAYBACK_PATH_TEMPLATE = "/api/v1/demo/media/rtsp/jobs/%s/playback.mp4";
    private static final String PUBLIC_BROADCAST_PAGE_PATH = "/broadcast";
    private static final String PUBLIC_BROADCAST_PLAYBACK_PATH = "/api/v1/demo/public/broadcast/live/index.m3u8";
    private static final String PUBLIC_BROADCAST_LEGACY_MP4_PATH = "/api/v1/demo/public/broadcast/live.mp4";
    private static final String PUBLIC_BROADCAST_HLS_BASE_URL = "http://127.0.0.1:8888/live/";
    private static final String BUNDLED_DEMO_RTSP_SOURCE_URL = "rtsp://127.0.0.1:8554/demo-source";
    private static final String PUBLIC_BROADCAST_FILE_NAME = "current.mp4";
    private static final String PUBLIC_BROADCAST_ROUTE_FILE_NAME = "current-source.txt";
    private static final String HOUSE_LOOP_PLAYLIST_FILE_NAME = "house-loop-sources.txt";
    private static final String HOUSE_LOOP_SEGMENT_ROOT = "house-loop-segments";
    private static final String BROADCAST_CHANNEL_ID = "acme-network-east";
    private static final String BROADCAST_CHANNEL_LABEL = "Acme Network East";
    private static final String HOUSE_LINEUP_TITLE = "Acme House Lineup";
    private static final String HOUSE_LINEUP_DETAIL = DemoBroadcastAdSchedule.DEFAULT_BROADCAST_DETAIL;
    private static final String BROADCAST_STATUS_ON_AIR = "ON_AIR";
    private static final String BROADCAST_STATUS_DEMO_LOOP = "DEMO_LOOP";
    private static final String BROADCAST_SOURCE_TYPE_RTSP = "RTSP_CONTRIBUTION";
    private static final String BROADCAST_SOURCE_TYPE_DEMO = "DEMO_LIBRARY";
    private static final String BROADCAST_ROUTE_KIND_PLAYLIST = "playlist";
    private static final DemoBroadcastAdSchedule BROADCAST_AD_SCHEDULE = DemoBroadcastAdSchedule.defaultSchedule();
    private static final List<DemoBroadcastAdSchedule.HouseLoopAsset> HOUSE_LOOP_ASSETS = BROADCAST_AD_SCHEDULE.houseLoopAssets();
    private static final int DEMO_AD_SLOW_DELAY_MS = 3000;
    private static final String BROADCAST_ROUTE_KIND_FILE = "file";
    private static final String BROADCAST_ROUTE_KIND_RTSP = "rtsp";
    private static final String TRACE_MAP_OK_STATUS = "ok";
    private static final String TRACE_MAP_DEGRADED_STATUS = "degraded";
    private static final Set<String> TRACE_MAP_DEPENDENCY_SERVICES = Set.of(
            "user-service-demo",
            "content-service-demo",
            "billing-service",
            "ad-service-demo"
    );
    private static final Set<String> DEMO_RTSP_HOSTS = Set.of(
            "demo.acmebroadcasting.local",
            "wowzaec2demo.streamlock.net"
    );
    private static final String DEMO_MONKEY_SCOPE =
            "Applies to protected MP4 playback, the public broadcast feed, ad insertion between queued videos, the public trace pivot, and optional browser-error injection so the demo story can move from experience symptom to root cause.";
    private static final List<String> DEMO_MONKEY_AFFECTED_PATHS = List.of(
            "/api/v1/demo/media/movie.mp4",
            "/api/v1/demo/media/library/*",
            "/api/v1/demo/media/rtsp/jobs/*/playback.mp4",
            "/api/v1/demo/public/broadcast/live/*",
            "/api/v1/demo/public/broadcast/live.mp4",
            "/api/v1/demo/public/trace-map",
            "/api/v1/demo/ads/current",
            "/api/v1/demo/ads/issues",
            "/api/v1/demo/ads/program-queue",
            "/",
            "/broadcast",
            "/demo-monkey"
    );

    private final ConcurrentMap<UUID, RtspJob> rtspJobs = new ConcurrentHashMap<>();
    private final AtomicLong jobSequence = new AtomicLong(4100);
    private final AtomicReference<UUID> activeBroadcastJobId = new AtomicReference<>();
    private final AtomicReference<String> stagedBroadcastKey = new AtomicReference<>("");
    private final AtomicReference<String> publishedBroadcastRouteKey = new AtomicReference<>("");
    private final AtomicReference<String> publishedBroadcastRouteDefinition = new AtomicReference<>("");
    private final AtomicReference<Instant> demoAdCycleOrigin = new AtomicReference<>(BROADCAST_AD_SCHEDULE.defaultCycleOrigin());
    private final AtomicReference<DemoMonkeyState> demoMonkeyState = new AtomicReference<>(DemoMonkeyState.disabled());
    private final AtomicLong demoAdTimelineGeneration = new AtomicLong();
    private final Object demoMonkeyStateLock = new Object();
    private final ExecutorService rtspExecutor = Executors.newCachedThreadPool(new RtspWorkerThreadFactory());
    private final ScheduledExecutorService rtspCleanupExecutor = Executors.newSingleThreadScheduledExecutor(new RtspCleanupThreadFactory());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TRACE_MAP_TIMEOUT)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DemoMediaStateRepository stateRepository;

    public DemoMediaController(DemoMediaStateRepository stateRepository) {
        this.stateRepository = stateRepository;
    }

    @Value("${demo.media.file:/opt/demo/demo.mp4}")
    private String demoMediaFile;

    @Value("${demo.media.root:/opt/demo/library}")
    private String demoMediaRoot;

    @Value("${demo.media.broadcast-root:/opt/demo/public-broadcast}")
    private String demoBroadcastRoot;

    @Value("${demo.media.review-root:/opt/demo/public-broadcast/rtsp-review}")
    private String demoReviewRoot;

    @Value("${demo.trace-map.user-service-url:http://user-service-demo.streaming-service-app.svc.cluster.local/api/v1/demo/auth/health}")
    private String traceMapUserServiceUrl;

    @Value("${demo.trace-map.content-service-url:http://content-service-demo.streaming-service-app.svc.cluster.local/api/v1/demo/content}")
    private String traceMapContentServiceUrl;

    @Value("${demo.trace-map.billing-service-url:http://billing-service.streaming-service-app.svc.cluster.local:8070/api/v1/billing/health}")
    private String traceMapBillingServiceUrl;

    @Value("${demo.trace-map.ad-service-url:http://ad-service-demo.streaming-service-app.svc.cluster.local/api/v1/demo/ads/health}")
    private String traceMapAdServiceUrl;

    @Value("${demo.ad-service.current-url:http://ad-service-demo.streaming-service-app.svc.cluster.local/api/v1/demo/ads/current}")
    private String adServiceCurrentUrl;

    @Value("${demo.ad-service.issue-url:http://ad-service-demo.streaming-service-app.svc.cluster.local/api/v1/demo/ads/issues}")
    private String adServiceIssueUrl;

    @Value("${demo.ad-service.timeline-url:http://ad-service-demo.streaming-service-app.svc.cluster.local/api/v1/demo/ads/timeline}")
    private String adServiceTimelineUrl;

    @Value("${demo.content-service.base-url:http://content-service-demo.streaming-service-app.svc.cluster.local}")
    private String contentServiceBaseUrl;

    @PostConstruct
    void initializeBroadcastFeed() {
        restorePersistedState();
        rtspCleanupExecutor.scheduleWithFixedDelay(
                this::cleanupRtspArtifactsSafely,
                RTSP_CLEANUP_INTERVAL.toSeconds(),
                RTSP_CLEANUP_INTERVAL.toSeconds(),
                TimeUnit.SECONDS
        );
        try {
            ensureBroadcastRelayConfigured(resolveBroadcastSelection());
        } catch (IOException ignored) {
        }
    }

    @PreDestroy
    void shutdownRtspWorkers() {
        rtspCleanupExecutor.shutdownNow();
        rtspExecutor.shutdownNow();
        rtspJobs.values().forEach(job -> deleteCaptureQuietly(job.capturePath()));
    }

    private synchronized void restorePersistedState() {
        rtspJobs.clear();
        long highestJobNumber = jobSequence.get();

        for (PersistedRtspJob persistedJob : stateRepository.findAllJobs()) {
            RtspJob restoredJob = RtspJob.restored(
                    persistedJob.jobId(),
                    persistedJob.jobNumber(),
                    persistedJob.contentId(),
                    persistedJob.mediaType(),
                    persistedJob.targetTitle(),
                    persistedJob.ingestSourceUrl(),
                    persistedJob.sourceUrl(),
                    persistedJob.operatorEmail(),
                    persistedJob.captureDurationSeconds(),
                    persistedJob.createdAt(),
                    persistedJob.updatedAt(),
                    persistedJob.status(),
                    persistedJob.playbackUrl(),
                    persistedJob.errorMessage(),
                    persistedJob.capturePath() == null || persistedJob.capturePath().isBlank() ? null : Path.of(persistedJob.capturePath())
            );

            if (!READY_STATUS.equals(restoredJob.status()) && !ERROR_STATUS.equals(restoredJob.status())) {
                restoredJob.markError("The demo media service restarted before the ingest worker completed. Re-run the contribution ingest.");
            } else if (READY_STATUS.equals(restoredJob.status()) && restoredJob.capturePath() != null && !Files.exists(restoredJob.capturePath())) {
                restoredJob.markError("The saved review capture is no longer available. Re-run the contribution ingest.");
            }

            rtspJobs.put(restoredJob.jobId(), restoredJob);
            persistRtspJob(restoredJob);
            highestJobNumber = Math.max(highestJobNumber, restoredJob.jobNumber());
        }

        jobSequence.set(highestJobNumber);
        demoMonkeyState.set(loadDemoMonkeyState());
        activeBroadcastJobId.set(loadActiveBroadcastJobId());
        if (activeBroadcastJobId.get() != null && rtspJobs.get(activeBroadcastJobId.get()) == null) {
            clearActiveBroadcastJob();
        }
    }

    private DemoMonkeyState loadDemoMonkeyState() {
        return stateRepository.loadStateJson(DEMO_MONKEY_STATE_KEY)
                .map(this::readDemoMonkeyState)
                .orElseGet(DemoMonkeyState::disabled);
    }

    private UUID loadActiveBroadcastJobId() {
        return stateRepository.loadStateJson(ACTIVE_BROADCAST_STATE_KEY)
                .map(this::readActiveBroadcastJobId)
                .orElse(null);
    }

    private DemoMonkeyState readDemoMonkeyState(String json) {
        try {
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            return new DemoMonkeyState(
                    Boolean.TRUE.equals(payload.get("enabled")),
                    String.valueOf(payload.getOrDefault("preset", "clear")),
                    intValue(payload.get("startupDelayMs")),
                    intValue(payload.get("throttleKbps")),
                    intValue(payload.get("disconnectAfterKb")),
                    intValue(payload.get("packetLossPercent")),
                    Boolean.TRUE.equals(payload.get("playbackFailureEnabled")),
                    Boolean.TRUE.equals(payload.get("traceMapFailureEnabled")),
                    Boolean.TRUE.equals(payload.get("dependencyTimeoutEnabled")),
                    String.valueOf(payload.getOrDefault("dependencyTimeoutService", "")),
                    Boolean.TRUE.equals(payload.get("dependencyFailureEnabled")),
                    String.valueOf(payload.getOrDefault("dependencyFailureService", "")),
                    Boolean.TRUE.equals(payload.get("frontendExceptionEnabled")),
                    Boolean.TRUE.equals(payload.get("slowAdEnabled")),
                    Boolean.TRUE.equals(payload.get("adLoadFailureEnabled")),
                    Boolean.TRUE.equals(payload.get("nextBreakOnlyEnabled")),
                    instantValue(payload.get("autoClearAt")),
                    instantValue(payload.get("updatedAt"))
            );
        } catch (Exception exception) {
            return DemoMonkeyState.disabled();
        }
    }

    private UUID readActiveBroadcastJobId(String json) {
        try {
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            Object value = payload.get("jobId");
            if (value == null || String.valueOf(value).isBlank()) {
                return null;
            }
            return UUID.fromString(String.valueOf(value));
        } catch (Exception exception) {
            return null;
        }
    }

    private void persistDemoMonkeyState(DemoMonkeyState state) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("enabled", state.enabled());
            payload.put("preset", state.preset());
            payload.put("startupDelayMs", state.startupDelayMs());
            payload.put("throttleKbps", state.throttleKbps());
            payload.put("disconnectAfterKb", state.disconnectAfterKb());
            payload.put("packetLossPercent", state.packetLossPercent());
            payload.put("playbackFailureEnabled", state.playbackFailureEnabled());
            payload.put("traceMapFailureEnabled", state.traceMapFailureEnabled());
            payload.put("dependencyTimeoutEnabled", state.dependencyTimeoutEnabled());
            payload.put("dependencyTimeoutService", state.dependencyTimeoutService());
            payload.put("dependencyFailureEnabled", state.dependencyFailureEnabled());
            payload.put("dependencyFailureService", state.dependencyFailureService());
            payload.put("frontendExceptionEnabled", state.frontendExceptionEnabled());
            payload.put("slowAdEnabled", state.slowAdEnabled());
            payload.put("adLoadFailureEnabled", state.adLoadFailureEnabled());
            payload.put("nextBreakOnlyEnabled", state.nextBreakOnlyEnabled());
            payload.put("autoClearAt", state.autoClearAt() == null ? null : state.autoClearAt().toString());
            payload.put("updatedAt", state.updatedAt() == null ? null : state.updatedAt().toString());
            stateRepository.saveStateJson(DEMO_MONKEY_STATE_KEY, objectMapper.writeValueAsString(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist Demo Monkey state.", exception);
        }
    }

    private void persistActiveBroadcastJobId(UUID jobId) {
        try {
            stateRepository.saveStateJson(
                    ACTIVE_BROADCAST_STATE_KEY,
                    objectMapper.writeValueAsString(Map.of("jobId", jobId == null ? "" : jobId.toString()))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist active broadcast selection.", exception);
        }
    }

    private void persistRtspJob(RtspJob job) {
        stateRepository.saveJob(new PersistedRtspJob(
                job.jobId(),
                job.jobNumber(),
                job.contentId(),
                job.mediaType(),
                job.targetTitle(),
                job.ingestSourceUrl(),
                job.sourceUrl(),
                job.operatorEmail(),
                job.captureDurationSeconds(),
                job.status(),
                job.createdAt(),
                job.updatedAt(),
                job.playbackUrl(),
                READY_STATUS.equals(job.status()) ? MP4_FORMAT : null,
                job.errorMessage(),
                job.capturePath() == null ? null : job.capturePath().toString()
        ));
    }

    private void setActiveBroadcastJob(UUID jobId) {
        activeBroadcastJobId.set(jobId);
        persistActiveBroadcastJobId(jobId);
    }

    private void clearActiveBroadcastJob() {
        activeBroadcastJobId.set(null);
        persistActiveBroadcastJobId(null);
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return Instant.parse(text);
    }

    @GetMapping("/api/v1/demo/media/health")
    public Map<String, String> health() {
        // Keep health checks cheap and side-effect free so probes do not trigger cleanup work.
        long activeJobs = rtspJobs.values().stream()
                .filter(job -> !READY_STATUS.equals(job.status()) && !ERROR_STATUS.equals(job.status()))
                .count();

        return Map.of(
                "status", "ok",
                "activeRtspJobs", Long.toString(activeJobs)
        );
    }

    @GetMapping("/api/v1/demo/media/demo-monkey")
    public DemoMonkeyStatusResponse getDemoMonkeyStatus() {
        return toDemoMonkeyStatusResponse(currentDemoMonkeyState());
    }

    @PutMapping("/api/v1/demo/media/demo-monkey")
    public DemoMonkeyStatusResponse updateDemoMonkey(@RequestBody DemoMonkeyConfigRequest request) {
        DemoMonkeyState updated = normalizeDemoMonkeyRequest(request);
        synchronized (demoMonkeyStateLock) {
            syncAdServiceIssues(updated);
            demoMonkeyState.set(updated);
            persistDemoMonkeyState(updated);
        }
        try {
            ensureBroadcastRelayConfigured(resolveBroadcastSelection());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to refresh the broadcast route after Demo Monkey changes.");
        }
        return toDemoMonkeyStatusResponse(updated);
    }

    @GetMapping("/api/v1/demo/media/rtsp/jobs")
    public List<RtspJobResponse> listRtspJobs() {
        pruneExpiredRtspArtifacts();
        return rtspJobs.values().stream()
                .sorted(Comparator.comparing(RtspJob::createdAt).reversed())
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/api/v1/demo/media/rtsp/jobs/{jobId}")
    public RtspJobResponse getRtspJob(@PathVariable UUID jobId) {
        pruneExpiredRtspArtifacts();
        return toResponse(findJob(jobId));
    }

    @PostMapping("/api/v1/demo/media/rtsp/jobs")
    public RtspJobResponse createRtspJob(@RequestBody RtspJobRequest request) {
        pruneExpiredRtspArtifacts();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }

        if (request.contentId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentId is required.");
        }

        String sourceUrl = normalizeRtspUrl(request.sourceUrl());
        int captureDurationSeconds = normalizeCaptureDuration(request.captureDurationSeconds());

        UUID jobId = UUID.randomUUID();
        RtspJob job = new RtspJob(
                jobId,
                jobSequence.incrementAndGet(),
                request.contentId(),
                request.mediaType() == null || request.mediaType().isBlank() ? "MOVIE" : request.mediaType().trim(),
                request.targetTitle() == null || request.targetTitle().isBlank() ? "Library Asset" : request.targetTitle().trim(),
                sourceUrl,
                redactRtspUrl(sourceUrl),
                request.operatorEmail() == null || request.operatorEmail().isBlank()
                        ? "unknown@acmebroadcasting.com"
                        : request.operatorEmail().trim().toLowerCase(),
                captureDurationSeconds,
                Instant.now()
        );
        rtspJobs.put(jobId, job);
        persistRtspJob(job);
        updateCatalogLifecycleAsync(
                job.contentId(),
                "INGESTING",
                "Contribution feed attached",
                "RTSP contribution feed",
                null,
                null
        );
        rtspExecutor.submit(() -> captureRtspJob(job, sourceUrl));

        return toResponse(job);
    }

    @PostMapping("/api/v1/media/video/rtsp/ingest")
    public ResponseEntity<Map<String, Object>> createRtspIngest(@RequestBody RtspJobRequest request) {
        RtspJobResponse response = createRtspJob(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(realRtspIngestResponse(response));
    }

    @GetMapping("/api/v1/media/video/rtsp/ingest/{videoId}")
    public Map<String, Object> getRtspIngest(@PathVariable UUID videoId) {
        RtspJobResponse response = getRtspJob(videoId);
        return realRtspIngestResponse(response);
    }

    @GetMapping("/api/v1/demo/media/rtsp/jobs/{jobId}/playback.mp4")
    public void getRtspPlayback(
            @PathVariable UUID jobId,
            @RequestHeader HttpHeaders headers,
            HttpServletResponse response
    ) throws IOException {
        pruneExpiredRtspArtifacts();
        RtspJob job = findJob(jobId);
        if (job.capturePath() == null || !READY_STATUS.equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RTSP capture is not ready for playback.");
        }

        serveMediaFile(job.capturePath(), headers, response);
    }

    @GetMapping("/api/v1/demo/public/broadcast/current")
    public BroadcastStatusResponse getPublicBroadcastStatus() {
        BroadcastSelection selection = resolveBroadcastSelection();
        try {
            ensureBroadcastRelayConfigured(selection);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to route the public broadcast feed.");
        }
        return toBroadcastStatus(selection);
    }

    @GetMapping("/api/v1/demo/public/trace-map")
    public ResponseEntity<TraceMapResponse> getPublicTraceMap() {
        DemoMonkeyState currentDemoMonkeyState = currentDemoMonkeyState();
        List<TraceMapDependencyResponse> dependencies = List.of(
                probeDependency("user-service-demo", traceMapUserServiceUrl, currentDemoMonkeyState),
                probeDependency("content-service-demo", traceMapContentServiceUrl, currentDemoMonkeyState),
                probeDependency("billing-service", traceMapBillingServiceUrl, currentDemoMonkeyState),
                probeDependency("ad-service-demo", traceMapAdServiceUrl, currentDemoMonkeyState)
        );

        boolean forcedFailure = currentDemoMonkeyState != null
                && currentDemoMonkeyState.enabled()
                && currentDemoMonkeyState.traceMapFailureEnabled();
        boolean allHealthy = dependencies.stream().allMatch(TraceMapDependencyResponse::healthy);
        TraceMapResponse payload = new TraceMapResponse(
                allHealthy && !forcedFailure ? TRACE_MAP_OK_STATUS : TRACE_MAP_DEGRADED_STATUS,
                "media-service-demo",
                Instant.now().toString(),
                traceMapDetail(currentDemoMonkeyState, forcedFailure, allHealthy),
                dependencies
        );

        return ResponseEntity
                .status(forcedFailure ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
                .body(payload);
    }

    @GetMapping("/api/v1/demo/public/broadcast/live/{assetName:.+}")
    public void getPublicBroadcastPlaylistAsset(
            @PathVariable String assetName,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        BroadcastSelection selection = resolveBroadcastSelection();
        try {
            ensureBroadcastRelayConfigured(selection);
            proxyBroadcastAsset(assetName, request.getQueryString(), response);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to proxy the public broadcast asset.");
        }
    }

    @GetMapping(PUBLIC_BROADCAST_LEGACY_MP4_PATH)
    public void getPublicBroadcastPlayback(
            @RequestHeader HttpHeaders headers,
            HttpServletResponse response
    ) throws IOException {
        BroadcastSelection selection = resolveBroadcastSelection();
        if (selection.mediaPath() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The active public feed is being served as a live HLS stream. Use " + PUBLIC_BROADCAST_PLAYBACK_PATH + "."
            );
        }
        Path broadcastPath = ensureBroadcastMaterialized(selection);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        serveMediaFile(broadcastPath, headers, response);
    }

    @PostMapping("/api/v1/demo/media/broadcast/jobs/{jobId}/activate")
    public BroadcastStatusResponse activateBroadcastJob(@PathVariable UUID jobId) {
        pruneExpiredRtspArtifacts();
        RtspJob job = findJob(jobId);
        if (!isBroadcastActivatable(job)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RTSP capture must be capturing or ready before it can be taken live.");
        }

        setActiveBroadcastJob(jobId);
        updateCatalogLifecycleAsync(
                job.contentId(),
                "ON_AIR",
                "Live contribution on air",
                "RTSP live route",
                BROADCAST_CHANNEL_LABEL,
                "Live contribution"
        );
        BroadcastSelection selection = resolveBroadcastSelection();
        try {
            ensureBroadcastRelayConfigured(selection);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to route the RTSP broadcast feed.");
        }
        return toBroadcastStatus(selection);
    }

    @PostMapping("/api/v1/demo/media/broadcast/reset")
    public BroadcastStatusResponse resetBroadcastJob() {
        pruneExpiredRtspArtifacts();
        UUID previousJobId = activeBroadcastJobId.get();
        RtspJob previousJob = previousJobId == null ? null : rtspJobs.get(previousJobId);

        clearActiveBroadcastJob();
        restoreCatalogLifecycleAfterBroadcastReset(previousJob);
        BroadcastSelection selection = resolveBroadcastSelection();
        try {
            ensureBroadcastRelayConfigured(selection);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to restore the default house loop.");
        }
        return toBroadcastStatus(selection);
    }

    @GetMapping("/api/v1/demo/media/movie.mp4")
    public void getDemoMovie(@RequestHeader HttpHeaders headers, HttpServletResponse response) throws IOException {
        serveMediaFile(Path.of(demoMediaFile), headers, response);
    }

    @GetMapping("/api/v1/demo/media/library/{assetId:.+}")
    public void getLibraryAsset(@PathVariable String assetId, @RequestHeader HttpHeaders headers, HttpServletResponse response) throws IOException {
        serveMediaFile(resolveLibraryAssetPath(assetId), headers, response);
    }

    @GetMapping({"/api/v1/stream/playlist/{videoId}", "/api/v1/stream/playlist/{videoId}.m3u8"})
    public ResponseEntity<Void> getDemoStreamPlaylist(@PathVariable UUID videoId) {
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(resolvePlaybackRedirectUrl(videoId)))
                .build();
    }

    @GetMapping({
            "/api/v1/stream/playlist/{videoId}/{resolution}",
            "/api/v1/stream/playlist/{videoId}/{resolution}.m3u8"
    })
    public ResponseEntity<Void> getDemoStreamVariant(@PathVariable UUID videoId, @PathVariable int resolution) {
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(resolvePlaybackRedirectUrl(videoId)))
                .build();
    }

    private void captureRtspJob(RtspJob job, String sourceUrl) {
        Path reviewPath = null;
        Path tempPath = null;
        try {
            job.markStatus(CONNECTING_STATUS);
            persistRtspJob(job);
            reviewPath = createReviewFile(job.jobId());
            tempPath = Files.createTempFile("rtsp-capture-" + job.jobId() + "-", ".mp4");
            if (isBundledDemoRtspSource(sourceUrl)) {
                captureBundledDemoFeed(tempPath, job.captureDurationSeconds(), job);
            } else {
                captureRtspToFile(sourceUrl, tempPath, job.captureDurationSeconds(), job);
            }
            job.markStatus(TRANSCODING_STATUS);
            persistRtspJob(job);
            moveCaptureFile(tempPath, reviewPath);
            job.markReady(String.format(PLAYBACK_PATH_TEMPLATE, job.jobId()), reviewPath);
            persistRtspJob(job);
            updateCatalogLifecycleAsync(
                    job.contentId(),
                    "QC_REVIEW",
                    "Review capture ready",
                    "Captured MP4 review",
                    null,
                    "Contribution review"
            );
            tempPath = null;
        } catch (Exception exception) {
            deleteCaptureQuietly(tempPath);
            deleteCaptureQuietly(reviewPath);
            job.markError(sanitizeErrorMessage(exception.getMessage()));
            persistRtspJob(job);
            updateCatalogLifecycleAsync(
                    job.contentId(),
                    "INGEST_ERROR",
                    "Contribution ingest failed",
                    "Awaiting operator retry",
                    null,
                    "Contribution recovery"
            );
        }
    }

    private void captureRtspToFile(String rtspUrl, Path outputFile, int captureDurationSeconds, RtspJob job) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(captureDurationSeconds).toNanos();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl)) {
            configureGrabber(grabber);
            grabber.start();

            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("RTSP source did not expose a video stream.");
            }

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    outputFile.toFile(),
                    width,
                    height,
                    Math.max(grabber.getAudioChannels(), 0)
            )) {
                configureRecorder(recorder, grabber);
                recorder.start();

                boolean receivedFrame = false;
                Frame frame;
                while (System.nanoTime() < deadlineNanos && (frame = grabber.grabFrame()) != null) {
                    if (!receivedFrame) {
                        job.markStatus(CAPTURING_STATUS);
                        persistRtspJob(job);
                        receivedFrame = true;
                    }
                    recorder.record(frame);
                }

                recorder.stop();
                if (!receivedFrame) {
                    throw new IllegalStateException("No frames were received from the RTSP source.");
                }
            }

            grabber.stop();
        }
    }

    private void captureBundledDemoFeed(Path outputFile, int captureDurationSeconds, RtspJob job) throws Exception {
        Path bundledFeed = Path.of(demoMediaFile);
        if (!Files.exists(bundledFeed)) {
            throw new IllegalStateException("Bundled RTSP demo feed is unavailable.");
        }

        long maxDurationMicros = Duration.ofSeconds(captureDurationSeconds).toMillis() * 1000L;

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(bundledFeed.toFile())) {
            grabber.start();

            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("Bundled RTSP demo feed did not expose a video stream.");
            }

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    outputFile.toFile(),
                    width,
                    height,
                    Math.max(grabber.getAudioChannels(), 0)
            )) {
                configureRecorder(recorder, grabber);
                recorder.start();

                boolean receivedFrame = false;
                long firstTimestampMicros = -1L;
                Frame frame;
                while ((frame = grabber.grabFrame()) != null) {
                    long currentTimestampMicros = Math.max(grabber.getTimestamp(), 0L);
                    if (firstTimestampMicros < 0L) {
                        firstTimestampMicros = currentTimestampMicros;
                    } else if (currentTimestampMicros - firstTimestampMicros >= maxDurationMicros) {
                        break;
                    }

                    if (!receivedFrame) {
                        job.markStatus(CAPTURING_STATUS);
                        persistRtspJob(job);
                        receivedFrame = true;
                    }
                    recorder.record(frame);
                }

                recorder.stop();
                if (!receivedFrame) {
                    throw new IllegalStateException("Bundled RTSP demo feed did not yield any frames.");
                }
            }

            grabber.stop();
        }
    }

    private void configureGrabber(FFmpegFrameGrabber grabber) {
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("timeout", String.valueOf(RTSP_TIMEOUT_MICROS));
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

    private RtspJob findJob(UUID jobId) {
        RtspJob job = rtspJobs.get(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RTSP job not found.");
        }
        return job;
    }

    private boolean isBroadcastReady(RtspJob job) {
        return job != null
                && READY_STATUS.equals(job.status())
                && job.capturePath() != null
                && Files.exists(job.capturePath());
    }

    private void restoreCatalogLifecycleAfterBroadcastReset(RtspJob job) {
        if (job == null) {
            return;
        }

        String status = String.valueOf(job.status()).toUpperCase(Locale.ROOT);
        switch (status) {
            case READY_STATUS -> updateCatalogLifecycleAsync(
                    job.contentId(),
                    "QC_REVIEW",
                    "Review capture ready",
                    "Captured MP4 review",
                    null,
                    "Contribution review"
            );
            case CAPTURING_STATUS, TRANSCODING_STATUS, CONNECTING_STATUS -> updateCatalogLifecycleAsync(
                    job.contentId(),
                    "INGESTING",
                    "Contribution feed attached",
                    "RTSP contribution feed",
                    null,
                    null
            );
            case ERROR_STATUS -> updateCatalogLifecycleAsync(
                    job.contentId(),
                    "INGEST_ERROR",
                    "Contribution ingest failed",
                    "Awaiting operator retry",
                    null,
                    "Contribution recovery"
            );
            default -> {
                // Leave other catalog states unchanged.
            }
        }
    }

    private boolean isBroadcastActivatable(RtspJob job) {
        if (job == null) {
            return false;
        }

        return CAPTURING_STATUS.equals(job.status())
                || TRANSCODING_STATUS.equals(job.status())
                || isBroadcastReady(job);
    }

    BroadcastSelection resolveBroadcastSelection() {
        UUID activeJobId = activeBroadcastJobId.get();
        if (activeJobId != null) {
            RtspJob activeJob = rtspJobs.get(activeJobId);
            if (activeJob != null) {
                if (CAPTURING_STATUS.equals(activeJob.status()) || TRANSCODING_STATUS.equals(activeJob.status())) {
                    return new BroadcastSelection(
                            activeJob.jobId(),
                            activeJob.targetTitle(),
                            BROADCAST_STATUS_ON_AIR,
                            BROADCAST_SOURCE_TYPE_RTSP,
                            activeJob.updatedAt(),
                            null,
                            null,
                            broadcastRelaySourceFor(activeJob),
                            activeJob.playbackUrl(),
                            "Live RTSP contribution is routed to the external channel while the review capture runs."
                    );
                }

                if (isBroadcastReady(activeJob)) {
                    return new BroadcastSelection(
                            activeJob.jobId(),
                            activeJob.targetTitle(),
                            BROADCAST_STATUS_ON_AIR,
                            BROADCAST_SOURCE_TYPE_RTSP,
                            activeJob.updatedAt(),
                            activeJob.capturePath(),
                            null,
                            null,
                            activeJob.playbackUrl(),
                            "Approved RTSP contribution playback is routed to the external channel."
                    );
                }
            }

            if (activeJob == null || ERROR_STATUS.equals(activeJob.status()) || QUEUED_STATUS.equals(activeJob.status()) || CONNECTING_STATUS.equals(activeJob.status())) {
                if (activeBroadcastJobId.compareAndSet(activeJobId, null)) {
                    persistActiveBroadcastJobId(null);
                }
            } else {
                return new BroadcastSelection(
                        activeJob.jobId(),
                        activeJob.targetTitle(),
                        BROADCAST_STATUS_ON_AIR,
                        BROADCAST_SOURCE_TYPE_RTSP,
                        activeJob.updatedAt(),
                        null,
                        null,
                        broadcastRelaySourceFor(activeJob),
                        activeJob.playbackUrl(),
                        "Live RTSP contribution is being routed to the external channel."
                );
            }
        }

        return new BroadcastSelection(
                null,
                HOUSE_LINEUP_TITLE,
                BROADCAST_STATUS_DEMO_LOOP,
                BROADCAST_SOURCE_TYPE_DEMO,
                houseLoopUpdatedAt(),
                Path.of(demoMediaFile),
                resolveHouseLoopPlaylistPath(),
                null,
                "/api/v1/demo/media/movie.mp4",
                HOUSE_LINEUP_DETAIL
        );
    }

    private synchronized Path ensureBroadcastMaterialized(BroadcastSelection selection) throws IOException {
        if (selection.mediaPath() == null || !Files.exists(selection.mediaPath())) {
            throw new IOException("Broadcast media source is unavailable.");
        }

        Path broadcastRoot = Files.createDirectories(Path.of(demoBroadcastRoot));
        Path broadcastPath = broadcastRoot.resolve(PUBLIC_BROADCAST_FILE_NAME);
        String nextKey = broadcastSelectionKey(selection);
        if (nextKey.equals(stagedBroadcastKey.get()) && Files.exists(broadcastPath)) {
            return broadcastPath;
        }

        Path tempPath = Files.createTempFile(broadcastRoot, "broadcast-", ".mp4");
        try {
            Files.copy(selection.mediaPath(), tempPath, StandardCopyOption.REPLACE_EXISTING);
            moveCaptureFile(tempPath, broadcastPath);
            stagedBroadcastKey.set(nextKey);
            return broadcastPath;
        } catch (IOException exception) {
            deleteCaptureQuietly(tempPath);
            throw exception;
        }
    }

    synchronized void ensureBroadcastRelayConfigured(BroadcastSelection selection) throws IOException {
        Path broadcastRoot = Files.createDirectories(Path.of(demoBroadcastRoot));
        Path routePath = broadcastRoot.resolve(PUBLIC_BROADCAST_ROUTE_FILE_NAME);
        String nextKey = broadcastSelectionKey(selection);
        BroadcastAdInsertionPlan adInsertionPlan = resolveAdInsertionPlan(selection);
        Instant routeRequestedAt = Instant.now();
        String relayKind;
        String relaySource;
        String relayLoop;
        if (selection.playlistPath() != null) {
            relayKind = BROADCAST_ROUTE_KIND_PLAYLIST;
            relaySource = ensureHouseLoopPlaylistMaterialized().toString();
            relayLoop = "true";
        } else if (selection.mediaPath() != null) {
            relayKind = BROADCAST_ROUTE_KIND_FILE;
            relaySource = ensureBroadcastMaterialized(selection).toString();
            relayLoop = "true";
        } else if (selection.liveSourceUrl() != null && !selection.liveSourceUrl().isBlank()) {
            relayKind = BROADCAST_ROUTE_KIND_RTSP;
            relaySource = selection.liveSourceUrl();
            relayLoop = "false";
        } else {
            throw new IOException("Broadcast relay source is unavailable.");
        }

        String routeDefinition = String.join("\n", List.of(
                nextKey,
                relayKind,
                relayLoop,
                relaySource,
                Boolean.toString(adInsertionPlan.insertAd()),
                Boolean.toString(adInsertionPlan.slowAd()),
                Boolean.toString(adInsertionPlan.adLoadFailure()),
                adInsertionPlan.adAssetPath(),
                adInsertionPlan.stallAssetPath()
        )) + "\n";
        if (routeDefinition.equals(publishedBroadcastRouteDefinition.get()) && Files.exists(routePath)) {
            return;
        }

        Path tempPath = Files.createTempFile(broadcastRoot, "broadcast-route-", ".txt");
        try {
            Files.writeString(tempPath, routeDefinition, StandardCharsets.UTF_8);
            moveCaptureFile(tempPath, routePath);
            publishedBroadcastRouteKey.set(nextKey);
            publishedBroadcastRouteDefinition.set(routeDefinition);
            if ((BROADCAST_ROUTE_KIND_FILE.equals(relayKind) || BROADCAST_ROUTE_KIND_PLAYLIST.equals(relayKind))
                    && "true".equals(relayLoop)
                    && adInsertionPlan.insertAd()) {
                synchronizeAdTimelineAsync(nextKey, routeRequestedAt);
            }
        } catch (IOException exception) {
            deleteCaptureQuietly(tempPath);
            throw exception;
        }
    }

    private void synchronizeAdTimelineAsync(String routeKey, Instant routeRequestedAt) {
        demoAdCycleOrigin.set(routeRequestedAt);
        long generation = demoAdTimelineGeneration.incrementAndGet();
        rtspExecutor.submit(() -> awaitAndSyncAdTimeline(routeKey, generation, routeRequestedAt));
    }

    private void awaitAndSyncAdTimeline(String routeKey, long generation, Instant fallbackOrigin) {
        Instant resolvedOrigin = fallbackOrigin;
        for (int attempt = 0; attempt < 30; attempt++) {
            if (isStaleAdTimelineSync(routeKey, generation)) {
                return;
            }
            if (publicBroadcastLiveAvailable()) {
                resolvedOrigin = Instant.now();
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (isStaleAdTimelineSync(routeKey, generation)) {
            return;
        }

        demoAdCycleOrigin.set(resolvedOrigin);
        try {
            syncAdServiceTimeline(resolvedOrigin);
        } catch (Exception exception) {
            System.err.println("Unable to synchronize ad timeline: " + exception.getMessage());
        }
    }

    private boolean isStaleAdTimelineSync(String routeKey, long generation) {
        return generation != demoAdTimelineGeneration.get() || !routeKey.equals(publishedBroadcastRouteKey.get());
    }

    private boolean publicBroadcastLiveAvailable() {
        return publicBroadcastLiveAvailable(PUBLIC_BROADCAST_PROXY_TIMEOUT);
    }

    private boolean publicBroadcastLiveAvailable(Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(PUBLIC_BROADCAST_HLS_BASE_URL + "index.m3u8"))
                .GET()
                .timeout(timeout)
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200
                    && response.statusCode() < 300
                    && response.body() != null
                    && response.body().contains("#EXTM3U");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cleanupRtspArtifactsSafely() {
        try {
            pruneExpiredRtspArtifacts();
        } catch (Exception exception) {
            System.err.println("Unable to prune expired RTSP demo artifacts: " + exception.getMessage());
        }
    }

    private synchronized void pruneExpiredRtspArtifacts() {
        Instant now = Instant.now();
        Set<Path> retainedCapturePaths = new HashSet<>();

        rtspJobs.forEach((jobId, job) -> {
            if (isRtspJobPinnedForBroadcast(job)) {
                addCapturePath(retainedCapturePaths, job.capturePath());
                return;
            }

            if (isRtspJobExpired(job, now)) {
                deleteCaptureQuietly(job.capturePath());
                if (activeBroadcastJobId.compareAndSet(jobId, null)) {
                    persistActiveBroadcastJobId(null);
                }
                if (job.capturePath() != null || !ERROR_STATUS.equals(job.status())) {
                    job.markError("Review capture archived after retention window.");
                    persistRtspJob(job);
                }
                return;
            }

            addCapturePath(retainedCapturePaths, job.capturePath());
        });

        deleteOrphanedReviewFiles(now, retainedCapturePaths);
    }

    private boolean isRtspJobPinnedForBroadcast(RtspJob job) {
        UUID activeJobId = activeBroadcastJobId.get();
        if (activeJobId == null || !activeJobId.equals(job.jobId())) {
            return false;
        }

        if (CAPTURING_STATUS.equals(job.status()) || TRANSCODING_STATUS.equals(job.status()) || isBroadcastReady(job)) {
            return true;
        }

        if (activeBroadcastJobId.compareAndSet(activeJobId, null)) {
            persistActiveBroadcastJobId(null);
        }
        return false;
    }

    private boolean isRtspJobExpired(RtspJob job, Instant now) {
        Instant expiry = switch (job.status()) {
            case READY_STATUS, ERROR_STATUS -> job.updatedAt().plus(RTSP_TERMINAL_JOB_RETENTION);
            default -> job.createdAt()
                    .plusSeconds(job.captureDurationSeconds())
                    .plus(RTSP_IN_PROGRESS_GRACE_PERIOD);
        };
        return !now.isBefore(expiry);
    }

    private void addCapturePath(Set<Path> retainedCapturePaths, Path capturePath) {
        if (capturePath == null) {
            return;
        }
        retainedCapturePaths.add(capturePath.toAbsolutePath().normalize());
    }

    private void deleteOrphanedReviewFiles(Instant now, Set<Path> retainedCapturePaths) {
        Path reviewRoot = reviewRootPath();
        if (!Files.isDirectory(reviewRoot)) {
            return;
        }

        Instant cutoff = now.minus(RTSP_TERMINAL_JOB_RETENTION);
        try (Stream<Path> reviewFiles = Files.list(reviewRoot)) {
            reviewFiles
                    .filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !retainedCapturePaths.contains(path))
                    .filter(path -> !fileUpdatedAt(path).isAfter(cutoff))
                    .forEach(this::deleteCaptureQuietly);
        } catch (IOException ignored) {
        }
    }

    private String broadcastSelectionKey(BroadcastSelection selection) {
        String selectionId = selection.jobId() == null ? "demo-loop" : selection.jobId().toString();
        return selectionId + ":" + selection.updatedAt().toEpochMilli();
    }

    private BroadcastAdInsertionPlan resolveAdInsertionPlan(BroadcastSelection selection) {
        Path adAsset = resolveLibraryAssetPath("sponsor-break.mp4");
        Path stallAsset = resolveLibraryAssetPath("sponsor-stall.mp4");
        if ((selection.mediaPath() == null && selection.playlistPath() == null) || !Files.exists(adAsset)) {
            return new BroadcastAdInsertionPlan(false, false, false, "", "");
        }

        try {
            AdServiceCurrentResponse current = fetchAdServiceCurrent();
            return new BroadcastAdInsertionPlan(
                    current.insertAd(),
                    current.slowAdEnabled(),
                    current.adLoadFailureEnabled(),
                    adAsset.toString(),
                    Files.exists(stallAsset) ? stallAsset.toString() : ""
            );
        } catch (Exception exception) {
            DemoMonkeyState currentDemoMonkeyState = currentDemoMonkeyState();
            boolean slowAd = currentDemoMonkeyState != null && currentDemoMonkeyState.enabled() && currentDemoMonkeyState.slowAdEnabled();
            boolean adLoadFailure = currentDemoMonkeyState != null && currentDemoMonkeyState.enabled() && currentDemoMonkeyState.adLoadFailureEnabled();
            return new BroadcastAdInsertionPlan(
                    true,
                    slowAd,
                    adLoadFailure,
                    adAsset.toString(),
                    Files.exists(stallAsset) ? stallAsset.toString() : ""
            );
        }
    }

    private String broadcastRelaySourceFor(RtspJob job) {
        if (job == null) {
            return "";
        }

        if (isBundledDemoRtspSource(job.ingestSourceUrl())) {
            return BUNDLED_DEMO_RTSP_SOURCE_URL;
        }

        return job.ingestSourceUrl();
    }

    private void proxyBroadcastAsset(String assetName, String queryString, HttpServletResponse response) throws IOException {
        DemoMonkeyExecutionPlan demoMonkeyPlan = DemoMonkeyExecutionPlan.from(currentDemoMonkeyState());
        pauseForDemoMonkey(demoMonkeyPlan.startupDelayMs());
        if (demoMonkeyPlan.playbackFailureEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demo Monkey forced playback failure.");
        }

        String normalizedAsset = assetName == null || assetName.isBlank() ? "index.m3u8" : assetName;
        boolean playlistRequest = normalizedAsset.endsWith(".m3u8");
        String uri = PUBLIC_BROADCAST_HLS_BASE_URL + normalizedAsset;
        if (queryString != null && !queryString.isBlank()) {
            uri += "?" + queryString;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .GET()
                .timeout(PUBLIC_BROADCAST_PROXY_TIMEOUT)
                .build();

        try {
            HttpResponse<byte[]> upstream = sendPublicBroadcastProxyRequest(request, playlistRequest);
            response.setStatus(upstream.statusCode());
            upstream.headers().firstValue(HttpHeaders.CONTENT_TYPE).ifPresent(response::setContentType);
            upstream.headers().firstValue(HttpHeaders.CACHE_CONTROL).ifPresent(value -> response.setHeader(HttpHeaders.CACHE_CONTROL, value));
            upstream.headers().firstValue("Access-Control-Allow-Origin").ifPresent(value -> response.setHeader("Access-Control-Allow-Origin", value));
            upstream.headers().firstValue("Access-Control-Allow-Credentials").ifPresent(value -> response.setHeader("Access-Control-Allow-Credentials", value));
            response.setHeader("X-Demo-Monkey-State", demoMonkeyPlan.active() ? "active" : "idle");
            response.setHeader("X-Demo-Monkey-Preset", demoMonkeyPlan.preset());
            response.setContentLength(upstream.body().length);
            try (InputStream inputStream = new java.io.ByteArrayInputStream(upstream.body())) {
                transferWithDemoMonkey(inputStream, response, upstream.body().length, demoMonkeyPlan);
            }
        } catch (IOException exception) {
            if (playlistRequest) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Public broadcast playlist is still warming up.", exception);
            }
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Public broadcast proxy was interrupted.", exception);
        }
    }

    private HttpResponse<byte[]> sendPublicBroadcastProxyRequest(HttpRequest request, boolean playlistRequest) throws IOException, InterruptedException {
        IOException lastFailure = null;
        Instant retryDeadline = playlistRequest ? Instant.now().plus(PUBLIC_BROADCAST_PLAYLIST_WARMUP_TIMEOUT) : null;
        int attempt = 0;

        while (true) {
            attempt += 1;
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException exception) {
                lastFailure = exception;
                if (!shouldRetryPublicBroadcastProxyRequest(playlistRequest, attempt, retryDeadline)) {
                    break;
                }

                try {
                    Thread.sleep((playlistRequest ? PUBLIC_BROADCAST_PLAYLIST_WARMUP_POLL_INTERVAL : PUBLIC_BROADCAST_PROXY_RETRY_DELAY).toMillis());
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw interruptedException;
                }
            }
        }

        throw lastFailure == null ? new IOException("Unable to proxy the public broadcast asset.") : lastFailure;
    }

    private boolean shouldRetryPublicBroadcastProxyRequest(boolean playlistRequest, int attempt, Instant retryDeadline) {
        if (playlistRequest) {
            return retryDeadline != null && Instant.now().isBefore(retryDeadline);
        }
        return attempt < PUBLIC_BROADCAST_PROXY_RETRIES;
    }

    private Instant fileUpdatedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private String normalizeRtspUrl(String sourceUrl) {
        String normalized = sourceUrl == null ? "" : sourceUrl.trim();
        if (!normalized.toLowerCase().startsWith(RTSP_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RTSP source must begin with rtsp://");
        }
        return normalized;
    }

    private boolean isBundledDemoRtspSource(String sourceUrl) {
        try {
            URI uri = new URI(sourceUrl);
            String host = uri.getHost();
            return host != null && DEMO_RTSP_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private int normalizeCaptureDuration(Integer captureDurationSeconds) {
        int normalized = captureDurationSeconds == null ? 300 : captureDurationSeconds;
        if (normalized < 30 || normalized > 86_400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Capture duration must be between 30 and 86400 seconds.");
        }
        return normalized;
    }

    private Path createReviewFile(UUID jobId) throws IOException {
        Path reviewRoot = Files.createDirectories(reviewRootPath());
        return reviewRoot.resolve(jobId + ".mp4");
    }

    private Path reviewRootPath() {
        return Path.of(demoReviewRoot);
    }

    private Path resolveLibraryAssetPath(String assetId) {
        Path libraryRoot = Path.of(demoMediaRoot).toAbsolutePath().normalize();
        Path resolved = libraryRoot.resolve(assetId).normalize();
        if (!resolved.startsWith(libraryRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media asset path.");
        }
        return resolved;
    }

    private void moveCaptureFile(Path tempPath, Path reviewPath) throws IOException {
        try {
            Files.move(tempPath, reviewPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempPath, reviewPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void serveMediaFile(Path mediaPath, HttpHeaders headers, HttpServletResponse response) throws IOException {
        if (mediaPath == null || !Files.exists(mediaPath)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long contentLength = Files.size(mediaPath);
        List<HttpRange> ranges = headers.getRange();
        String fileName = mediaPath.getFileName().toString();
        DemoMonkeyExecutionPlan demoMonkeyPlan = DemoMonkeyExecutionPlan.from(currentDemoMonkeyState());

        pauseForDemoMonkey(demoMonkeyPlan.startupDelayMs());
        if (demoMonkeyPlan.playbackFailureEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demo Monkey forced playback failure.");
        }

        response.setContentType(contentTypeFor(fileName));
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"");
        response.setHeader("X-Demo-Monkey-State", demoMonkeyPlan.active() ? "active" : "idle");
        response.setHeader("X-Demo-Monkey-Preset", demoMonkeyPlan.preset());

        if (ranges.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(contentLength);
            try (InputStream inputStream = Files.newInputStream(mediaPath)) {
                transferWithDemoMonkey(inputStream, response, contentLength, demoMonkeyPlan);
            }
            return;
        }

        HttpRange range = ranges.getFirst();
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long rangeLength = end - start + 1;
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + contentLength);
        response.setContentLengthLong(rangeLength);

        try (InputStream inputStream = Files.newInputStream(mediaPath)) {
            inputStream.skipNBytes(start);
            transferWithDemoMonkey(inputStream, response, rangeLength, demoMonkeyPlan);
        }
    }

    private void transferWithDemoMonkey(
            InputStream inputStream,
            HttpServletResponse response,
            long contentLength,
            DemoMonkeyExecutionPlan plan
    ) throws IOException {
        long remaining = contentLength;
        long delivered = 0;
        long packetLossTriggerByte = plan.packetLossTriggerByte(contentLength);
        byte[] buffer = new byte[(int) Math.min(STREAM_BUFFER_SIZE, Math.max(8 * 1024L, Math.min(contentLength, STREAM_BUFFER_SIZE)))];

        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            int read = inputStream.read(buffer, 0, requested);
            if (read < 0) {
                break;
            }

            int toWrite = read;
            if (plan.disconnectAfterBytes() > 0) {
                long bytesUntilDisconnect = plan.disconnectAfterBytes() - delivered;
                if (bytesUntilDisconnect <= 0) {
                    closeConnectionEarly(response);
                    return;
                }
                toWrite = (int) Math.min(read, bytesUntilDisconnect);
            }
            if (packetLossTriggerByte > 0) {
                long bytesUntilPacketLoss = packetLossTriggerByte - delivered;
                if (bytesUntilPacketLoss <= 0) {
                    closeConnectionEarly(response);
                    return;
                }
                toWrite = (int) Math.min(toWrite, bytesUntilPacketLoss);
            }
            if (toWrite <= 0) {
                closeConnectionEarly(response);
                return;
            }

            response.getOutputStream().write(buffer, 0, toWrite);
            response.flushBuffer();
            delivered += toWrite;
            remaining -= toWrite;

            if (plan.disconnectAfterBytes() > 0 && delivered >= plan.disconnectAfterBytes()) {
                closeConnectionEarly(response);
                return;
            }
            if (packetLossTriggerByte > 0 && delivered >= packetLossTriggerByte) {
                closeConnectionEarly(response);
                return;
            }

            pauseForDemoMonkey(plan.delayForBytes(toWrite));
        }
    }

    private void closeConnectionEarly(HttpServletResponse response) throws IOException {
        response.flushBuffer();
        response.getOutputStream().close();
    }

    private void pauseForDemoMonkey(long delayMs) throws IOException {
        if (delayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Demo Monkey fault injection was interrupted.", exception);
        }
    }

    private void deleteCaptureQuietly(Path capturePath) {
        if (capturePath == null) {
            return;
        }

        try {
            Files.deleteIfExists(capturePath);
        } catch (IOException ignored) {
        }
    }

    private String contentTypeFor(String fileName) {
        String normalized = fileName.toLowerCase();
        if (normalized.endsWith(".webm")) {
            return "video/webm";
        }
        if (normalized.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }

    private Instant houseLoopUpdatedAt() {
        return HOUSE_LOOP_ASSETS.stream()
                .map(asset -> fileUpdatedAt(resolveLibraryAssetPath(asset.fileName())))
                .max(Comparator.naturalOrder())
                .orElseGet(() -> fileUpdatedAt(Path.of(demoMediaFile)));
    }

    private Path resolveHouseLoopPlaylistPath() {
        return Path.of(demoBroadcastRoot).resolve(HOUSE_LOOP_PLAYLIST_FILE_NAME);
    }

    private synchronized Path ensureHouseLoopPlaylistMaterialized() throws IOException {
        Path broadcastRoot = Files.createDirectories(Path.of(demoBroadcastRoot));
        Path playlistPath = broadcastRoot.resolve(HOUSE_LOOP_PLAYLIST_FILE_NAME);
        String playlistDefinition = resolveHouseLoopPlaylistSources().stream()
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        if (Files.exists(playlistPath) && playlistDefinition.equals(Files.readString(playlistPath, StandardCharsets.UTF_8))) {
            return playlistPath;
        }

        Path tempPath = Files.createTempFile(broadcastRoot, "house-loop-", ".txt");
        try {
            Files.writeString(tempPath, playlistDefinition, StandardCharsets.UTF_8);
            moveCaptureFile(tempPath, playlistPath);
            return playlistPath;
        } catch (IOException exception) {
            deleteCaptureQuietly(tempPath);
            throw exception;
        }
    }

    private List<Path> resolveHouseLoopPlaylistSources() {
        List<Path> segmentRoots = new ArrayList<>();
        Path libraryRoot = Path.of(demoMediaRoot);
        segmentRoots.add(libraryRoot.resolve(HOUSE_LOOP_SEGMENT_ROOT));
        Path mediaRootParent = libraryRoot.getParent();
        if (mediaRootParent != null) {
            segmentRoots.add(mediaRootParent.resolve(HOUSE_LOOP_SEGMENT_ROOT));
        }

        for (Path segmentRoot : segmentRoots) {
            if (!Files.isDirectory(segmentRoot)) {
                continue;
            }

            try (Stream<Path> segmentFiles = Files.list(segmentRoot)) {
                List<Path> segments = segmentFiles
                        .filter(Files::isRegularFile)
                        .filter(path -> String.valueOf(path.getFileName()).endsWith(".mp4"))
                        .sorted()
                        .toList();
                if (!segments.isEmpty()) {
                    return segments;
                }
            }
            catch (IOException ignored) {
            }
        }

        return HOUSE_LOOP_ASSETS.stream()
                .map(asset -> resolveLibraryAssetPath(asset.fileName()))
                .toList();
    }

    private Map<String, Object> realRtspIngestResponse(RtspJobResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("videoId", response.jobId());
        payload.put("status", response.status());
        payload.put("playbackUrl", response.playbackUrl());
        payload.put("updatedAt", response.updatedAt());
        payload.put("errorMessage", response.errorMessage());
        return payload;
    }

    private RtspJobResponse toResponse(RtspJob job) {
        return new RtspJobResponse(
                job.jobId(),
                job.jobNumber(),
                job.contentId(),
                job.mediaType(),
                job.targetTitle(),
                job.operatorEmail(),
                job.sourceUrl(),
                job.captureDurationSeconds(),
                job.status(),
                job.createdAt().toString(),
                job.updatedAt().toString(),
                READY_STATUS.equals(job.status()) ? job.playbackUrl() : null,
                READY_STATUS.equals(job.status()) ? MP4_FORMAT : null,
                job.errorMessage()
        );
    }

    private String resolvePlaybackRedirectUrl(UUID contentId) {
        return rtspJobs.values().stream()
                .filter(job -> contentId.equals(job.contentId()))
                .filter(job -> READY_STATUS.equals(job.status()) && job.playbackUrl() != null && !job.playbackUrl().isBlank())
                .sorted(Comparator.comparing(RtspJob::updatedAt).reversed())
                .map(RtspJob::playbackUrl)
                .findFirst()
                .orElseGet(() -> fetchCatalogPlaybackUrl(contentId));
    }

    private String fetchCatalogPlaybackUrl(UUID contentId) {
        String url = contentServiceBaseUrl + "/api/v1/demo/content/" + contentId;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(TRACE_MAP_TIMEOUT)
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<?, ?> payload = objectMapper.readValue(response.body(), Map.class);
                Object watchUrl = payload.get("watchUrl");
                if (watchUrl != null && !String.valueOf(watchUrl).isBlank()) {
                    return String.valueOf(watchUrl);
                }
            }
        } catch (Exception ignored) {
        }
        return "/api/v1/demo/media/movie.mp4";
    }

    private void updateCatalogLifecycleAsync(
            UUID contentId,
            String lifecycleState,
            String readinessLabel,
            String signalProfile,
            String channelLabel,
            String programmingTrack
    ) {
        if (contentId == null) {
            return;
        }

        rtspExecutor.submit(() -> updateCatalogLifecycle(contentId, lifecycleState, readinessLabel, signalProfile, channelLabel, programmingTrack));
    }

    private void updateCatalogLifecycle(
            UUID contentId,
            String lifecycleState,
            String readinessLabel,
            String signalProfile,
            String channelLabel,
            String programmingTrack
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lifecycleState", lifecycleState);
        payload.put("readinessLabel", readinessLabel);
        payload.put("signalProfile", signalProfile);
        payload.put("channelLabel", channelLabel);
        payload.put("programmingTrack", programmingTrack);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(contentServiceBaseUrl + "/api/v1/demo/content/" + contentId + "/lifecycle"))
                    .timeout(TRACE_MAP_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    private String redactRtspUrl(String sourceUrl) {
        return sourceUrl.replaceAll("(?i)(?<=rtsp://)[^/@\\s]+@", "***@");
    }

    private String sanitizeErrorMessage(String message) {
        String normalized = message == null || message.isBlank()
                ? "Unable to capture the RTSP source."
                : message.trim();
        return redactRtspUrl(normalized);
    }

    private String summarizeExternalError(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.trim();
    }

    private BroadcastStatusResponse toBroadcastStatus(BroadcastSelection selection) {
        return new BroadcastStatusResponse(
                BROADCAST_CHANNEL_ID,
                BROADCAST_CHANNEL_LABEL,
                selection.status(),
                selection.title(),
                selection.sourceType(),
                selection.updatedAt().toString(),
                selection.jobId(),
                PUBLIC_BROADCAST_PLAYBACK_PATH + "?v=" + selection.updatedAt().toEpochMilli(),
                selection.operatorPlaybackUrl(),
                PUBLIC_BROADCAST_PAGE_PATH,
                selection.detail(),
                resolveAdStatus(selection)
        );
    }

    BroadcastAdStatusResponse resolveAdStatus(BroadcastSelection selection) {
        try {
            return fetchAdServiceStatus(selection);
        } catch (Exception exception) {
            return fallbackAdStatus(selection, summarizeExternalError(exception.getMessage(), "Ad service did not respond."));
        }
    }

    private BroadcastAdStatusResponse fetchAdServiceStatus(BroadcastSelection selection) throws IOException, InterruptedException {
        AdServiceCurrentResponse payload = fetchAdServiceCurrent();
        if (BROADCAST_STATUS_DEMO_LOOP.equals(selection.status())) {
            return synchronizedDemoLoopAdStatus(selection, payload, null);
        }
        return new BroadcastAdStatusResponse(
                payload.state(),
                payload.podLabel(),
                payload.sponsorLabel(),
                payload.decisioningMode(),
                payload.breakStartAt(),
                payload.breakEndAt(),
                payload.issueSummary() == null || payload.issueSummary().isBlank()
                        ? payload.detail()
                        : payload.detail() + " " + payload.issueSummary()
        );
    }

    private AdServiceCurrentResponse fetchAdServiceCurrent() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(adServiceCurrentUrl))
                .GET()
                .timeout(PUBLIC_BROADCAST_PROXY_TIMEOUT)
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessfulHttpStatus(response.statusCode())) {
            throw new IOException("Ad service returned HTTP " + response.statusCode());
        }

        return objectMapper.readValue(response.body(), AdServiceCurrentResponse.class);
    }

    private void syncAdServiceTimeline(Instant cycleOriginAt) throws IOException, InterruptedException {
        AdServiceTimelineRequest request = new AdServiceTimelineRequest(cycleOriginAt.toString());
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(adServiceTimelineUrl))
                .timeout(PUBLIC_BROADCAST_PROXY_TIMEOUT)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (!isSuccessfulHttpStatus(response.statusCode())) {
            throw new IOException("Ad timeline service returned HTTP " + response.statusCode());
        }
    }

    private void syncAdServiceIssues(DemoMonkeyState state) {
        AdServiceIssueRequest request = new AdServiceIssueRequest(
                state.enabled() && (state.slowAdEnabled() || state.adLoadFailureEnabled()),
                state.preset(),
                state.enabled() && state.slowAdEnabled() ? DEMO_AD_SLOW_DELAY_MS : 0,
                state.enabled() && state.adLoadFailureEnabled()
        );

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(adServiceIssueUrl))
                    .timeout(PUBLIC_BROADCAST_PROXY_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (!isSuccessfulHttpStatus(response.statusCode())) {
                throw new IOException("Ad service returned HTTP " + response.statusCode());
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to synchronize Demo Monkey ad issues with the ad service.");
        }
    }

    private boolean isSuccessfulHttpStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private BroadcastAdStatusResponse fallbackAdStatus(BroadcastSelection selection, String errorDetail) {
        if (BROADCAST_STATUS_DEMO_LOOP.equals(selection.status())) {
            return synchronizedDemoLoopAdStatus(selection, null, errorDetail);
        }

        return BROADCAST_AD_SCHEDULE.fallbackStatus(
                BROADCAST_STATUS_ON_AIR.equals(selection.status()),
                errorDetail,
                demoAdCycleOrigin.get(),
                ZonedDateTime.now()
        );
    }

    private BroadcastAdStatusResponse synchronizedDemoLoopAdStatus(
            BroadcastSelection selection,
            AdServiceCurrentResponse payload,
            String errorDetail
    ) {
        DemoBroadcastAdSchedule.CurrentAdPayload currentPayload = payload == null
                ? null
                : new DemoBroadcastAdSchedule.CurrentAdPayload(
                        payload.sponsorLabel(),
                        payload.decisioningMode(),
                        payload.issueSummary(),
                        payload.slowAdEnabled(),
                        payload.adLoadFailureEnabled()
                );
        return BROADCAST_AD_SCHEDULE.synchronizedDemoLoopStatus(
                currentPayload,
                errorDetail,
                demoAdCycleOrigin.get(),
                ZonedDateTime.now()
        );
    }

    private DemoMonkeyState currentDemoMonkeyState() {
        DemoMonkeyState current = demoMonkeyState.get();
        if (current == null) {
            return DemoMonkeyState.disabled();
        }

        if (!shouldAutoClearDemoMonkey(current)) {
            return current;
        }

        synchronized (demoMonkeyStateLock) {
            current = demoMonkeyState.get();
            if (current == null) {
                return DemoMonkeyState.disabled();
            }

            if (!shouldAutoClearDemoMonkey(current)) {
                return current;
            }

            DemoMonkeyState cleared = DemoMonkeyState.disabledAt(Instant.now());
            try {
                syncAdServiceIssues(cleared);
                demoMonkeyState.set(cleared);
                persistDemoMonkeyState(cleared);
                return cleared;
            } catch (ResponseStatusException exception) {
                return current;
            }
        }
    }

    private boolean shouldAutoClearDemoMonkey(DemoMonkeyState state) {
        return state != null
                && state.enabled()
                && state.nextBreakOnlyEnabled()
                && state.autoClearAt() != null
                && !Instant.now().isBefore(state.autoClearAt());
    }

    private Instant resolveDemoMonkeyAutoClearAt(boolean enabled, boolean nextBreakOnlyEnabled) {
        if (!enabled || !nextBreakOnlyEnabled) {
            return null;
        }

        try {
            AdServiceCurrentResponse current = fetchAdServiceCurrent();
            if (current != null && current.breakEndAt() != null && !current.breakEndAt().isBlank()) {
                return Instant.parse(current.breakEndAt());
            }
        } catch (Exception ignored) {
        }

        return BROADCAST_AD_SCHEDULE.currentOrNextBreakEnd(demoAdCycleOrigin.get(), ZonedDateTime.now());
    }

    private DemoMonkeyState normalizeDemoMonkeyRequest(DemoMonkeyConfigRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }

        boolean enabled = Boolean.TRUE.equals(request.enabled());
        int startupDelayMs = normalizeDemoMonkeyValue(request.startupDelayMs(), 0, MAX_STARTUP_DELAY_MS, "startupDelayMs");
        int throttleKbps = normalizeDemoMonkeyValue(request.throttleKbps(), 0, MAX_THROTTLE_KBPS, "throttleKbps");
        int disconnectAfterKb = normalizeDemoMonkeyValue(request.disconnectAfterKb(), 0, MAX_DISCONNECT_AFTER_KB, "disconnectAfterKb");
        int packetLossPercent = normalizeDemoMonkeyValue(request.packetLossPercent(), 0, MAX_PACKET_LOSS_PERCENT, "packetLossPercent");
        boolean playbackFailureEnabled = Boolean.TRUE.equals(request.playbackFailureEnabled());
        boolean traceMapFailureEnabled = Boolean.TRUE.equals(request.traceMapFailureEnabled());
        boolean dependencyTimeoutEnabled = Boolean.TRUE.equals(request.dependencyTimeoutEnabled());
        String dependencyTimeoutService = normalizeDemoMonkeyDependencyService(
                request.dependencyTimeoutService(),
                enabled,
                dependencyTimeoutEnabled,
                "dependencyTimeoutService"
        );
        boolean dependencyFailureEnabled = Boolean.TRUE.equals(request.dependencyFailureEnabled());
        String dependencyFailureService = normalizeDemoMonkeyDependencyService(
                request.dependencyFailureService(),
                enabled,
                dependencyFailureEnabled,
                "dependencyFailureService"
        );
        boolean frontendExceptionEnabled = Boolean.TRUE.equals(request.frontendExceptionEnabled());
        boolean slowAdEnabled = Boolean.TRUE.equals(request.slowAdEnabled());
        boolean adLoadFailureEnabled = Boolean.TRUE.equals(request.adLoadFailureEnabled());
        boolean nextBreakOnlyEnabled = enabled && Boolean.TRUE.equals(request.nextBreakOnlyEnabled());

        if (!enabled) {
            startupDelayMs = 0;
            throttleKbps = 0;
            disconnectAfterKb = 0;
            packetLossPercent = 0;
            playbackFailureEnabled = false;
            traceMapFailureEnabled = false;
            dependencyTimeoutEnabled = false;
            dependencyTimeoutService = "";
            dependencyFailureEnabled = false;
            dependencyFailureService = "";
            frontendExceptionEnabled = false;
            slowAdEnabled = false;
            adLoadFailureEnabled = false;
            nextBreakOnlyEnabled = false;
        }

        Instant autoClearAt = resolveDemoMonkeyAutoClearAt(enabled, nextBreakOnlyEnabled);

        return new DemoMonkeyState(
                enabled,
                normalizeDemoMonkeyPreset(request.preset(), enabled),
                startupDelayMs,
                throttleKbps,
                disconnectAfterKb,
                packetLossPercent,
                playbackFailureEnabled,
                traceMapFailureEnabled,
                dependencyTimeoutEnabled,
                dependencyTimeoutService,
                dependencyFailureEnabled,
                dependencyFailureService,
                frontendExceptionEnabled,
                slowAdEnabled,
                adLoadFailureEnabled,
                nextBreakOnlyEnabled,
                autoClearAt,
                Instant.now()
        );
    }

    private int normalizeDemoMonkeyValue(Integer value, int min, int max, String fieldName) {
        int normalized = value == null ? 0 : value;
        if (normalized < min || normalized > max) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " must be between " + min + " and " + max + "."
            );
        }
        return normalized;
    }

    private String normalizeDemoMonkeyDependencyService(String value, boolean enabled, boolean faultEnabled, String fieldName) {
        if (!enabled || !faultEnabled) {
            return "";
        }

        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!TRACE_MAP_DEPENDENCY_SERVICES.contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " must be one of " + String.join(", ", TRACE_MAP_DEPENDENCY_SERVICES) + "."
            );
        }
        return normalized;
    }

    private String normalizeDemoMonkeyPreset(String preset, boolean enabled) {
        if (preset == null || preset.isBlank()) {
            return enabled ? "custom" : "clear";
        }

        return preset.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
    }

    private DemoMonkeyStatusResponse toDemoMonkeyStatusResponse(DemoMonkeyState state) {
        return new DemoMonkeyStatusResponse(
                state.enabled(),
                state.preset(),
                state.startupDelayMs(),
                state.throttleKbps(),
                state.disconnectAfterKb(),
                state.packetLossPercent(),
                state.playbackFailureEnabled(),
                state.traceMapFailureEnabled(),
                state.dependencyTimeoutEnabled(),
                state.dependencyTimeoutService(),
                state.dependencyFailureEnabled(),
                state.dependencyFailureService(),
                state.frontendExceptionEnabled(),
                state.slowAdEnabled(),
                state.adLoadFailureEnabled(),
                state.nextBreakOnlyEnabled(),
                state.autoClearAt() == null ? null : state.autoClearAt().toString(),
                state.updatedAt().toString(),
                demoMonkeySummary(state),
                DEMO_MONKEY_SCOPE,
                DEMO_MONKEY_AFFECTED_PATHS
        );
    }

    private String demoMonkeySummary(DemoMonkeyState state) {
        if (!state.enabled()) {
            return "Demo Monkey is bypassed. Playback, ad insertion, and broadcast traffic are flowing normally.";
        }

        List<String> effects = new ArrayList<>();
        if (state.startupDelayMs() > 0) {
            effects.add(state.startupDelayMs() + " ms startup lag");
        }
        if (state.throttleKbps() > 0) {
            effects.add(state.throttleKbps() + " kbps bandwidth clamp");
        }
        if (state.disconnectAfterKb() > 0) {
            effects.add("connection reset after " + state.disconnectAfterKb() + " KiB");
        }
        if (state.packetLossPercent() > 0) {
            effects.add(state.packetLossPercent() + "% of playback transfers drop before completion");
        }
        if (state.playbackFailureEnabled()) {
            effects.add("playback responses return HTTP 503");
        }
        if (state.traceMapFailureEnabled()) {
            effects.add("trace pivot returns HTTP 503");
        }
        if (state.dependencyTimeoutEnabled() && !state.dependencyTimeoutService().isBlank()) {
            effects.add(formatTraceDependencyLabel(state.dependencyTimeoutService()) + " times out in the trace pivot");
        }
        if (state.dependencyFailureEnabled() && !state.dependencyFailureService().isBlank()) {
            effects.add(formatTraceDependencyLabel(state.dependencyFailureService()) + " returns HTTP 503 in the trace pivot");
        }
        if (state.frontendExceptionEnabled()) {
            effects.add("browser exception fires on page load");
        }
        if (state.slowAdEnabled()) {
            effects.add("ad decisioning is delayed");
        }
        if (state.adLoadFailureEnabled()) {
            effects.add("ad loads fail before the sponsor clip plays");
        }
        if (state.nextBreakOnlyEnabled()) {
            effects.add("auto-clears after the next sponsor pod");
        }

        if (effects.isEmpty()) {
            return "Demo Monkey is armed, but no faults are currently configured.";
        }

        String summary = "Demo Monkey is active with " + String.join(", ", effects) + ".";
        if (state.nextBreakOnlyEnabled() && state.autoClearAt() != null) {
            summary += " Auto-clear is scheduled for " + state.autoClearAt() + ".";
        }
        return summary;
    }

    private String traceMapDetail(DemoMonkeyState state, boolean forcedFailure, boolean allHealthy) {
        if (forcedFailure) {
            return "Demo Monkey forced the trace pivot to return HTTP 503 after fanning out to the demo dependencies.";
        }

        List<String> injectedIssues = new ArrayList<>();
        if (state != null && state.enabled()) {
            if (state.dependencyTimeoutEnabled() && !state.dependencyTimeoutService().isBlank()) {
                injectedIssues.add(formatTraceDependencyLabel(state.dependencyTimeoutService()) + " timeout");
            }
            if (state.dependencyFailureEnabled() && !state.dependencyFailureService().isBlank()) {
                injectedIssues.add(formatTraceDependencyLabel(state.dependencyFailureService()) + " failure");
            }
        }

        if (!injectedIssues.isEmpty()) {
            return "Demo Monkey forced " + String.join(" and ", injectedIssues) + " in the public trace pivot.";
        }

        return allHealthy
                ? "Trace map request reached user, content, and billing services."
                : "One or more downstream demo services failed during the trace map request.";
    }

    private String formatTraceDependencyLabel(String serviceName) {
        return switch (serviceName) {
            case "user-service-demo" -> "User service";
            case "content-service-demo" -> "Content service";
            case "billing-service" -> "Billing service";
            case "ad-service-demo" -> "Ad service";
            default -> serviceName == null || serviceName.isBlank() ? "Selected dependency" : serviceName;
        };
    }

    private TraceMapDependencyResponse probeDependency(String serviceName, String targetUrl, DemoMonkeyState state) {
        String path = safePath(targetUrl);
        if (state != null && state.enabled()) {
            if (state.dependencyTimeoutEnabled() && serviceName.equals(state.dependencyTimeoutService())) {
                try {
                    pauseForDemoMonkey(TRACE_MAP_TIMEOUT.toMillis() + FORCED_DEPENDENCY_TIMEOUT_BUFFER_MS);
                } catch (IOException exception) {
                    return new TraceMapDependencyResponse(
                            serviceName,
                            path,
                            HttpStatus.GATEWAY_TIMEOUT.value(),
                            sanitizeErrorMessage(exception.getMessage())
                    );
                }
                return new TraceMapDependencyResponse(
                        serviceName,
                        path,
                        HttpStatus.GATEWAY_TIMEOUT.value(),
                        "Demo Monkey forced " + formatTraceDependencyLabel(serviceName) + " to time out."
                );
            }
            if (state.dependencyFailureEnabled() && serviceName.equals(state.dependencyFailureService())) {
                return new TraceMapDependencyResponse(
                        serviceName,
                        path,
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Demo Monkey forced " + formatTraceDependencyLabel(serviceName) + " to return HTTP 503."
                );
            }
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .GET()
                    .timeout(TRACE_MAP_TIMEOUT)
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new TraceMapDependencyResponse(
                    serviceName,
                    path,
                    response.statusCode(),
                    summarizeDependency(response.statusCode(), response.body())
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TraceMapDependencyResponse(
                    serviceName,
                    path,
                    HttpStatus.GATEWAY_TIMEOUT.value(),
                    sanitizeErrorMessage(exception.getMessage())
            );
        } catch (Exception exception) {
            return new TraceMapDependencyResponse(
                    serviceName,
                    path,
                    HttpStatus.GATEWAY_TIMEOUT.value(),
                    sanitizeErrorMessage(exception.getMessage())
            );
        }
    }

    private String summarizeDependency(int statusCode, String body) {
        String normalizedBody = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (normalizedBody.isBlank()) {
            return statusCode >= 200 && statusCode < 300 ? TRACE_MAP_OK_STATUS : "HTTP " + statusCode;
        }
        return normalizedBody.length() > 140 ? normalizedBody.substring(0, 140) + "..." : normalizedBody;
    }

    private String safePath(String targetUrl) {
        try {
            URI uri = new URI(targetUrl);
            return uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        } catch (URISyntaxException exception) {
            return targetUrl;
        }
    }

    public record RtspJobRequest(
            UUID contentId,
            String mediaType,
            String targetTitle,
            String sourceUrl,
            Integer captureDurationSeconds,
            String operatorEmail
    ) {
    }

    public record RtspJobResponse(
            UUID jobId,
            long jobNumber,
            UUID contentId,
            String mediaType,
            String targetTitle,
            String operatorEmail,
            String sourceUrl,
            Integer captureDurationSeconds,
            String status,
            String createdAt,
            String updatedAt,
            String playbackUrl,
            String playbackFormat,
            String errorMessage
    ) {
    }

    public record BroadcastStatusResponse(
            String channelId,
            String channelLabel,
            String status,
            String title,
            String sourceType,
            String updatedAt,
            UUID jobId,
            String publicPlaybackUrl,
            String operatorPlaybackUrl,
            String publicPageUrl,
            String detail,
            BroadcastAdStatusResponse adStatus
    ) {
    }

    public record BroadcastAdStatusResponse(
            String state,
            String podLabel,
            String sponsorLabel,
            String decisioningMode,
            String breakStartAt,
            String breakEndAt,
            String detail
    ) {
    }

    private record AdServiceCurrentResponse(
            String serviceState,
            String state,
            String podLabel,
            String sponsorLabel,
            String decisioningMode,
            String breakStartAt,
            String breakEndAt,
            String detail,
            String issueSummary,
            boolean insertAd,
            boolean slowAdEnabled,
            boolean adLoadFailureEnabled
    ) {
    }

    private record AdServiceIssueRequest(
            boolean enabled,
            String preset,
            int responseDelayMs,
            boolean adLoadFailureEnabled
    ) {
    }

    private record AdServiceTimelineRequest(
            String cycleOriginAt
    ) {
    }

    public record TraceMapResponse(
            String status,
            String entryService,
            String generatedAt,
            String detail,
            List<TraceMapDependencyResponse> dependencies
    ) {
    }

    public record TraceMapDependencyResponse(
            String service,
            String path,
            int statusCode,
            String detail
    ) {
        public boolean healthy() {
            return statusCode >= 200 && statusCode < 400;
        }
    }

    public record DemoMonkeyConfigRequest(
            Boolean enabled,
            String preset,
            Integer startupDelayMs,
            Integer throttleKbps,
            Integer disconnectAfterKb,
            Integer packetLossPercent,
            Boolean playbackFailureEnabled,
            Boolean traceMapFailureEnabled,
            Boolean dependencyTimeoutEnabled,
            String dependencyTimeoutService,
            Boolean dependencyFailureEnabled,
            String dependencyFailureService,
            Boolean frontendExceptionEnabled,
            Boolean slowAdEnabled,
            Boolean adLoadFailureEnabled,
            Boolean nextBreakOnlyEnabled
    ) {
    }

    public record DemoMonkeyStatusResponse(
            boolean enabled,
            String preset,
            int startupDelayMs,
            int throttleKbps,
            int disconnectAfterKb,
            int packetLossPercent,
            boolean playbackFailureEnabled,
            boolean traceMapFailureEnabled,
            boolean dependencyTimeoutEnabled,
            String dependencyTimeoutService,
            boolean dependencyFailureEnabled,
            String dependencyFailureService,
            boolean frontendExceptionEnabled,
            boolean slowAdEnabled,
            boolean adLoadFailureEnabled,
            boolean nextBreakOnlyEnabled,
            String autoClearAt,
            String updatedAt,
            String summary,
            String scope,
            List<String> affectedPaths
    ) {
    }

    record BroadcastSelection(
            UUID jobId,
            String title,
            String status,
            String sourceType,
            Instant updatedAt,
            Path mediaPath,
            Path playlistPath,
            String liveSourceUrl,
            String operatorPlaybackUrl,
            String detail
    ) {
    }

    private record BroadcastAdInsertionPlan(
            boolean insertAd,
            boolean slowAd,
            boolean adLoadFailure,
            String adAssetPath,
            String stallAssetPath
    ) {
    }

    private record DemoMonkeyState(
            boolean enabled,
            String preset,
            int startupDelayMs,
            int throttleKbps,
            int disconnectAfterKb,
            int packetLossPercent,
            boolean playbackFailureEnabled,
            boolean traceMapFailureEnabled,
            boolean dependencyTimeoutEnabled,
            String dependencyTimeoutService,
            boolean dependencyFailureEnabled,
            String dependencyFailureService,
            boolean frontendExceptionEnabled,
            boolean slowAdEnabled,
            boolean adLoadFailureEnabled,
            boolean nextBreakOnlyEnabled,
            Instant autoClearAt,
            Instant updatedAt
    ) {
        private static DemoMonkeyState disabled() {
            return disabledAt(Instant.EPOCH);
        }

        private static DemoMonkeyState disabledAt(Instant updatedAt) {
            return new DemoMonkeyState(false, "clear", 0, 0, 0, 0, false, false, false, "", false, "", false, false, false, false, null, updatedAt);
        }
    }

    private record DemoMonkeyExecutionPlan(
            boolean active,
            String preset,
            int startupDelayMs,
            boolean playbackFailureEnabled,
            long bytesPerSecond,
            long disconnectAfterBytes,
            int packetLossPercent
    ) {
        private static DemoMonkeyExecutionPlan from(DemoMonkeyState state) {
            if (state == null || !state.enabled()) {
                return new DemoMonkeyExecutionPlan(false, "clear", 0, false, 0, 0, 0);
            }

            long bytesPerSecond = state.throttleKbps() > 0
                    ? Math.max(1L, Math.round(state.throttleKbps() * 1024d / 8d))
                    : 0L;
            long disconnectAfterBytes = state.disconnectAfterKb() > 0
                    ? Math.max(1L, state.disconnectAfterKb() * 1024L)
                    : 0L;

            return new DemoMonkeyExecutionPlan(
                    true,
                    state.preset(),
                    state.startupDelayMs(),
                    state.playbackFailureEnabled(),
                    bytesPerSecond,
                    disconnectAfterBytes,
                    state.packetLossPercent()
            );
        }

        private long delayForBytes(int bytesSent) {
            if (bytesPerSecond <= 0 || bytesSent <= 0) {
                return 0;
            }

            return Math.max(1L, Math.round(bytesSent * 1000d / bytesPerSecond));
        }

        private long packetLossTriggerByte(long contentLength) {
            if (packetLossPercent <= 0 || contentLength <= 1) {
                return 0L;
            }

            if (ThreadLocalRandom.current().nextInt(100) >= packetLossPercent) {
                return 0L;
            }

            return ThreadLocalRandom.current().nextLong(1L, contentLength);
        }
    }

    private static final class RtspJob {
        private final UUID jobId;
        private final long jobNumber;
        private final UUID contentId;
        private final String mediaType;
        private final String targetTitle;
        private final String ingestSourceUrl;
        private final String sourceUrl;
        private final String operatorEmail;
        private final int captureDurationSeconds;
        private final Instant createdAt;
        private volatile Instant updatedAt;
        private volatile String status;
        private volatile String playbackUrl;
        private volatile String errorMessage;
        private volatile Path capturePath;

        private RtspJob(
                UUID jobId,
                long jobNumber,
                UUID contentId,
                String mediaType,
                String targetTitle,
                String ingestSourceUrl,
                String sourceUrl,
                String operatorEmail,
                int captureDurationSeconds,
                Instant createdAt
        ) {
            this.jobId = jobId;
            this.jobNumber = jobNumber;
            this.contentId = contentId;
            this.mediaType = mediaType;
            this.targetTitle = targetTitle;
            this.ingestSourceUrl = ingestSourceUrl;
            this.sourceUrl = sourceUrl;
            this.operatorEmail = operatorEmail;
            this.captureDurationSeconds = captureDurationSeconds;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.status = QUEUED_STATUS;
        }

        private static RtspJob restored(
                UUID jobId,
                long jobNumber,
                UUID contentId,
                String mediaType,
                String targetTitle,
                String ingestSourceUrl,
                String sourceUrl,
                String operatorEmail,
                int captureDurationSeconds,
                Instant createdAt,
                Instant updatedAt,
                String status,
                String playbackUrl,
                String errorMessage,
                Path capturePath
        ) {
            RtspJob job = new RtspJob(
                    jobId,
                    jobNumber,
                    contentId,
                    mediaType,
                    targetTitle,
                    ingestSourceUrl,
                    sourceUrl,
                    operatorEmail,
                    captureDurationSeconds,
                    createdAt
            );
            job.updatedAt = updatedAt == null ? createdAt : updatedAt;
            job.status = status == null || status.isBlank() ? QUEUED_STATUS : status;
            job.playbackUrl = playbackUrl;
            job.errorMessage = errorMessage;
            job.capturePath = capturePath;
            return job;
        }

        private synchronized void markStatus(String status) {
            this.status = status;
            this.updatedAt = Instant.now();
        }

        private synchronized void markReady(String playbackUrl, Path capturePath) {
            this.status = READY_STATUS;
            this.playbackUrl = playbackUrl;
            this.capturePath = capturePath;
            this.errorMessage = null;
            this.updatedAt = Instant.now();
        }

        private synchronized void markError(String errorMessage) {
            this.status = ERROR_STATUS;
            this.errorMessage = errorMessage;
            this.playbackUrl = null;
            this.capturePath = null;
            this.updatedAt = Instant.now();
        }

        private UUID jobId() {
            return jobId;
        }

        private long jobNumber() {
            return jobNumber;
        }

        private UUID contentId() {
            return contentId;
        }

        private String mediaType() {
            return mediaType;
        }

        private String targetTitle() {
            return targetTitle;
        }

        private String sourceUrl() {
            return sourceUrl;
        }

        private String ingestSourceUrl() {
            return ingestSourceUrl;
        }

        private String operatorEmail() {
            return operatorEmail;
        }

        private int captureDurationSeconds() {
            return captureDurationSeconds;
        }

        private Instant createdAt() {
            return createdAt;
        }

        private Instant updatedAt() {
            return updatedAt;
        }

        private String status() {
            return status;
        }

        private String playbackUrl() {
            return playbackUrl;
        }

        private String errorMessage() {
            return errorMessage;
        }

        private Path capturePath() {
            return capturePath;
        }
    }

    private static final class RtspWorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger threadCounter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "demo-rtsp-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class RtspCleanupThreadFactory implements ThreadFactory {
        private final AtomicInteger threadCounter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "demo-rtsp-cleanup-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
