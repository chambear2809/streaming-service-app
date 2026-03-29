const runtimeConfig = window.STREAMING_CONFIG ?? {};

const elements = {
    title: document.querySelector("#broadcast-title"),
    detail: document.querySelector("#broadcast-detail"),
    channel: document.querySelector("#broadcast-channel"),
    status: document.querySelector("#broadcast-status"),
    statusCopy: document.querySelector("#broadcast-status-copy"),
    sourceType: document.querySelector("#broadcast-source-type"),
    sourceCopy: document.querySelector("#broadcast-source-copy"),
    adPod: document.querySelector("#broadcast-ad-pod"),
    adCopy: document.querySelector("#broadcast-ad-copy"),
    adMode: document.querySelector("#broadcast-ad-mode"),
    adModeCopy: document.querySelector("#broadcast-ad-mode-copy"),
    updated: document.querySelector("#broadcast-updated"),
    playbackUrl: document.querySelector("#broadcast-playback-url"),
    pageUrl: document.querySelector("#broadcast-page-url"),
    directLink: document.querySelector("#broadcast-direct-link"),
    presentationToggle: document.querySelector("#broadcast-presentation-toggle"),
    player: document.querySelector("#broadcast-player")
};

const state = {
    activePlaybackUrl: "",
    lastSuccessfulUpdatedLabel: ""
};

let presentationFallbackEnabled = false;
let hlsPlayer = null;
let reconnectTimer = null;
let stallRecoveryTimer = null;
let playbackWatchdogTimer = null;
let lastPlaybackProgressAt = 0;
let lastPlaybackTime = 0;
const BROADCAST_REFRESH_INTERVAL_MS = 5000;
const STALL_RECOVERY_DELAY_MS = 4000;
const PLAYBACK_WATCHDOG_INTERVAL_MS = 3000;
const PLAYBACK_STALL_THRESHOLD_MS = 5000;
const fallbackAdBreakSegmentSeconds = 180;
const fallbackAdBreakDurationSeconds = 15;
const fallbackHouseLoopPrograms = [
    { title: "Big Buck Bunny", durationSeconds: 597 },
    { title: "Elephants Dream", durationSeconds: 654 },
    { title: "Sintel", durationSeconds: 888 },
    { title: "Tears of Steel", durationSeconds: 734 }
];
const fallbackHouseLoopSegments = fallbackHouseLoopPrograms.flatMap((program) => {
    const segments = [];
    let remaining = program.durationSeconds;
    let part = 0;
    const segmentCount = Math.max(1, Math.ceil(program.durationSeconds / fallbackAdBreakSegmentSeconds));

    while (remaining > 0) {
        const durationSeconds = Math.min(fallbackAdBreakSegmentSeconds, remaining);
        part += 1;
        segments.push({
            title: segmentCount > 1 ? `${program.title} · Part ${part}` : program.title,
            durationSeconds
        });
        remaining -= durationSeconds;
    }

    return segments;
});
const fallbackAdBreakCount = fallbackHouseLoopSegments.length;
const fallbackAdProgramCycleSeconds = fallbackHouseLoopSegments.reduce((total, segment) => total + segment.durationSeconds, 0)
    + (fallbackAdBreakDurationSeconds * fallbackAdBreakCount);
const fallbackAdCycleOriginMs = Date.parse("2026-01-01T00:00:00Z");
const fallbackAdCampaigns = [
    { sponsor: "North Coast Sports Network", label: "Regional sports launch pod" },
    { sponsor: "Metro Weather Desk", label: "Storm desk weather sponsorship" },
    { sponsor: "City Arts Channel", label: "Festival takeover pod" },
    { sponsor: "Pop-Up Event East", label: "Opening-week sponsor activation" }
];

function resolveChannelLabel(label) {
    return String(label ?? "").trim() || runtimeConfig.defaultBroadcastChannelLabel || "Acme Network East";
}

function resolveBroadcastTitle(title) {
    return String(title ?? "").trim() || runtimeConfig.defaultBroadcastTitle || runtimeConfig.defaultBroadcastChannelLabel || "Acme Network East";
}

