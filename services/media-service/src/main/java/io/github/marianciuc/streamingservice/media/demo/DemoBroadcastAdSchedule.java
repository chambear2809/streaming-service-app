package io.github.marianciuc.streamingservice.media.demo;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class DemoBroadcastAdSchedule {

    static final String DEFAULT_BROADCAST_DETAIL =
            "Sintel, Big Buck Bunny, Elephants Dream, and Tears of Steel rotate on the external channel with sponsor pods about every 90 seconds until a contribution feed is taken live.";

    private static final int BREAK_SEGMENT_SECONDS = 90;
    private static final int BREAK_DURATION_SECONDS = 15;
    private static final Instant DEFAULT_CYCLE_ORIGIN = Instant.parse("2026-01-01T00:00:00Z");
    private static final DemoBroadcastAdSchedule DEFAULT_INSTANCE = new DemoBroadcastAdSchedule(
            List.of(
                    new HouseLoopAsset(
                            "sintel.mp4",
                            "Sintel",
                            Duration.ofSeconds(888),
                            "/api/v1/demo/media/library/sintel.mp4",
                            "The fantasy feature now opens the house lineup before sponsor pod A."
                    ),
                    new HouseLoopAsset(
                            "big-buck-bunny.mp4",
                            "Big Buck Bunny",
                            Duration.ofSeconds(597),
                            "/api/v1/demo/media/library/big-buck-bunny.mp4",
                            "Forest slapstick now follows the opening sponsor pod and resets the room after the fantasy lead-in."
                    ),
                    new HouseLoopAsset(
                            "elephants-dream.mp4",
                            "Elephants Dream",
                            Duration.ofSeconds(654),
                            "/api/v1/demo/media/library/elephants-dream.mp4",
                            "The machine-world feature carries the mid-show window before sponsor pod C."
                    ),
                    new HouseLoopAsset(
                            "tears-of-steel.mp4",
                            "Tears of Steel",
                            Duration.ofSeconds(734),
                            "/api/v1/demo/media/library/tears-of-steel.mp4",
                            "The closing hybrid feature resets the channel before the lineup rolls into the next sponsor pod."
                    )
            ),
            List.of(
                    new Campaign("North Coast Sports Network", "Regional sports launch pod", "two 15-second sports spots and a 30-second brand anthem"),
                    new Campaign("Metro Weather Desk", "Storm desk weather sponsorship", "a 30-second forecast sponsor followed by two 15-second local promos"),
                    new Campaign("City Arts Channel", "Festival takeover pod", "a 30-second arts festival sponsor with two 15-second tune-in promos"),
                    new Campaign("Pop-Up Event East", "Opening-week sponsor activation", "three 15-second event launch spots with a branded lower-third callout")
            ),
            BREAK_SEGMENT_SECONDS,
            BREAK_DURATION_SECONDS,
            DEFAULT_CYCLE_ORIGIN
    );

    private final List<HouseLoopAsset> houseLoopAssets;
    private final List<Campaign> campaigns;
    private final int breakSegmentSeconds;
    private final int breakDurationSeconds;
    private final Instant defaultCycleOrigin;
    private final List<HouseLoopSegment> houseLoopSegments;
    private final Duration cycleDuration;

    DemoBroadcastAdSchedule(
            List<HouseLoopAsset> houseLoopAssets,
            List<Campaign> campaigns,
            int breakSegmentSeconds,
            int breakDurationSeconds,
            Instant defaultCycleOrigin
    ) {
        this.houseLoopAssets = List.copyOf(houseLoopAssets);
        this.campaigns = List.copyOf(campaigns);
        this.breakSegmentSeconds = breakSegmentSeconds;
        this.breakDurationSeconds = breakDurationSeconds;
        this.defaultCycleOrigin = defaultCycleOrigin;
        this.houseLoopSegments = buildHouseLoopSegments(this.houseLoopAssets, breakSegmentSeconds);
        this.cycleDuration = this.houseLoopSegments.stream()
                .map(HouseLoopSegment::duration)
                .reduce(Duration.ZERO, Duration::plus)
                .plus(Duration.ofSeconds((long) breakDurationSeconds * this.houseLoopSegments.size()));
    }

    static DemoBroadcastAdSchedule defaultSchedule() {
        return DEFAULT_INSTANCE;
    }

    List<HouseLoopAsset> houseLoopAssets() {
        return houseLoopAssets;
    }

    List<HouseLoopSegment> houseLoopSegments() {
        return houseLoopSegments;
    }

    Duration cycleDuration() {
        return cycleDuration;
    }

    Instant defaultCycleOrigin() {
        return defaultCycleOrigin;
    }

    LoopCycle currentCycle(Instant cycleOrigin, ZonedDateTime now) {
        long cycleSeconds = cycleDuration.getSeconds();
        long elapsedSeconds = Duration.between(cycleOrigin, now.toInstant()).getSeconds();
        long cycleSequence = Math.floorDiv(elapsedSeconds, cycleSeconds);
        Instant cycleStartInstant = cycleOrigin.plusSeconds(cycleSequence * cycleSeconds);
        ZonedDateTime cycleStart = ZonedDateTime.ofInstant(cycleStartInstant, now.getZone());
        return new LoopCycle(cycleSequence, cycleStart, cycleStart.plus(cycleDuration));
    }

    List<AdBreakWindow> adBreakWindows(long cycleSequence, ZonedDateTime cycleStart) {
        List<AdBreakWindow> windows = new ArrayList<>();
        long cursorSeconds = 0;

        for (int segmentIndex = 0; segmentIndex < houseLoopSegments.size(); segmentIndex++) {
            cursorSeconds += houseLoopSegments.get(segmentIndex).duration().toSeconds();

            ZonedDateTime breakStart = cycleStart.plusSeconds(cursorSeconds);
            windows.add(new AdBreakWindow(
                    cycleSequence * houseLoopSegments.size() + segmentIndex,
                    segmentIndex,
                    breakStart,
                    breakStart.plusSeconds(breakDurationSeconds)
            ));
            cursorSeconds += breakDurationSeconds;
        }

        return windows;
    }

    WindowSelection currentOrNextWindow(Instant cycleOrigin, ZonedDateTime now) {
        LoopCycle currentCycle = currentCycle(cycleOrigin, now);
        List<AdBreakWindow> windows = adBreakWindows(currentCycle.sequence(), currentCycle.start());
        AdBreakWindow activeWindow = windows.stream()
                .filter(window -> !now.isBefore(window.start()) && now.isBefore(window.end()))
                .findFirst()
                .orElse(null);
        AdBreakWindow scheduledWindow = activeWindow != null
                ? activeWindow
                : windows.stream()
                    .filter(window -> now.isBefore(window.start()))
                    .min(Comparator.comparing(AdBreakWindow::start))
                    .orElseGet(() -> adBreakWindows(currentCycle.sequence() + 1, currentCycle.end()).getFirst());

        return new WindowSelection(currentCycle, activeWindow, scheduledWindow);
    }

    Campaign campaignForSequence(long sequence) {
        int campaignIndex = (int) Math.floorMod(sequence, campaigns.size());
        return campaigns.get(campaignIndex);
    }

    Instant currentOrNextBreakEnd(Instant cycleOrigin, ZonedDateTime now) {
        return currentOrNextWindow(cycleOrigin, now).scheduledWindow().end().toInstant();
    }

    DemoMediaController.BroadcastAdStatusResponse fallbackStatus(
            boolean liveContribution,
            String errorDetail,
            Instant cycleOrigin,
            ZonedDateTime now
    ) {
        WindowSelection selection = currentOrNextWindow(cycleOrigin, now);
        AdBreakWindow activeWindow = selection.activeWindow();
        AdBreakWindow scheduledWindow = selection.scheduledWindow();
        Campaign campaign = campaignForSequence(scheduledWindow.sequence());
        String playoutLabel = liveContribution ? "live contribution feed" : "house loop";
        String detail = activeWindow != null
                ? campaign.campaignLabel() + " with " + campaign.creativeMix() + " is stitched into the " + playoutLabel + " right now."
                : campaign.campaignLabel() + " with " + campaign.creativeMix() + " is queued for the next sponsor pod on the " + playoutLabel + ".";
        if (errorDetail != null && !errorDetail.isBlank()) {
            detail += " Ad service fallback is active: " + errorDetail;
        }

        return new DemoMediaController.BroadcastAdStatusResponse(
                activeWindow != null ? "IN_BREAK" : "ARMED",
                podLabel(scheduledWindow.slotIndex()),
                campaign.sponsorLabel(),
                liveContribution ? "Fallback live splice" : "Fallback stitched pod",
                scheduledWindow.start().toInstant().toString(),
                scheduledWindow.end().toInstant().toString(),
                detail
        );
    }

    DemoMediaController.BroadcastAdStatusResponse synchronizedDemoLoopStatus(
            CurrentAdPayload payload,
            String errorDetail,
            Instant cycleOrigin,
            ZonedDateTime now
    ) {
        WindowSelection selection = currentOrNextWindow(cycleOrigin, now);
        AdBreakWindow activeWindow = selection.activeWindow();
        AdBreakWindow scheduledWindow = selection.scheduledWindow();
        Campaign campaign = campaignForSequence(scheduledWindow.sequence());
        boolean adLoadFailureEnabled = payload != null && payload.adLoadFailureEnabled();
        boolean slowAdEnabled = payload != null && payload.slowAdEnabled();
        String sponsorLabel = payload != null && payload.sponsorLabel() != null && !payload.sponsorLabel().isBlank()
                ? payload.sponsorLabel()
                : campaign.sponsorLabel();
        String decisioningMode = payload != null && payload.decisioningMode() != null && !payload.decisioningMode().isBlank()
                ? payload.decisioningMode()
                : "Fallback stitched pod";
        String detail = adLoadFailureEnabled
                ? campaign.campaignLabel() + " is scheduled, but the injected ad-load failure will miss this sponsor pod inside the house loop."
                : activeWindow != null
                    ? campaign.campaignLabel() + " is active and the sponsor pod is stitched into the house loop right now."
                    : campaign.campaignLabel() + " is armed for the next sponsor pod inside the house loop.";
        if (slowAdEnabled && !adLoadFailureEnabled) {
            detail += " Ad decisioning delay is active for this break.";
        }
        if (payload != null && payload.issueSummary() != null && !payload.issueSummary().isBlank()) {
            detail += " " + payload.issueSummary();
        }
        if (errorDetail != null && !errorDetail.isBlank()) {
            detail += " Ad service fallback is active: " + errorDetail;
        }

        return new DemoMediaController.BroadcastAdStatusResponse(
                activeWindow != null ? "IN_BREAK" : "ARMED",
                podLabel(scheduledWindow.slotIndex()),
                sponsorLabel,
                decisioningMode,
                scheduledWindow.start().toInstant().toString(),
                scheduledWindow.end().toInstant().toString(),
                detail
        );
    }

    private static List<HouseLoopSegment> buildHouseLoopSegments(List<HouseLoopAsset> assets, int breakSegmentSeconds) {
        List<HouseLoopSegment> segments = new ArrayList<>();

        for (HouseLoopAsset asset : assets) {
            long totalSeconds = asset.duration().toSeconds();
            int segmentCount = Math.max(1, (int) Math.ceil((double) totalSeconds / breakSegmentSeconds));
            long remainingSeconds = totalSeconds;

            for (int part = 1; remainingSeconds > 0; part++) {
                long segmentSeconds = Math.min(breakSegmentSeconds, remainingSeconds);
                segments.add(new HouseLoopSegment(
                        segmentCount > 1 ? asset.title() + " · Part " + part : asset.title(),
                        Duration.ofSeconds(segmentSeconds),
                        asset.playbackUrl(),
                        segmentCount > 1
                                ? asset.detail() + " Part " + part + " of " + segmentCount + " in the tighter sponsor loop."
                                : asset.detail()
                ));
                remainingSeconds -= segmentSeconds;
            }
        }

        return List.copyOf(segments);
    }

    private String podLabel(int slotIndex) {
        return "Sponsor pod " + (char) ('A' + slotIndex);
    }

    record HouseLoopAsset(
            String fileName,
            String title,
            Duration duration,
            String playbackUrl,
            String detail
    ) {
    }

    record HouseLoopSegment(
            String title,
            Duration duration,
            String playbackUrl,
            String detail
    ) {
    }

    record Campaign(
            String sponsorLabel,
            String campaignLabel,
            String creativeMix
    ) {
    }

    record AdBreakWindow(
            long sequence,
            int slotIndex,
            ZonedDateTime start,
            ZonedDateTime end
    ) {
    }

    record LoopCycle(
            long sequence,
            ZonedDateTime start,
            ZonedDateTime end
    ) {
    }

    record WindowSelection(
            LoopCycle currentCycle,
            AdBreakWindow activeWindow,
            AdBreakWindow scheduledWindow
    ) {
    }

    record CurrentAdPayload(
            String sponsorLabel,
            String decisioningMode,
            String issueSummary,
            boolean slowAdEnabled,
            boolean adLoadFailureEnabled
    ) {
    }
}
