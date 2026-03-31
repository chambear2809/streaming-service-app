export const DEFAULT_BROADCAST_DETAIL =
    "Sintel, Big Buck Bunny, Elephants Dream, and Tears of Steel rotate on the external channel with sponsor pods about every 90 seconds until a contribution feed is taken live.";
export const SPONSOR_BREAK_PLAYBACK_URL = "/api/v1/demo/media/library/sponsor-break.mp4";
export const FALLBACK_AD_BREAK_SEGMENT_SECONDS = 90;
export const FALLBACK_AD_BREAK_DURATION_SECONDS = 15;
export const FALLBACK_AD_CYCLE_ORIGIN_MS = Date.parse("2026-01-01T00:00:00Z");
export const FALLBACK_HOUSE_LOOP_PROGRAMS = [
    {
        title: "Sintel",
        durationSeconds: 888,
        playbackUrl: "/api/v1/demo/media/library/sintel.mp4",
        detail: "The fantasy feature now opens the house lineup before sponsor pod A."
    },
    {
        title: "Big Buck Bunny",
        durationSeconds: 597,
        playbackUrl: "/api/v1/demo/media/library/big-buck-bunny.mp4",
        detail: "Forest slapstick now follows the opening sponsor pod and resets the room after the fantasy lead-in."
    },
    {
        title: "Elephants Dream",
        durationSeconds: 654,
        playbackUrl: "/api/v1/demo/media/library/elephants-dream.mp4",
        detail: "The machine-world feature carries the mid-show window before sponsor pod C."
    },
    {
        title: "Tears of Steel",
        durationSeconds: 734,
        playbackUrl: "/api/v1/demo/media/library/tears-of-steel.mp4",
        detail: "The closing hybrid feature resets the channel before the lineup rolls into the next sponsor pod."
    }
];
export const FALLBACK_AD_CAMPAIGNS = [
    { sponsor: "North Coast Sports Network", label: "Regional sports launch pod" },
    { sponsor: "Metro Weather Desk", label: "Storm desk weather sponsorship" },
    { sponsor: "City Arts Channel", label: "Festival takeover pod" },
    { sponsor: "Pop-Up Event East", label: "Opening-week sponsor activation" }
];

export const FALLBACK_HOUSE_LOOP_SEGMENTS = buildHouseLoopSegments(FALLBACK_HOUSE_LOOP_PROGRAMS);
export const FALLBACK_AD_BREAK_COUNT = FALLBACK_HOUSE_LOOP_SEGMENTS.length;
export const FALLBACK_AD_PROGRAM_CYCLE_SECONDS = FALLBACK_HOUSE_LOOP_SEGMENTS.reduce(
    (total, segment) => total + segment.durationSeconds,
    0
) + (FALLBACK_AD_BREAK_DURATION_SECONDS * FALLBACK_AD_BREAK_COUNT);

function buildHouseLoopSegments(programs) {
    return programs.flatMap((program) => {
        const segments = [];
        let remainingSeconds = program.durationSeconds;
        let part = 0;
        const segmentCount = Math.max(1, Math.ceil(program.durationSeconds / FALLBACK_AD_BREAK_SEGMENT_SECONDS));

        while (remainingSeconds > 0) {
            const durationSeconds = Math.min(FALLBACK_AD_BREAK_SEGMENT_SECONDS, remainingSeconds);
            part += 1;
            segments.push({
                title: segmentCount > 1 ? `${program.title} · Part ${part}` : program.title,
                durationSeconds,
                playbackUrl: program.playbackUrl,
                detail: segmentCount > 1
                    ? `${program.detail} Part ${part} of ${segmentCount} in the 90-second sponsor loop.`
                    : program.detail
            });
            remainingSeconds -= durationSeconds;
        }

        return segments;
    });
}

export function addSeconds(date, seconds) {
    return new Date(date.getTime() + seconds * 1000);
}

export function fallbackCampaignForSequence(sequence) {
    const normalized = Math.abs(Number.parseInt(sequence, 10) || 0);
    return FALLBACK_AD_CAMPAIGNS[normalized % FALLBACK_AD_CAMPAIGNS.length];
}

export function fallbackAdCycle(reference = new Date()) {
    const elapsedSeconds = Math.max(0, Math.floor((reference.getTime() - FALLBACK_AD_CYCLE_ORIGIN_MS) / 1000));
    const cycleSequence = Math.floor(elapsedSeconds / FALLBACK_AD_PROGRAM_CYCLE_SECONDS);
    const cycleStart = new Date(FALLBACK_AD_CYCLE_ORIGIN_MS + cycleSequence * FALLBACK_AD_PROGRAM_CYCLE_SECONDS * 1000);
    return { cycleSequence, cycleStart };
}