function resolveBroadcastDetail(detail) {
    return String(detail ?? "").trim()
        || runtimeConfig.defaultBroadcastDetail
        || "Big Buck Bunny, Elephants Dream, Sintel, and Tears of Steel rotate on the external channel with sponsor pods roughly every three minutes until a contribution feed is taken live.";
}

function addSeconds(date, seconds) {
    return new Date(date.getTime() + seconds * 1000);
}

function fallbackCampaignForSequence(sequence) {
    const normalized = Math.abs(Number.parseInt(sequence, 10) || 0);
    return fallbackAdCampaigns[normalized % fallbackAdCampaigns.length];
}

function fallbackAdCycle(reference = new Date()) {
    const elapsedSeconds = Math.max(0, Math.floor((reference.getTime() - fallbackAdCycleOriginMs) / 1000));
    const cycleSequence = Math.floor(elapsedSeconds / fallbackAdProgramCycleSeconds);
    const cycleStart = new Date(fallbackAdCycleOriginMs + cycleSequence * fallbackAdProgramCycleSeconds * 1000);
    return { cycleSequence, cycleStart };
}

function fallbackAdWindows(reference = new Date()) {
    const { cycleSequence, cycleStart } = fallbackAdCycle(reference);
    const windows = [];
    let cursor = cycleStart;
    for (let segmentIndex = 0; segmentIndex < fallbackHouseLoopSegments.length; segmentIndex += 1) {
        cursor = addSeconds(cursor, fallbackHouseLoopSegments[segmentIndex].durationSeconds);

        const start = cursor;
        const end = addSeconds(start, fallbackAdBreakDurationSeconds);
        windows.push({
            sequence: cycleSequence * fallbackAdBreakCount + segmentIndex,
            slotIndex: segmentIndex,
            start,
            end
        });
        cursor = end;
    }

    return { cycleSequence, cycleStart, windows };
}

function fallbackActiveOrNextAdWindow(reference = new Date()) {
    const current = fallbackAdWindows(reference);
    const activeWindow = current.windows.find((window) => reference >= window.start && reference < window.end) ?? null;
    const nextWindow = current.windows.find((window) => reference < window.start)
        ?? fallbackAdWindows(addSeconds(current.cycleStart, fallbackAdProgramCycleSeconds)).windows[0];

    return {
        activeWindow,
        scheduledWindow: activeWindow ?? nextWindow
    };
}

