package io.github.marianciuc.streamingservice.ad.demo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@CrossOrigin(origins = "*")
public class DemoAdController {

    private static final int MAX_DELAY_MS = 15_000;
    private static final int DEMO_AD_BREAK_SEGMENT_SECONDS = 90;
    private static final int DEMO_AD_BREAK_DURATION_SECONDS = 15;
    private static final Duration PROGRAM_AD_SLOT = Duration.ofSeconds(DEMO_AD_BREAK_DURATION_SECONDS);
    private static final List<HouseLoopProgram> HOUSE_LOOP_PROGRAMS = List.of(
            new HouseLoopProgram(
                    "Big Buck Bunny",
                    Duration.ofSeconds(597),
                    "/api/v1/demo/media/library/big-buck-bunny.mp4",
                    "Forest slapstick opens the house lineup before sponsor pod A."
            ),
            new HouseLoopProgram(
                    "Elephants Dream",
                    Duration.ofSeconds(654),
                    "/api/v1/demo/media/library/elephants-dream.mp4",
                    "The machine-world feature follows sponsor pod A and leads into the next stitched break."
            ),
            new HouseLoopProgram(
                    "Sintel",
                    Duration.ofSeconds(888),
                    "/api/v1/demo/media/library/sintel.mp4",
                    "The longer fantasy feature carries the mid-show window before sponsor pod C."
            ),
            new HouseLoopProgram(
                    "Tears of Steel",
                    Duration.ofSeconds(734),
                    "/api/v1/demo/media/library/tears-of-steel.mp4",
                    "The closing hybrid feature resets the channel before the lineup rolls into the next sponsor pod."
            )
    );
    private static final List<HouseLoopSegment> HOUSE_LOOP_SEGMENTS = houseLoopSegments();
    private static final int PROGRAM_BREAK_COUNT = HOUSE_LOOP_SEGMENTS.size();
    private static final Duration PROGRAM_CYCLE = HOUSE_LOOP_SEGMENTS.stream()
            .map(HouseLoopSegment::duration)
            .reduce(Duration.ZERO, Duration::plus)
            .plus(PROGRAM_AD_SLOT.multipliedBy(PROGRAM_BREAK_COUNT));
    private static final Instant PROGRAM_CYCLE_ORIGIN = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PROGRAM_CHANNEL = "Acme Network East";
    private static final String AD_KIND = "AD";
    private static final String CONTENT_KIND = "CONTENT";
    private static final String READY_STATUS = "READY";
    private static final String DEGRADED_STATUS = "DEGRADED";
    private static final String FAILED_STATUS = "FAILED_TO_LOAD";
    private static final List<AdCampaign> AD_CAMPAIGNS = List.of(
            new AdCampaign("North Coast Sports Network", "Regional sports launch pod"),
            new AdCampaign("Metro Weather Desk", "Storm desk weather sponsorship"),
            new AdCampaign("City Arts Channel", "Festival takeover pod"),
            new AdCampaign("Pop-Up Event East", "Opening-week sponsor activation")
    );
    private final AtomicReference<AdIssueState> issueState;
    private final AtomicReference<ProgramTimelineState> timelineState;
    private final DemoAdStateRepository stateRepository;

    public DemoAdController(DemoAdStateRepository stateRepository) {
        this.stateRepository = stateRepository;
        this.issueState = new AtomicReference<>(AdIssueState.fromRecord(stateRepository.loadIssueState()));
        this.timelineState = new AtomicReference<>(ProgramTimelineState.fromRecord(stateRepository.loadTimelineState()));
    }

    @GetMapping("/healthz")
    public Map<String, String> probe() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/v1/demo/ads/health")
    public Map<String, String> health() {
        AdIssueState current = issueState.get();
        maybeDelay(current);
        return Map.of(
                "status", current.enabled() ? "degraded" : "ok",
                "summary", issueSummary(current),
                "updatedAt", current.updatedAt().toString()
        );
    }

