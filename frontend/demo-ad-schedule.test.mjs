/**
 * Unit tests for demo-ad-schedule.mjs.
 *
 * Uses node:assert only — no external test framework.
 * Run with:  node demo-ad-schedule.test.mjs
 */

import assert from "node:assert";

import {
    fallbackAdWindows,
    fallbackActiveOrNextAdWindow,
    FALLBACK_AD_BREAK_DURATION_SECONDS,
    FALLBACK_AD_PROGRAM_CYCLE_SECONDS,
    FALLBACK_AD_BREAK_COUNT
} from "./demo-ad-schedule.mjs";

// ---------------------------------------------------------------------------
// Simple test runner
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function run(name, fn) {
    try {
        fn();
        console.log(`  PASS  ${name}`);
        passed += 1;
    } catch (err) {
        console.error(`  FAIL  ${name}`);
        console.error(`        ${err.message}`);
        failed += 1;
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

run("fallbackAdWindows returns FALLBACK_AD_BREAK_COUNT windows per cycle", () => {
    const { windows } = fallbackAdWindows();
    assert.strictEqual(
        windows.length,
        FALLBACK_AD_BREAK_COUNT,
        `expected ${FALLBACK_AD_BREAK_COUNT} windows, got ${windows.length}`
    );
});

run("each window has start before end and the correct duration", () => {
    const { windows } = fallbackAdWindows();
    const expectedDurationMs = FALLBACK_AD_BREAK_DURATION_SECONDS * 1000;

    for (const window of windows) {
        assert.ok(
            window.start < window.end,
            `window slotIndex=${window.slotIndex}: start must be before end`
        );
        const actualDurationMs = window.end.getTime() - window.start.getTime();
        assert.strictEqual(
            actualDurationMs,
            expectedDurationMs,
            `window slotIndex=${window.slotIndex}: expected duration ${expectedDurationMs}ms, got ${actualDurationMs}ms`
        );
    }
});

run("fallbackActiveOrNextAdWindow always returns a non-null scheduledWindow", () => {
    // Test at several points in time: well before the first break, during a break,
    // after all breaks, and across a cycle boundary.
    const { cycleStart, windows } = fallbackAdWindows();

    const probes = [
        cycleStart,
        windows[0].start,
        new Date(windows[0].start.getTime() + 5000),
        windows[0].end,
        windows.at(-1).end,
        new Date(cycleStart.getTime() + FALLBACK_AD_PROGRAM_CYCLE_SECONDS * 1000 + 1000)
    ];

    for (const reference of probes) {
        const result = fallbackActiveOrNextAdWindow(reference);
        assert.ok(
            result.scheduledWindow !== null && result.scheduledWindow !== undefined,
            `scheduledWindow must not be null at reference ${reference.toISOString()}`
        );
    }
});

run("windows within a cycle are in chronological order", () => {
    const { windows } = fallbackAdWindows();

    for (let i = 1; i < windows.length; i += 1) {
        assert.ok(
            windows[i].start >= windows[i - 1].end,
            `window ${i} start (${windows[i].start.toISOString()}) must not overlap window ${i - 1} end (${windows[i - 1].end.toISOString()})`
        );
    }
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log(`\n${passed} passed, ${failed} failed`);
if (failed > 0) {
    process.exitCode = 1;
}