function fallbackAdStatus() {
    const { activeWindow, scheduledWindow } = fallbackActiveOrNextAdWindow();
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

function absoluteUrl(path) {
    try {
        return new URL(path || "/", window.location.origin).href;
    } catch (_error) {
        return path || "/";
    }
}

function formatStamp(dateLike) {
    const date = new Date(dateLike);
    if (Number.isNaN(date.getTime())) {
        return "Unavailable";
    }

    return date.toLocaleString([], {
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit"
    });
}

function presentationModeActive() {
    return Boolean(document.fullscreenElement) || presentationFallbackEnabled;
}

function syncPresentationMode() {
    const active = presentationModeActive();

    document.body.classList.toggle("is-presentation-mode", active);
    elements.presentationToggle.setAttribute("aria-pressed", String(active));
    elements.presentationToggle.textContent = active ? "Exit fullscreen" : "Presentation mode";
}

function markBroadcastStatusStale() {
    const hasSnapshot = Boolean(state.lastSuccessfulUpdatedLabel);
    const fallbackAd = fallbackAdStatus();

    elements.status.textContent = hasSnapshot ? "Status stale" : "Unavailable";
    elements.statusCopy.textContent = hasSnapshot
        ? `Live metadata refresh failed. Showing the last known broadcast snapshot from ${state.lastSuccessfulUpdatedLabel}.`
        : "The external distribution feed could not be loaded.";
    elements.updated.textContent = hasSnapshot ? state.lastSuccessfulUpdatedLabel : "Unavailable";
    elements.sourceType.textContent = hasSnapshot ? "Previous feed snapshot" : "House lineup";
    elements.sourceCopy.textContent = hasSnapshot
        ? "The status endpoint did not refresh successfully, so treat the current source and sponsor details on this page as stale until the next successful poll."
        : resolveBroadcastDetail(runtimeConfig.defaultBroadcastDetail);
    elements.adPod.textContent = `${fallbackAd.podLabel} · ${fallbackAd.sponsorLabel}`;
    elements.adCopy.textContent = fallbackAd.detail;
    elements.adMode.textContent = hasSnapshot ? "Snapshot only" : "Program feed";
    elements.adModeCopy.textContent = hasSnapshot
        ? "Sponsor timing and transport metadata are showing the last successful refresh."
        : `${fallbackAd.decisioningMode} · ${formatStamp(fallbackAd.breakStartAt)} - ${formatStamp(fallbackAd.breakEndAt)}`;
}

async function togglePresentationMode() {
    const root = document.documentElement;

    if (!document.fullscreenEnabled || typeof root.requestFullscreen !== "function") {
        presentationFallbackEnabled = !presentationFallbackEnabled;
        syncPresentationMode();
        return;
    }

    try {
        if (document.fullscreenElement) {
            await document.exitFullscreen();
        } else {
            presentationFallbackEnabled = false;
            await root.requestFullscreen();
        }
    } catch (error) {
        console.warn("Unable to toggle broadcast presentation mode.", error);
        presentationFallbackEnabled = !presentationFallbackEnabled;
    }

    syncPresentationMode();
}

function resetPlayerSource() {
    if (reconnectTimer) {
        window.clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    if (stallRecoveryTimer) {
        window.clearTimeout(stallRecoveryTimer);
        stallRecoveryTimer = null;
    }

    if (hlsPlayer) {
        hlsPlayer.destroy();
        hlsPlayer = null;
    }

    elements.player.pause();
    elements.player.removeAttribute("src");
    while (elements.player.firstChild) {
        elements.player.removeChild(elements.player.firstChild);
    }
    elements.player.load();
}

function markPlaybackProgress() {
    lastPlaybackTime = elements.player.currentTime || 0;
    lastPlaybackProgressAt = Date.now();
}

function clearStallRecoveryTimer() {
    if (!stallRecoveryTimer) {
        return;
    }

    window.clearTimeout(stallRecoveryTimer);
    stallRecoveryTimer = null;
}

function scheduleStallRecovery(reason) {
    if (!state.activePlaybackUrl || !isLikelyHlsSource(state.activePlaybackUrl)) {
        return;
    }

    if (stallRecoveryTimer) {
        return;
    }

    elements.statusCopy.textContent = reason;
    stallRecoveryTimer = window.setTimeout(() => {
        stallRecoveryTimer = null;
        elements.statusCopy.textContent = "The external live feed stalled during playout. Reconnecting...";
        scheduleReconnect(state.activePlaybackUrl, 250);
    }, STALL_RECOVERY_DELAY_MS);
}

function ensurePlaybackWatchdog() {
    if (playbackWatchdogTimer) {
        return;
    }

    playbackWatchdogTimer = window.setInterval(() => {
        if (!state.activePlaybackUrl || !isLikelyHlsSource(state.activePlaybackUrl)) {
            return;
        }

        if (elements.player.paused || elements.player.ended) {
            return;
        }

        const currentTime = elements.player.currentTime || 0;
        const now = Date.now();
        const timeAdvanced = Math.abs(currentTime - lastPlaybackTime) > 0.2;
        if (timeAdvanced) {
            markPlaybackProgress();
            clearStallRecoveryTimer();
            return;
        }

        const lowReadyState = elements.player.readyState < HTMLMediaElement.HAVE_FUTURE_DATA;
        const stalledTooLong = lastPlaybackProgressAt > 0 && now - lastPlaybackProgressAt >= PLAYBACK_STALL_THRESHOLD_MS;
        if (lowReadyState || stalledTooLong) {
            scheduleStallRecovery("The external live feed is buffering. Recovering the player...");
        }
    }, PLAYBACK_WATCHDOG_INTERVAL_MS);
}

function isLikelyHlsSource(url) {
    const normalized = String(url ?? "").trim().toLowerCase();
    return normalized.endsWith(".m3u8") || normalized.includes("/broadcast/live/");
}

function scheduleReconnect(sourceUrl, delayMs = 1500) {
    if (!sourceUrl) {
        return;
    }

    if (reconnectTimer) {
        window.clearTimeout(reconnectTimer);
    }

    reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        state.activePlaybackUrl = "";
        updatePlayer(sourceUrl);
    }, delayMs);
}