    @GetMapping("/api/v1/demo/ads/current")
    public AdCurrentResponse currentDecision() {
        AdIssueState current = issueState.get();
        maybeDelay(current);
        ActiveWindowPair windowPair = activeOrNextWindow();
        DemoAdWindow activeWindow = windowPair.activeWindow();
        DemoAdWindow scheduledWindow = activeWindow != null ? activeWindow : windowPair.nextWindow();
        AdCampaign campaign = campaignForWindow(scheduledWindow);
        boolean adLoadFailure = current.enabled() && current.adLoadFailureEnabled();
        boolean slowAdEnabled = current.enabled() && current.responseDelayMs() > 0;
        String serviceState = current.enabled() ? DEGRADED_STATUS : READY_STATUS;
        String state = activeWindow != null ? "IN_BREAK" : "ARMED";
        String detail = adLoadFailure
                ? campaign.label() + " is scheduled, but the injected ad-load failure is forcing a missed sponsor break inside the house loop."
                : activeWindow != null
                    ? campaign.label() + " is active and the short sponsor clip is stitched into the house loop right now."
                    : campaign.label() + " is armed for the next sponsor pod in the 90-second house loop.";

        return new AdCurrentResponse(
                serviceState,
                state,
                "Sponsor pod " + (char) ('A' + scheduledWindow.slotIndex()),
                campaign.sponsor(),
                current.enabled() && current.responseDelayMs() > 0
                        ? "Ad decisioning delayed by " + current.responseDelayMs() + " ms"
                        : "Server-side stitched pod",
                scheduledWindow.start().toInstant().toString(),
                scheduledWindow.end().toInstant().toString(),
                detail,
                issueSummary(current),
                true,
                slowAdEnabled,
                adLoadFailure
        );
    }

    @GetMapping("/api/v1/demo/ads/program-queue")
    public AdProgramQueueResponse programQueue() {
        AdIssueState current = issueState.get();
        maybeDelay(current);
        LoopCycle cycle = currentCycle();
        List<AdProgramQueueEntry> queue = buildProgramQueue(cycle, current);

        return new AdProgramQueueResponse(
                PROGRAM_CHANNEL,
                current.enabled() ? DEGRADED_STATUS : READY_STATUS,
                issueSummary(current),
                Instant.now().toString(),
                queue
        );
    }

    @GetMapping("/api/v1/demo/ads/issues")
    public AdIssueResponse issueStatus() {
        return toIssueResponse(issueState.get());
    }

    @GetMapping("/api/v1/demo/ads/timeline")
    public AdTimelineResponse timelineStatus() {
        return toTimelineResponse(timelineState.get());
    }

    @PutMapping("/api/v1/demo/ads/issues")
    public AdIssueResponse updateIssues(@RequestBody AdIssueRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }

        boolean enabled = Boolean.TRUE.equals(request.enabled());
        int responseDelayMs = normalizeDelay(request.responseDelayMs());
        boolean adLoadFailureEnabled = Boolean.TRUE.equals(request.adLoadFailureEnabled());
        String preset = normalizePreset(request.preset(), enabled, responseDelayMs, adLoadFailureEnabled);

        if (!enabled) {
            responseDelayMs = 0;
            adLoadFailureEnabled = false;
            preset = "clear";
        }

