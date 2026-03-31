package io.github.marianciuc.streamingservice.media.demo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoBroadcastAdScheduleTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final DemoBroadcastAdSchedule schedule = DemoBroadcastAdSchedule.defaultSchedule();

    @Test
    void defaultScheduleSplitsTheHouseLoopIntoNinetySecondSegments() {
        long totalAssetSeconds = schedule.houseLoopAssets().stream()
                .mapToLong(asset -> asset.duration().toSeconds())
                .sum();
        long totalSegmentSeconds = schedule.houseLoopSegments().stream()
                .mapToLong(segment -> segment.duration().toSeconds())
                .sum();

        assertEquals(34, schedule.houseLoopSegments().size());
        assertEquals(3383, schedule.cycleDuration().getSeconds());
        assertEquals(totalAssetSeconds, totalSegmentSeconds);
        assertEquals("Sintel · Part 1", schedule.houseLoopSegments().getFirst().title());
        assertEquals("Tears of Steel · Part 9", schedule.houseLoopSegments().getLast().title());
    }

    @Test
    void currentOrNextWindowSchedulesTheFirstPodBeforeTheInitialBreak() {
        Instant cycleOrigin = schedule.defaultCycleOrigin();
        ZonedDateTime now = ZonedDateTime.ofInstant(cycleOrigin.plusSeconds(30), UTC);

        DemoBroadcastAdSchedule.WindowSelection selection = schedule.currentOrNextWindow(cycleOrigin, now);

        assertNull(selection.activeWindow());
        assertEquals(0, selection.scheduledWindow().slotIndex());
        assertEquals("2026-01-01T00:01:30Z", selection.scheduledWindow().start().toInstant().toString());
        assertEquals("2026-01-01T00:01:45Z", selection.scheduledWindow().end().toInstant().toString());
        assertEquals(selection.scheduledWindow().end().toInstant(), schedule.currentOrNextBreakEnd(cycleOrigin, now));
    }

    @Test
    void currentOrNextWindowAdvancesToTheFollowingPodAtTheExactBreakEnd() {
        Instant cycleOrigin = schedule.defaultCycleOrigin();
        DemoBroadcastAdSchedule.AdBreakWindow firstWindow = schedule.adBreakWindows(
                0,
                ZonedDateTime.ofInstant(cycleOrigin, UTC)
        ).getFirst();

        DemoBroadcastAdSchedule.WindowSelection selection = schedule.currentOrNextWindow(cycleOrigin, firstWindow.end());

        assertNull(selection.activeWindow());
        assertEquals(1, selection.scheduledWindow().slotIndex());
        assertEquals("2026-01-01T00:03:15Z", selection.scheduledWindow().start().toInstant().toString());
        assertEquals("2026-01-01T00:03:30Z", selection.scheduledWindow().end().toInstant().toString());
    }

    @Test
    void currentOrNextWindowRollsOverToTheFirstPodOfTheNextCycleAtTheCycleBoundary() {
        Instant cycleOrigin = schedule.defaultCycleOrigin();
        ZonedDateTime now = ZonedDateTime.ofInstant(cycleOrigin.plus(schedule.cycleDuration()), UTC);

        DemoBroadcastAdSchedule.WindowSelection selection = schedule.currentOrNextWindow(cycleOrigin, now);

        assertEquals(1, selection.currentCycle().sequence());
        assertNull(selection.activeWindow());
        assertEquals(0, selection.scheduledWindow().slotIndex());
        assertEquals(34, selection.scheduledWindow().sequence());
        assertEquals("2026-01-01T00:57:53Z", selection.scheduledWindow().start().toInstant().toString());
    }

    @Test
    void fallbackStatusUsesLiveContributionCopyWhenFallbackSchedulingIsActive() {
        Instant cycleOrigin = schedule.defaultCycleOrigin();
        ZonedDateTime now = ZonedDateTime.ofInstant(cycleOrigin.plusSeconds(100), UTC);

        DemoMediaController.BroadcastAdStatusResponse response = schedule.fallbackStatus(
                true,
                "Ad service returned HTTP 503",
                cycleOrigin,
                now
        );

        assertEquals("IN_BREAK", response.state());
        assertEquals("Sponsor pod A", response.podLabel());
        assertEquals("North Coast Sports Network", response.sponsorLabel());
        assertEquals("Fallback live splice", response.decisioningMode());
        assertTrue(response.detail().contains("live contribution feed"));
        assertTrue(response.detail().contains("Ad service fallback is active: Ad service returned HTTP 503"));
    }

    @Test
    void synchronizedDemoLoopStatusUsesPayloadOverridesAndIssueSummary() {
        Instant cycleOrigin = schedule.defaultCycleOrigin();
        ZonedDateTime now = ZonedDateTime.ofInstant(cycleOrigin.plusSeconds(30), UTC);
        DemoBroadcastAdSchedule.CurrentAdPayload payload = new DemoBroadcastAdSchedule.CurrentAdPayload(
                "Override Sponsor",
                "Delayed stitched pod",
                "Issue summary from ad service.",
                true,
                false
        );

        DemoMediaController.BroadcastAdStatusResponse response = schedule.synchronizedDemoLoopStatus(
                payload,
                "",
                cycleOrigin,
                now
        );

        assertEquals("ARMED", response.state());
        assertEquals("Override Sponsor", response.sponsorLabel());
        assertEquals("Delayed stitched pod", response.decisioningMode());
        assertTrue(response.detail().contains("armed for the next sponsor pod"));
        assertTrue(response.detail().contains("Ad decisioning delay is active for this break."));
        assertTrue(response.detail().contains("Issue summary from ad service."));
    }

    @Test
    void synchronizedDemoLoopStatusPrioritizesLoadFailureCopyOverSlowDecisioningCopy() {
        Instant cycleOrigin = schedule.defaultCycleOrigin();
        ZonedDateTime now = ZonedDateTime.ofInstant(cycleOrigin.plusSeconds(30), UTC);
        DemoBroadcastAdSchedule.CurrentAdPayload payload = new DemoBroadcastAdSchedule.CurrentAdPayload(
                "",
                "Delayed stitched pod",
                "Issue summary from ad service.",
                true,
                true
        );

        DemoMediaController.BroadcastAdStatusResponse response = schedule.synchronizedDemoLoopStatus(
                payload,
                "",
                cycleOrigin,
                now
        );

        assertEquals("ARMED", response.state());
        assertEquals("North Coast Sports Network", response.sponsorLabel());
        assertTrue(response.detail().contains("scheduled, but the injected ad-load failure will miss this sponsor pod"));
        assertTrue(response.detail().contains("Issue summary from ad service."));
        assertFalse(response.detail().contains("Ad decisioning delay is active for this break."));
    }
}