function loadHlsPlayerSource(sourceUrl) {
    if (window.Hls?.isSupported?.()) {
        hlsPlayer = new window.Hls({
            enableWorker: true,
            lowLatencyMode: false
        });
        hlsPlayer.on(window.Hls.Events.ERROR, (_event, data) => {
            if (!data?.fatal) {
                if (data?.details === window.Hls.ErrorDetails.BUFFER_STALLED_ERROR) {
                    scheduleStallRecovery("The external live feed is buffering. Recovering the player...");
                }
                return;
            }

            console.warn("External HLS feed failed to load.", data);
            elements.statusCopy.textContent = "The external live feed dropped during playout. Reconnecting...";

            if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && hlsPlayer) {
                hlsPlayer.recoverMediaError();
                return;
            }

            if (hlsPlayer) {
                hlsPlayer.destroy();
                hlsPlayer = null;
            }
            scheduleReconnect(sourceUrl);
        });
        hlsPlayer.loadSource(sourceUrl);
        hlsPlayer.attachMedia(elements.player);
        return true;
    }

    if (elements.player.canPlayType("application/vnd.apple.mpegurl") || elements.player.canPlayType("application/x-mpegURL")) {
        elements.player.src = sourceUrl;
        elements.player.load();
        return true;
    }

    return false;
}

function updatePlayer(playbackUrl) {
    const resolvedUrl = absoluteUrl(playbackUrl);
    const needsRecovery = isLikelyHlsSource(resolvedUrl) && (!hlsPlayer || elements.player.ended);
    if (!playbackUrl || (state.activePlaybackUrl === resolvedUrl && !needsRecovery)) {
        return;
    }

    state.activePlaybackUrl = resolvedUrl;
    resetPlayerSource();
    markPlaybackProgress();
    ensurePlaybackWatchdog();

    if (isLikelyHlsSource(resolvedUrl)) {
        if (!loadHlsPlayerSource(resolvedUrl)) {
            elements.statusCopy.textContent = "This browser cannot open the live HLS feed.";
            return;
        }
    } else {
        elements.player.src = resolvedUrl;
        elements.player.load();
    }

    elements.player.play().catch(() => {});
}

