import test from "node:test";
import assert from "node:assert/strict";

import {
    DEFAULT_BROADCAST_DETAIL,
    FALLBACK_AD_BREAK_COUNT,
    FALLBACK_AD_BREAK_DURATION_SECONDS,
    FALLBACK_AD_CYCLE_ORIGIN_MS,
    FALLBACK_AD_PROGRAM_CYCLE_SECONDS,
    FALLBACK_HOUSE_LOOP_PROGRAMS,
    FALLBACK_HOUSE_LOOP_SEGMENTS,
    buildDefaultAdStatus,
    buildFallbackAdProgramQueue,
    fallbackActiveOrNextAdWindow,
    fallbackAdWindows,
    fallbackCampaignForSequence
} from "../demo-ad-schedule.mjs";

test("house loop segments preserve the source runtime and split at ninety seconds", () => {
    const totalProgramSeconds = FALLBACK_HOUSE_LOOP_PROGRAMS.reduce((total, program) => total + program.durationSeconds, 0);
    const totalSegmentSeconds = FALLBACK_HOUSE_LOOP_SEGMENTS.reduce((total, segment) => total + segment.durationSeconds, 0);

    assert.equal(FALLBACK_HOUSE_LOOP_SEGMENTS.length, 34);
    assert.equal(totalSegmentSeconds, totalProgramSeconds);
    assert.equal(FALLBACK_HOUSE_LOOP_SEGMENTS[0].title, "Sintel · Part 1");
    assert.equal(FALLBACK_HOUSE_LOOP_SEGMENTS[0].durationSeconds, 90);
    assert.equal(FALLBACK_HOUSE_LOOP_SEGMENTS.at(-1).title, "Tears of Steel · Part 9");
    assert.equal(FALLBACK_HOUSE_LOOP_SEGMENTS.at(-1).durationSeconds, 14);
});

test("active or next ad window returns the first scheduled pod before the first break", () => {
    const reference = new Date(FALLBACK_AD_CYCLE_ORIGIN_MS + 30_000);

    const result = fallbackActiveOrNextAdWindow(reference);

    assert.equal(result.activeWindow, null);
    assert.equal(result.scheduledWindow.slotIndex, 0);
    assert.equal(result.scheduledWindow.start.toISOString(), "2026-01-01T00:01:30.000Z");
    assert.equal(result.scheduledWindow.end.toISOString(), "2026-01-01T00:01:45.000Z");
});

test("active or next ad window detects an in-progress sponsor break", () => {
    const reference = new Date(FALLBACK_AD_CYCLE_ORIGIN_MS + 100_000);

    const result = fallbackActiveOrNextAdWindow(reference);
    const adStatus = buildDefaultAdStatus(reference);

    assert.equal(result.activeWindow.slotIndex, 0);
    assert.equal(result.scheduledWindow.slotIndex, 0);
    assert.equal(adStatus.state, "IN_BREAK");
    assert.equal(adStatus.podLabel, "Sponsor pod A");
    assert.match(adStatus.detail, /active and the short sponsor clip is stitched/i);
});

test("active or next ad window advances to the following pod at the exact break end", () => {
    const firstWindow = fallbackAdWindows(new Date(FALLBACK_AD_CYCLE_ORIGIN_MS + 30_000)).windows[0];

    const result = fallbackActiveOrNextAdWindow(firstWindow.end);

    assert.equal(result.activeWindow, null);
    assert.equal(result.scheduledWindow.slotIndex, 1);
    assert.equal(result.scheduledWindow.start.toISOString(), "2026-01-01T00:03:15.000Z");
});

test("program queue alternates content and ad entries across the segmented sponsor loop", () => {
    const reference = new Date(FALLBACK_AD_CYCLE_ORIGIN_MS + 30_000);
    const queue = buildFallbackAdProgramQueue({
        reference,
        channelLabel: "Acme Network East",
        formatClock: (value) => value.toISOString().slice(11, 19)
    });

    assert.equal(queue.length, FALLBACK_AD_BREAK_COUNT * 2);
    assert.equal(queue[0].kind, "CONTENT");
    assert.equal(queue[0].status, "NOW");
    assert.equal(queue[1].kind, "AD");
    assert.equal(queue[1].status, "READY");
    assert.equal(queue[1].title, fallbackCampaignForSequence(0).sponsor);
    assert.equal(queue[1].playbackUrl, "/api/v1/demo/media/library/sponsor-break.mp4");
    assert.equal(queue[1].durationLabel, `${FALLBACK_AD_BREAK_DURATION_SECONDS}s`);
    assert.equal(queue.at(-1).kind, "AD");
});

test("active or next ad window rolls over to the first pod in the next cycle after the final break", () => {
    const { windows } = fallbackAdWindows(new Date(FALLBACK_AD_CYCLE_ORIGIN_MS + 30_000));
    const finalWindow = windows.at(-1);

    const result = fallbackActiveOrNextAdWindow(finalWindow.end);

    assert.equal(result.activeWindow, null);
    assert.equal(result.scheduledWindow.slotIndex, 0);
    assert.equal(result.scheduledWindow.sequence, FALLBACK_AD_BREAK_COUNT);
    assert.equal(result.scheduledWindow.start.toISOString(), "2026-01-01T00:57:53.000Z");
});

test("program queue builder requires a clock formatter", () => {
    assert.throws(
        () => buildFallbackAdProgramQueue(),
        /requires a formatClock function/i
    );
});

test("cycle rollover keeps sponsor rotation stable on the next loop", () => {
    const reference = new Date(FALLBACK_AD_CYCLE_ORIGIN_MS + (FALLBACK_AD_PROGRAM_CYCLE_SECONDS + 30) * 1000);
    const windows = fallbackAdWindows(reference);

    assert.equal(windows.cycleSequence, 1);
    assert.equal(windows.windows[0].sequence, FALLBACK_AD_BREAK_COUNT);
    assert.equal(fallbackCampaignForSequence(windows.windows[0].sequence).sponsor, "City Arts Channel");
    assert.match(DEFAULT_BROADCAST_DETAIL, /about every 90 seconds/);
});