export function fallbackAdWindows(reference = new Date()) {
    const { cycleSequence, cycleStart } = fallbackAdCycle(reference);
    const windows = [];
    let cursor = cycleStart;

    for (let segmentIndex = 0; segmentIndex < FALLBACK_HOUSE_LOOP_SEGMENTS.length; segmentIndex += 1) {
        cursor = addSeconds(cursor, FALLBACK_HOUSE_LOOP_SEGMENTS[segmentIndex].durationSeconds);

        const start = cursor;
        const end = addSeconds(start, FALLBACK_AD_BREAK_DURATION_SECONDS);

        windows.push({
            sequence: cycleSequence * FALLBACK_AD_BREAK_COUNT + segmentIndex,
            slotIndex: segmentIndex,
            start,
            end
        });
        cursor = end;
    }

    return { cycleSequence, cycleStart, windows };
}

export function fallbackActiveOrNextAdWindow(reference = new Date()) {
    const current = fallbackAdWindows(reference);
    const activeWindow = current.windows.find((window) => reference >= window.start && reference < window.end) ?? null;
    const nextWindow = current.windows.find((window) => reference < window.start)
        ?? fallbackAdWindows(addSeconds(current.cycleStart, FALLBACK_AD_PROGRAM_CYCLE_SECONDS)).windows[0];

    return {
        cycleSequence: current.cycleSequence,
        cycleStart: current.cycleStart,
        activeWindow,
        scheduledWindow: activeWindow ?? nextWindow
    };
}

export function formatFallbackDurationLabel(seconds) {
    if (seconds % 60 === 0) {
        return `${seconds / 60}m`;
    }

    if (seconds > 60) {
        const minutes = Math.floor(seconds / 60);
        const remainder = seconds % 60;
        return `${minutes}m ${remainder}s`;
    }

    return `${seconds}s`;
}

export function buildDefaultAdStatus(reference = new Date()) {
    const { activeWindow, scheduledWindow } = fallbackActiveOrNextAdWindow(reference);
    const campaign = fallbackCampaignForSequence(scheduledWindow?.sequence ?? 0);
    const slotIndex = Number.isFinite(scheduledWindow?.slotIndex) ? scheduledWindow.slotIndex : 0;

    return {
        state: activeWindow ? "IN_BREAK" : "ARMED",
        podLabel: `Sponsor pod ${String.fromCharCode(65 + slotIndex)}`,
        sponsorLabel: campaign.sponsor,
        decisioningMode: "Server-side stitched pod",
        breakStartAt: scheduledWindow?.start?.toISOString?.() ?? "",
        breakEndAt: scheduledWindow?.end?.toISOString?.() ?? "",
        detail: activeWindow
            ? `${campaign.label} is active and the short sponsor clip is stitched into the house lineup right now.`
            : `${campaign.label} is armed for the next sponsor pod inside the house lineup.`
    };
}

export function buildFallbackAdProgramQueue({
    reference = new Date(),
    channelLabel = "Acme Network East",
    formatClock
} = {}) {
    if (typeof formatClock !== "function") {
        throw new TypeError("buildFallbackAdProgramQueue requires a formatClock function.");
    }

    const { cycleSequence, cycleStart } = fallbackAdCycle(reference);
    const entries = [];
    let cursor = cycleStart;

    for (let index = 0; index < FALLBACK_HOUSE_LOOP_SEGMENTS.length; index += 1) {
        const segment = FALLBACK_HOUSE_LOOP_SEGMENTS[index];
        const contentDurationSeconds = segment.durationSeconds;
        const contentEnd = addSeconds(cursor, contentDurationSeconds);
        const contentActive = reference >= cursor && reference < contentEnd;

        entries.push({
            entryId: `fallback-content-${cycleSequence}-${index}`,
            contentId: "",
            kind: "CONTENT",
            title: segment.title,
            channelLabel,
            slotLabel: `${formatClock(cursor)} - ${formatClock(contentEnd)}`,
            status: contentActive ? "NOW" : "QUEUED",
            detail: segment.detail,
            playbackUrl: segment.playbackUrl,
            durationLabel: formatFallbackDurationLabel(contentDurationSeconds)
        });
        cursor = contentEnd;

        const breakSequence = cycleSequence * FALLBACK_AD_BREAK_COUNT + index;
        const campaign = fallbackCampaignForSequence(breakSequence);
        const adEnd = addSeconds(cursor, FALLBACK_AD_BREAK_DURATION_SECONDS);
        const adActive = reference >= cursor && reference < adEnd;

        entries.push({
            entryId: `fallback-ad-${breakSequence}`,
            contentId: "",
            kind: "AD",
            title: campaign.sponsor,
            channelLabel,
            slotLabel: `${formatClock(cursor)} - ${formatClock(adEnd)}`,
            status: adActive ? "NOW" : "READY",
            detail: `${campaign.label} inserts the short sponsor clip at the next 90-second break.`,
            playbackUrl: SPONSOR_BREAK_PLAYBACK_URL,
            durationLabel: formatFallbackDurationLabel(FALLBACK_AD_BREAK_DURATION_SECONDS)
        });
        cursor = adEnd;
    }

    return entries;
}