async function refreshBroadcast() {
    const statusUrl = runtimeConfig.publicBroadcastStatusUrl ?? "/api/v1/demo/public/broadcast/current";
    const response = await fetch(statusUrl, {
        cache: "no-store"
    });

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
    }

    const payload = await response.json();
    const broadcastPageUrl = absoluteUrl(payload?.publicPageUrl ?? runtimeConfig.publicBroadcastPageUrl ?? "/broadcast");
    const publicPlaybackUrl = payload?.publicPlaybackUrl ?? runtimeConfig.publicBroadcastPlaybackUrl ?? "/api/v1/demo/public/broadcast/live/index.m3u8";
    const status = String(payload?.status ?? "DEMO_LOOP").toUpperCase();
    const statusLabel = status === "ON_AIR" ? "On air" : "House loop";
    const sourceType = status === "ON_AIR" ? "RTSP contribution" : "House lineup";
    const adStatus = payload?.adStatus ?? {};
    const adState = String(adStatus?.state ?? "ARMED").toUpperCase();
    const adBreakActive = adState === "IN_BREAK";
    const updatedLabel = formatStamp(payload?.updatedAt);

    elements.title.textContent = resolveBroadcastTitle(payload?.title);
    elements.detail.textContent = resolveBroadcastDetail(payload?.detail);
    elements.channel.textContent = resolveChannelLabel(payload?.channelLabel);
    elements.status.textContent = statusLabel;
    elements.statusCopy.textContent = resolveBroadcastDetail(payload?.detail) || (status === "ON_AIR"
        ? "Master control has routed an active RTSP contribution into the external channel."
        : "The channel is carrying the house lineup until a contribution source is taken live.");
    elements.sourceType.textContent = sourceType;
    elements.sourceCopy.textContent = payload?.detail ?? "The current source detail is unavailable.";
    elements.adPod.textContent = `${adStatus?.podLabel ?? "Sponsor pod A"} · ${adStatus?.sponsorLabel ?? "North Coast Sports Network"}`;
    elements.adCopy.textContent = adStatus?.detail ?? "The current sponsor pod detail is unavailable.";
    elements.adMode.textContent = adBreakActive ? "Sponsor break live" : "Program feed";
    elements.adModeCopy.textContent = `${adStatus?.decisioningMode ?? "Server-side stitched pod"} · ${formatStamp(adStatus?.breakStartAt)} - ${formatStamp(adStatus?.breakEndAt)}`;
    elements.updated.textContent = updatedLabel;
    elements.playbackUrl.textContent = absoluteUrl(publicPlaybackUrl);
    elements.pageUrl.textContent = broadcastPageUrl;
    elements.directLink.href = absoluteUrl(publicPlaybackUrl);
    state.lastSuccessfulUpdatedLabel = updatedLabel === "Unavailable" ? "" : updatedLabel;
    updatePlayer(publicPlaybackUrl);
}

async function bootstrap() {
    syncPresentationMode();

    try {
        await refreshBroadcast();
    } catch (error) {
        console.warn("Unable to load the external distribution feed.", error);
        markBroadcastStatusStale();
    }

    window.setInterval(() => {
        refreshBroadcast().catch((error) => {
            console.warn("Unable to refresh the external distribution feed.", error);
            markBroadcastStatusStale();
        });
    }, BROADCAST_REFRESH_INTERVAL_MS);
}

elements.presentationToggle.addEventListener("click", () => {
    togglePresentationMode();
});
window.addEventListener("focus", () => {
    refreshBroadcast().catch((error) => {
        console.warn("Unable to refresh the external distribution feed.", error);
        markBroadcastStatusStale();
    });
});
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible") {
        return;
    }
    refreshBroadcast().catch((error) => {
        console.warn("Unable to refresh the external distribution feed.", error);
        markBroadcastStatusStale();
    });
});
document.addEventListener("fullscreenchange", () => {
    if (!document.fullscreenElement) {
        presentationFallbackEnabled = false;
    }
    syncPresentationMode();
});
elements.player.addEventListener("ended", () => {
    if (!state.activePlaybackUrl || !isLikelyHlsSource(state.activePlaybackUrl)) {
        return;
    }

    elements.statusCopy.textContent = "The external live feed reached a playout boundary. Reconnecting...";
    scheduleReconnect(state.activePlaybackUrl, 500);
});
elements.player.addEventListener("playing", () => {
    markPlaybackProgress();
    clearStallRecoveryTimer();
});
elements.player.addEventListener("timeupdate", () => {
    markPlaybackProgress();
    clearStallRecoveryTimer();
});
elements.player.addEventListener("waiting", () => {
    scheduleStallRecovery("The external live feed is buffering. Recovering the player...");
});
elements.player.addEventListener("stalled", () => {
    scheduleStallRecovery("The external live feed stalled during playout. Recovering the player...");
});
elements.player.addEventListener("error", () => {
    scheduleStallRecovery("The external live feed hit a playback error. Recovering the player...");
});

bootstrap().catch((error) => {
    console.warn("External distribution bootstrap failed.", error);
});
