package io.github.marianciuc.streamingservice.ad.demo;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoAdControllerTest {

    @Test
    void currentDecisionArmsFirstSponsorBreakBeforeTheLoopReachesIt() {
        DemoAdController controller = controllerWithState(false, 0, false, 30);

        DemoAdController.AdCurrentResponse response = controller.currentDecision();

        assertEquals("READY", response.serviceState());
        assertEquals("ARMED", response.state());
        assertEquals("Sponsor pod A", response.podLabel());
        assertEquals("North Coast Sports Network", response.sponsorLabel());
        assertEquals("Server-side stitched pod", response.decisioningMode());
        assertTrue(response.detail().contains("armed for the next sponsor pod"));
        assertFalse(response.slowAdEnabled());
        assertFalse(response.adLoadFailureEnabled());
        assertNotNull(response.breakStartAt());
        assertNotNull(response.breakEndAt());
    }

    @Test
    void currentDecisionReportsAnActiveBreakInsideTheFirstSponsorWindow() {
        DemoAdController controller = controllerWithState(false, 0, false, 100);

        DemoAdController.AdCurrentResponse response = controller.currentDecision();

        assertEquals("READY", response.serviceState());
        assertEquals("IN_BREAK", response.state());
        assertEquals("Sponsor pod A", response.podLabel());
        assertTrue(response.detail().contains("is active and the short sponsor clip is stitched"));
    }

    @Test
    void currentDecisionReportsSlowDecisioningModeAndIssueSummary() {
        DemoAdController controller = controllerWithState(true, 5, false, 30);

        DemoAdController.AdCurrentResponse response = controller.currentDecision();

        assertEquals("DEGRADED", response.serviceState());
        assertEquals("ARMED", response.state());
        assertEquals("Ad decisioning delayed by 5 ms", response.decisioningMode());
        assertEquals("Ad service issue injection is active with 5 ms decisioning delay.", response.issueSummary());
        assertTrue(response.slowAdEnabled());
        assertFalse(response.adLoadFailureEnabled());
        assertTrue(response.detail().contains("armed for the next sponsor pod in the 90-second house loop"));
    }

    @Test
    void currentDecisionPrioritizesAdLoadFailureCopyWhenSlowAndFailedPresetIsEnabled() {
        DemoAdController controller = controllerWithState(true, 5, true, 30);

        DemoAdController.AdCurrentResponse response = controller.currentDecision();

        assertEquals("DEGRADED", response.serviceState());
        assertEquals("ARMED", response.state());
        assertEquals("Ad decisioning delayed by 5 ms", response.decisioningMode());
        assertEquals("Ad service issue injection is active with 5 ms decisioning delay and ad clip load failures.", response.issueSummary());
        assertTrue(response.slowAdEnabled());
        assertTrue(response.adLoadFailureEnabled());
        assertTrue(response.detail().contains("injected ad-load failure is forcing a missed sponsor break"));
    }

    @Test
    void currentDecisionAdvancesToTheNextPodAtTheFirstBreakBoundary() {
        DemoAdController controller = controllerWithState(false, 0, false, 105);

        DemoAdController.AdCurrentResponse response = controller.currentDecision();

        assertEquals("ARMED", response.state());
        assertEquals("Sponsor pod B", response.podLabel());
        assertEquals("Metro Weather Desk", response.sponsorLabel());
        assertTrue(response.detail().contains("armed for the next sponsor pod in the 90-second house loop"));
    }

    @Test
    void currentDecisionRollsOverToTheFirstPodOfTheNextCycle() {
        DemoAdController controller = controllerWithState(false, 0, false, 3383);

        DemoAdController.AdCurrentResponse response = controller.currentDecision();

        assertEquals("ARMED", response.state());
        assertEquals("Sponsor pod A", response.podLabel());
        assertEquals("City Arts Channel", response.sponsorLabel());
        assertTrue(response.detail().contains("armed for the next sponsor pod in the 90-second house loop"));
    }

    @Test
    void programQueueBuildsSegmentedContentAndAdEntriesForTheFullHouseLoop() {
        DemoAdController controller = controllerWithState(false, 0, false, 30);

        DemoAdController.AdProgramQueueResponse response = controller.programQueue();

        assertEquals("Acme Network East", response.channelLabel());
        assertEquals("READY", response.serviceState());
        assertEquals(68, response.items().size());
        assertEquals("CONTENT", response.items().getFirst().kind());
        assertEquals("Big Buck Bunny · Part 1", response.items().getFirst().title());
        assertEquals("NOW", response.items().getFirst().status());
        assertEquals("AD", response.items().get(1).kind());
        assertEquals("North Coast Sports Network", response.items().get(1).title());
        assertEquals("READY", response.items().get(1).status());
        assertTrue(response.items().stream().anyMatch(item -> "Sintel · Part 10".equals(item.title())));
        assertEquals("AD", response.items().getLast().kind());
    }

    @Test
    void programQueueMarksSponsorPodsAsFailedWhenAdLoadsAreInjectedToFail() {
        DemoAdController controller = controllerWithState(true, 0, true, 30);

        DemoAdController.AdProgramQueueResponse response = controller.programQueue();
        DemoAdController.AdProgramQueueEntry firstAd = response.items().get(1);

        assertEquals("DEGRADED", response.serviceState());
        assertEquals("FAILED_TO_LOAD", firstAd.status());
        assertEquals("North Coast Sports Network", firstAd.title());
        assertNull(firstAd.playbackUrl());
        assertTrue(firstAd.detail().contains("injected ad fault will make this sponsor break miss"));
    }

    @Test
    void issueStatusReportsEnabledStateWithNoConfiguredEffects() {
        DemoAdController controller = controllerWithState(true, 0, false, 30);

        DemoAdController.AdIssueResponse response = controller.issueStatus();

        assertTrue(response.enabled());
        assertEquals("custom", response.preset());
        assertEquals("Ad service issue injection is enabled with no active effects.", response.summary());
        assertTrue(response.affectedPaths().contains("/api/v1/demo/public/broadcast/current"));
    }

    private DemoAdController controllerWithState(boolean enabled, int responseDelayMs, boolean adLoadFailureEnabled, long elapsedSeconds) {
        DemoAdStateRepository repository = mock(DemoAdStateRepository.class);
        Instant now = Instant.now();
        String preset = !enabled
                ? "clear"
                : responseDelayMs > 0 && adLoadFailureEnabled
                    ? "slow-and-failed"
                    : adLoadFailureEnabled
                        ? "failed-ads"
                        : responseDelayMs > 0
                            ? "slow-decisioning"
                            : "custom";
        when(repository.loadIssueState()).thenReturn(new DemoAdController.AdIssueStateRecord(
                enabled,
                preset,
                responseDelayMs,
                adLoadFailureEnabled,
                now
        ));
        when(repository.loadTimelineState()).thenReturn(new DemoAdController.ProgramTimelineStateRecord(
                now.minusSeconds(elapsedSeconds),
                now
        ));

        return new DemoAdController(repository);
    }
}