        AdIssueState updated = new AdIssueState(
                enabled,
                preset,
                responseDelayMs,
                adLoadFailureEnabled,
                Instant.now()
        );
        issueState.set(updated);
        stateRepository.saveIssueState(updated.toRecord());
        return toIssueResponse(updated);
    }

    @PutMapping("/api/v1/demo/ads/timeline")
    public AdTimelineResponse updateTimeline(@RequestBody AdTimelineRequest request) {
        if (request == null || request.cycleOriginAt() == null || request.cycleOriginAt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cycleOriginAt is required.");
        }

        Instant cycleOriginAt;
        try {
            cycleOriginAt = Instant.parse(request.cycleOriginAt().trim());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cycleOriginAt must be an ISO-8601 instant.");
        }

        ProgramTimelineState updated = new ProgramTimelineState(cycleOriginAt, Instant.now());
        timelineState.set(updated);
        stateRepository.saveTimelineState(updated.toRecord());
        return toTimelineResponse(updated);
    }

    private List<AdProgramQueueEntry> buildProgramQueue(LoopCycle cycle, AdIssueState current) {
        ZonedDateTime cursor = cycle.start();
        List<AdProgramQueueEntry> entries = new java.util.ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now();
        for (int index = 0; index < HOUSE_LOOP_SEGMENTS.size(); index++) {
            HouseLoopSegment segment = HOUSE_LOOP_SEGMENTS.get(index);
            Duration contentDuration = segment.duration();
            ZonedDateTime contentEnd = cursor.plus(contentDuration);
            entries.add(new AdProgramQueueEntry(
                    "content-" + cycle.sequence() + "-" + index,
                    null,
                    CONTENT_KIND,
                    segment.title(),
                    PROGRAM_CHANNEL,
                    slotLabel(cursor, contentEnd),
                    !now.isBefore(cursor) && now.isBefore(contentEnd) ? "NOW" : "QUEUED",
                    segment.detail(),
                    segment.playbackUrl(),
                    formatDurationLabel(contentDuration)
            ));
            cursor = contentEnd;

            long breakSequence = cycle.sequence() * PROGRAM_BREAK_COUNT + index;
            AdCampaign campaign = campaignForSequence(breakSequence);
            ZonedDateTime adEnd = cursor.plus(PROGRAM_AD_SLOT);
            boolean failed = current.enabled() && current.adLoadFailureEnabled();
            boolean activeBreak = !now.isBefore(cursor) && now.isBefore(adEnd);
            entries.add(new AdProgramQueueEntry(
                    "ad-" + breakSequence,
                    null,
                    AD_KIND,
                    campaign.sponsor(),
                    PROGRAM_CHANNEL,
                    slotLabel(cursor, adEnd),
                    failed ? FAILED_STATUS : activeBreak ? "NOW" : current.enabled() ? DEGRADED_STATUS : READY_STATUS,
                    failed
                            ? campaign.label() + " is queued here, but the injected ad fault will make this sponsor break miss."
                            : campaign.label() + " inserts the short sponsor clip at the next 90-second break.",
                    failed ? null : "/api/v1/demo/media/library/sponsor-break.mp4",
                    DEMO_AD_BREAK_DURATION_SECONDS + "s"
            ));
            cursor = adEnd;
        }

        return entries;
    }

    private ActiveWindowPair activeOrNextWindow() {
        LoopCycle currentCycle = currentCycle();
        ZonedDateTime now = ZonedDateTime.now();
        List<DemoAdWindow> windows = demoAdWindows(currentCycle.sequence(), currentCycle.start());
        DemoAdWindow active = windows.stream()
                .filter(window -> !now.isBefore(window.start()) && now.isBefore(window.end()))
                .findFirst()
                .orElse(null);
        DemoAdWindow next = windows.stream()
                .filter(window -> now.isBefore(window.start()))
                .min(Comparator.comparing(DemoAdWindow::start))
                .orElseGet(() -> demoAdWindows(currentCycle.sequence() + 1, currentCycle.end()).getFirst());
        return new ActiveWindowPair(active, next);
    }

    private List<DemoAdWindow> demoAdWindows(long cycleSequence, ZonedDateTime cycleStart) {
        List<DemoAdWindow> windows = new java.util.ArrayList<>();
        long cursorSeconds = 0;
        for (int segmentIndex = 0; segmentIndex < HOUSE_LOOP_SEGMENTS.size(); segmentIndex++) {
            cursorSeconds += HOUSE_LOOP_SEGMENTS.get(segmentIndex).duration().toSeconds();

            ZonedDateTime breakStart = cycleStart.plusSeconds(cursorSeconds);
            windows.add(new DemoAdWindow(
                    cycleSequence * PROGRAM_BREAK_COUNT + segmentIndex,
                    segmentIndex,
                    breakStart,
                    breakStart.plusSeconds(DEMO_AD_BREAK_DURATION_SECONDS)
            ));
            cursorSeconds += DEMO_AD_BREAK_DURATION_SECONDS;
        }

        return windows;
    }

    private AdCampaign campaignForWindow(DemoAdWindow window) {
        int index = (int) Math.floorMod(window.sequence(), AD_CAMPAIGNS.size());
        return AD_CAMPAIGNS.get(index);
    }

    private AdCampaign campaignForSequence(long sequence) {
        int index = (int) Math.floorMod(sequence, AD_CAMPAIGNS.size());
        return AD_CAMPAIGNS.get(index);
    }

    private AdIssueResponse toIssueResponse(AdIssueState state) {
        return new AdIssueResponse(
                state.enabled(),
                state.preset(),
                state.responseDelayMs(),
                state.adLoadFailureEnabled(),
                state.updatedAt().toString(),
                issueSummary(state),
                List.of(
                        "/api/v1/demo/ads/current",
                        "/api/v1/demo/ads/program-queue",
                        "/api/v1/demo/ads/health",
                        "/api/v1/demo/public/broadcast/current"
                )
        );
    }

    private String issueSummary(AdIssueState state) {
        if (!state.enabled()) {
            return "Ad service is healthy. Sponsor clips are inserted about every 90 seconds throughout the house loop without additional delay.";
        }

        java.util.List<String> effects = new java.util.ArrayList<>();
        if (state.responseDelayMs() > 0) {
            effects.add(state.responseDelayMs() + " ms decisioning delay");
        }
        if (state.adLoadFailureEnabled()) {
            effects.add("ad clip load failures");
        }

        return effects.isEmpty()
                ? "Ad service issue injection is enabled with no active effects."
                : "Ad service issue injection is active with " + String.join(" and ", effects) + ".";
    }

    private int normalizeDelay(Integer responseDelayMs) {
        int normalized = responseDelayMs == null ? 0 : responseDelayMs;
        if (normalized < 0 || normalized > MAX_DELAY_MS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "responseDelayMs must be between 0 and " + MAX_DELAY_MS + ".");
        }
        return normalized;
    }

    private String normalizePreset(String preset, boolean enabled, int responseDelayMs, boolean adLoadFailureEnabled) {
        if (preset != null && !preset.isBlank()) {
            return preset.trim().toLowerCase().replace(' ', '-').replace('_', '-');
        }

        if (!enabled) {
            return "clear";
        }
        if (responseDelayMs > 0 && adLoadFailureEnabled) {
            return "slow-and-failed";
        }
        if (adLoadFailureEnabled) {
            return "failed-ads";
        }
        if (responseDelayMs > 0) {
            return "slow-decisioning";
        }
        return "custom";
    }

    private void maybeDelay(AdIssueState state) {
        if (!state.enabled() || state.responseDelayMs() <= 0) {
            return;
        }

        try {
            Thread.sleep(state.responseDelayMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Ad service delay injection was interrupted.");
        }
    }

    private LoopCycle currentCycle() {
        long cycleSeconds = PROGRAM_CYCLE.getSeconds();
        Instant cycleOrigin = timelineState.get().cycleOriginAt();
        long elapsedSeconds = Duration.between(cycleOrigin, Instant.now()).getSeconds();
        long cycleSequence = Math.floorDiv(elapsedSeconds, cycleSeconds);
        Instant cycleStartInstant = cycleOrigin.plusSeconds(cycleSequence * cycleSeconds);
        ZonedDateTime cycleStart = ZonedDateTime.ofInstant(cycleStartInstant, ZonedDateTime.now().getZone());
        return new LoopCycle(cycleSequence, cycleStart, cycleStart.plus(PROGRAM_CYCLE));
    }

    private AdTimelineResponse toTimelineResponse(ProgramTimelineState state) {
        return new AdTimelineResponse(
                state.cycleOriginAt().toString(),
                state.updatedAt().toString(),
                PROGRAM_CYCLE.getSeconds()
        );
    }

    private String slotLabel(ZonedDateTime start, ZonedDateTime end) {
        return String.format("%s - %s", formatClock(start), formatClock(end));
    }

    private String formatClock(ZonedDateTime value) {
        int hour = value.getHour() % 12 == 0 ? 12 : value.getHour() % 12;
        return hour + ":" + String.format("%02d", value.getMinute()) + (value.getHour() < 12 ? " AM" : " PM");
    }

    private static List<HouseLoopSegment> houseLoopSegments() {
        List<HouseLoopSegment> segments = new java.util.ArrayList<>();

        for (HouseLoopProgram program : HOUSE_LOOP_PROGRAMS) {
            long totalSeconds = program.duration().toSeconds();
            int segmentCount = Math.max(1, (int) Math.ceil((double) totalSeconds / DEMO_AD_BREAK_SEGMENT_SECONDS));
            long remainingSeconds = totalSeconds;

            for (int part = 1; remainingSeconds > 0; part++) {
                long segmentSeconds = Math.min(DEMO_AD_BREAK_SEGMENT_SECONDS, remainingSeconds);
                String title = segmentCount > 1 ? program.title() + " · Part " + part : program.title();
                String detail = segmentCount > 1
                        ? program.detail() + " Part " + part + " of " + segmentCount + " in the tighter sponsor loop."
                        : program.detail();
                segments.add(new HouseLoopSegment(
                        title,
                        Duration.ofSeconds(segmentSeconds),
                        program.playbackUrl(),
                        detail
                ));
                remainingSeconds -= segmentSeconds;
            }
        }

        return List.copyOf(segments);
    }

    private String formatDurationLabel(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        if (seconds > 60) {
            long minutes = seconds / 60;
            long remainder = seconds % 60;
            return minutes + "m " + remainder + "s";
        }
        return seconds + "s";
    }

    private record AdCampaign(
            String sponsor,
            String label
    ) {
    }

    private record HouseLoopProgram(
            String title,
            Duration duration,
            String playbackUrl,
            String detail
    ) {
    }

    private record HouseLoopSegment(
            String title,
            Duration duration,
            String playbackUrl,
            String detail
    ) {
    }

    private record DemoAdWindow(
            long sequence,
            int slotIndex,
            ZonedDateTime start,
            ZonedDateTime end
    ) {
    }

    private record ActiveWindowPair(
            DemoAdWindow activeWindow,
            DemoAdWindow nextWindow
    ) {
    }

    private record LoopCycle(
            long sequence,
            ZonedDateTime start,
            ZonedDateTime end
    ) {
    }

    static record AdIssueStateRecord(
            boolean enabled,
            String preset,
            int responseDelayMs,
            boolean adLoadFailureEnabled,
            Instant updatedAt
    ) {
    }

    private record AdIssueState(
            boolean enabled,
            String preset,
            int responseDelayMs,
            boolean adLoadFailureEnabled,
            Instant updatedAt
    ) {
        private static AdIssueState disabled() {
            return new AdIssueState(false, "clear", 0, false, Instant.EPOCH);
        }

        private static AdIssueState fromRecord(AdIssueStateRecord record) {
            return new AdIssueState(
                    record.enabled(),
                    record.preset(),
                    record.responseDelayMs(),
                    record.adLoadFailureEnabled(),
                    record.updatedAt()
            );
        }

        private AdIssueStateRecord toRecord() {
            return new AdIssueStateRecord(enabled, preset, responseDelayMs, adLoadFailureEnabled, updatedAt);
        }
    }

    static record ProgramTimelineStateRecord(
            Instant cycleOriginAt,
            Instant updatedAt
    ) {
    }

    private record ProgramTimelineState(
            Instant cycleOriginAt,
            Instant updatedAt
    ) {
        private static ProgramTimelineState defaulted() {
            return new ProgramTimelineState(PROGRAM_CYCLE_ORIGIN, Instant.EPOCH);
        }

        private static ProgramTimelineState fromRecord(ProgramTimelineStateRecord record) {
            return new ProgramTimelineState(record.cycleOriginAt(), record.updatedAt());
        }

        private ProgramTimelineStateRecord toRecord() {
            return new ProgramTimelineStateRecord(cycleOriginAt, updatedAt);
        }
    }

    public record AdCurrentResponse(
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

    public record AdProgramQueueResponse(
            String channelLabel,
            String serviceState,
            String issueSummary,
            String generatedAt,
            List<AdProgramQueueEntry> items
    ) {
    }

    public record AdProgramQueueEntry(
            String entryId,
            String contentId,
            String kind,
            String title,
            String channelLabel,
            String slotLabel,
            String status,
            String detail,
            String playbackUrl,
            String durationLabel
    ) {
    }

    public record AdIssueRequest(
            Boolean enabled,
            String preset,
            Integer responseDelayMs,
            Boolean adLoadFailureEnabled
    ) {
    }

    public record AdIssueResponse(
            boolean enabled,
            String preset,
            int responseDelayMs,
            boolean adLoadFailureEnabled,
            String updatedAt,
            String summary,
            List<String> affectedPaths
    ) {
    }

    public record AdTimelineRequest(
            String cycleOriginAt
    ) {
    }

    public record AdTimelineResponse(
            String cycleOriginAt,
            String updatedAt,
            long cycleLengthSeconds
    ) {
    }
}
