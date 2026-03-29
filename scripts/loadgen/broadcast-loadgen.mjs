#!/usr/bin/env node

import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

const HELP_TEXT = `Broadcast load generator

Usage:
  node scripts/loadgen/broadcast-loadgen.mjs [options]

The script simulates viewer sessions against the public broadcast page and HLS
playback endpoints. CLI flags override LOADGEN_* environment variables.

Options:
  --base-url <url>                 Frontend base URL.
  --page-path <path>               Public page path. Default: /broadcast
  --status-path <path>             Broadcast status path. Default: /api/v1/demo/public/broadcast/current
  --playlist-path <path>           Fallback playback path. Default: /api/v1/demo/public/broadcast/live/index.m3u8
  --trace-map-path <path>          Trace-map path. Default: /api/v1/demo/public/trace-map
  --target-viewers <count>         Peak concurrent viewers. Default: 60
  --duration <time>                Total scenario duration. Default: 10m
  --ramp-up <time>                 Ramp-up duration. Default: 2m
  --ramp-down <time>               Ramp-down duration. Default: 1m
  --session-min <time>             Minimum session length. Default: 90s
  --session-max <time>             Maximum session length. Default: 4m
  --page-viewer-ratio <ratio>      Fraction of sessions that fetch the broadcast page and assets. Default: 0.35
  --trace-map-session-ratio <ratio>
                                   Fraction of sessions that pivot through trace-map once. Default: 0.10
  --status-poll-interval <time>    Public status polling interval. Default: 5s
  --request-timeout <time>         Per-request timeout. Default: 15s
  --playlist-poll-floor <time>     Minimum media-playlist refresh interval. Default: 1s
  --playlist-poll-ceiling <time>   Maximum media-playlist refresh interval. Default: 6s
  --variant-strategy <mode>        balanced | highest | lowest | random. Default: balanced
  --live-edge-segments <count>     Full media segments to follow near the live edge. Default: 1
  --live-edge-parts <count>        LL-HLS partial segments to follow near the live edge. Default: 8
  --max-playback-errors <count>    Consecutive playback refresh misses before a session fails. Default: 6
  --log-every <time>               Progress log interval. Default: 5s
  --vod-loop <true|false>          Re-read static playlists when they reach ENDLIST. Default: true
  --help                           Show this help text.

Environment variable equivalents:
  LOADGEN_BASE_URL
  LOADGEN_PAGE_PATH
  LOADGEN_STATUS_PATH
  LOADGEN_PLAYLIST_PATH
  LOADGEN_TRACE_MAP_PATH
  LOADGEN_TARGET_VIEWERS
  LOADGEN_DURATION
  LOADGEN_RAMP_UP
  LOADGEN_RAMP_DOWN
  LOADGEN_SESSION_MIN
  LOADGEN_SESSION_MAX
  LOADGEN_PAGE_VIEWER_RATIO
  LOADGEN_TRACE_MAP_SESSION_RATIO
  LOADGEN_STATUS_POLL_INTERVAL
  LOADGEN_REQUEST_TIMEOUT
  LOADGEN_PLAYLIST_POLL_FLOOR
  LOADGEN_PLAYLIST_POLL_CEILING
  LOADGEN_VARIANT_STRATEGY
  LOADGEN_LIVE_EDGE_SEGMENTS
  LOADGEN_LIVE_EDGE_PARTS
  LOADGEN_MAX_PLAYBACK_ERRORS
  LOADGEN_LOG_EVERY
  LOADGEN_VOD_LOOP
`;

const REQUEST_KIND_ORDER = ["page", "asset", "status", "trace", "playlist", "segment"];

async function main() {
    const config = parseConfig(process.argv.slice(2), process.env);
    if (config.helpRequested) {
        console.log(HELP_TEXT);
        return;
    }

    validateConfig(config);
    printScenario(config);

    const metrics = new Metrics();
    const runner = new ScenarioRunner(config, metrics);
    await runner.run();
    printSummary(metrics);
}

class ScenarioRunner {
    constructor(config, metrics) {
        this.config = config;
        this.metrics = metrics;
        this.activeSessions = new Map();
        this.nextSessionId = 1;
    }

    async run() {
        const startedAt = Date.now();
        const endsAt = startedAt + this.config.durationMs;
        const logTimer = setInterval(() => {
            printProgress(this.config, this.metrics, startedAt, endsAt, this.activeSessions.size);
        }, this.config.logEveryMs);

        try {
            while (Date.now() < endsAt) {
                const target = computeTargetViewerCount(this.config, startedAt, endsAt, Date.now());
                while (this.activeSessions.size < target) {
                    this.launchSession(endsAt);
                }
                await sleep(250);
            }

            const drainDeadline = Date.now() + Math.min(30_000, this.config.requestTimeoutMs + 5_000);
            while (this.activeSessions.size > 0 && Date.now() < drainDeadline) {
                await sleep(250);
            }
        } finally {
            clearInterval(logTimer);
        }
    }

    launchSession(scenarioEndsAt) {
        const sessionId = this.nextSessionId++;
        const promise = runViewerSession(sessionId, this.config, this.metrics, scenarioEndsAt)
            .finally(() => {
                this.activeSessions.delete(sessionId);
            });
        this.activeSessions.set(sessionId, promise);
    }
}

async function runViewerSession(_sessionId, config, metrics, scenarioEndsAt) {
    const startedAt = Date.now();
    const sessionDurationMs = randomBetween(config.sessionMinMs, config.sessionMaxMs);
    const endsAt = Math.min(startedAt + sessionDurationMs, scenarioEndsAt);
    const pageViewer = Math.random() < config.pageViewerRatio;
    const traceViewer = Math.random() < config.traceMapSessionRatio;
    const sessionState = {
        startedAt,
        playbackUrl: resolveUrl(config.baseUrl, config.playlistPath),
        startupLatencyRecorded: false,
        trackStates: new Map(),
        consecutivePlaybackErrors: 0
    };

    metrics.recordSessionStart(pageViewer, traceViewer);

    try {
        if (pageViewer) {
            await fetchPageShell(config, metrics);
        }

        const initialStatus = await fetchBroadcastStatus(sessionState, config, metrics);
        if (initialStatus?.playbackUrl) {
            sessionState.playbackUrl = initialStatus.playbackUrl;
        }

        if (traceViewer) {
            try {
                await fetchJsonEndpoint(resolveUrl(config.baseUrl, config.traceMapPath), "trace", config, metrics);
            } catch {
                // Trace pivots are a minority slice of the traffic mix. Do not fail the viewer on a trace miss.
            }
        }

        const tasks = [
            playbackLoop(sessionState, config, metrics, endsAt)
        ];

        if (pageViewer) {
            tasks.push(statusPollingLoop(sessionState, config, metrics, endsAt));
        }

        await Promise.all(tasks);
        metrics.recordSessionComplete();
    } catch (error) {
        metrics.recordSessionFailure(error);
    }
}

async function fetchPageShell(config, metrics) {
    const pageUrl = resolveUrl(config.baseUrl, config.pagePath);
    const pageResponse = await fetchTextEndpoint(pageUrl, "page", config, metrics, {
        accept: "text/html,application/xhtml+xml"
    });

    if (!pageResponse.ok) {
        throw new Error(`Broadcast page returned HTTP ${pageResponse.status}.`);
    }

    const assets = extractLocalAssetUrls(pageResponse.text, pageResponse.url);
    if (assets.length === 0) {
        return;
    }

    await Promise.all(
        assets.map((assetUrl) => attemptFetchBinary(assetUrl, "asset", config, metrics))
    );
}

async function fetchBroadcastStatus(sessionState, config, metrics) {
    const statusUrl = resolveUrl(config.baseUrl, config.statusPath);
    let response;

    try {
        response = await fetchJsonEndpoint(statusUrl, "status", config, metrics);
    } catch {
        return null;
    }

    if (!response.ok || !response.payload || typeof response.payload !== "object") {
        return null;
    }

    const publicPlaybackUrl = response.payload.publicPlaybackUrl
        ? resolveUrl(config.baseUrl, response.payload.publicPlaybackUrl)
        : null;

    if (publicPlaybackUrl) {
        sessionState.playbackUrl = publicPlaybackUrl;
    }

    return {
        playbackUrl: publicPlaybackUrl,
        payload: response.payload
    };
}

async function statusPollingLoop(sessionState, config, metrics, endsAt) {
    while (Date.now() < endsAt) {
        await sleep(config.statusPollIntervalMs);
        if (Date.now() >= endsAt) {
            return;
        }

        try {
            await fetchBroadcastStatus(sessionState, config, metrics);
        } catch {
            // Keep the viewer alive if status polling hiccups.
        }
    }
}

async function playbackLoop(sessionState, config, metrics, endsAt) {
    let activeEntryUrl = "";

    while (Date.now() < endsAt) {
        const desiredUrl = sessionState.playbackUrl;
        if (!desiredUrl) {
            await sleep(250);
            continue;
        }

        if (desiredUrl !== activeEntryUrl) {
            activeEntryUrl = desiredUrl;
            sessionState.trackStates.clear();
            sessionState.consecutivePlaybackErrors = 0;
        }

        const iteration = await refreshPlaybackState(activeEntryUrl, sessionState, config, metrics, endsAt);

        if (iteration.successfulAssets > 0) {
            sessionState.consecutivePlaybackErrors = 0;
            if (!sessionState.startupLatencyRecorded) {
                metrics.recordStartupLatency(Date.now() - sessionState.startedAt);
                sessionState.startupLatencyRecorded = true;
            }
        } else if (iteration.hadRecoverableIssue) {
            sessionState.consecutivePlaybackErrors += 1;
            if (sessionState.consecutivePlaybackErrors >= config.maxPlaybackErrors) {
                throw new Error(`Playback could not recover after ${sessionState.consecutivePlaybackErrors} consecutive refreshes.`);
            }
        }

        await sleep(iteration.pollIntervalMs);
    }
}

async function refreshPlaybackState(entryUrl, sessionState, config, metrics, endsAt) {
    const playlistResponse = await attemptFetchText(entryUrl, "playlist", config, metrics, {
        accept: "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain;q=0.9,*/*;q=0.1"
    });

    if (!playlistResponse || !playlistResponse.ok) {
        return {
            successfulAssets: 0,
            hadRecoverableIssue: true,
            pollIntervalMs: config.playlistPollFloorMs
        };
    }

    const parsed = parsePlaylist(playlistResponse.text, playlistResponse.url);
    const tracks = parsed.type === "master"
        ? resolvePlaybackTracks(parsed, config)
        : [{ key: `primary:${canonicalAssetKey(playlistResponse.url)}`, url: playlistResponse.url }];

    if (tracks.length === 0) {
        return {
            successfulAssets: 0,
            hadRecoverableIssue: true,
            pollIntervalMs: config.playlistPollFloorMs
        };
    }

    let successfulAssets = 0;
    let hadRecoverableIssue = false;
    let pollIntervalMs = parsed.type === "media"
        ? computePlaylistPollIntervalMs(parsed, config)
        : config.playlistPollFloorMs;

    for (const track of tracks) {
        if (Date.now() >= endsAt) {
            break;
        }

        const trackState = ensureTrackState(sessionState.trackStates, track.key);
        const outcome = await refreshPlaybackTrack(track, trackState, config, metrics);
        successfulAssets += outcome.successfulAssets;
        hadRecoverableIssue = hadRecoverableIssue || outcome.hadRecoverableIssue;
        pollIntervalMs = Math.min(pollIntervalMs, outcome.pollIntervalMs);
    }

    return {
        successfulAssets,
        hadRecoverableIssue,
        pollIntervalMs
    };
}

async function refreshPlaybackTrack(track, trackState, config, metrics) {
    const playlistResponse = await attemptFetchText(track.url, "playlist", config, metrics, {
        accept: "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain;q=0.9,*/*;q=0.1"
    });

    if (!playlistResponse || !playlistResponse.ok) {
        return {
            successfulAssets: 0,
            hadRecoverableIssue: true,
            pollIntervalMs: config.playlistPollFloorMs
        };
    }

    const parsed = parsePlaylist(playlistResponse.text, playlistResponse.url);
    if (parsed.type === "master") {
        return {
            successfulAssets: 0,
            hadRecoverableIssue: true,
            pollIntervalMs: config.playlistPollFloorMs
        };
    }

    const assetQueue = planPlaybackAssets(parsed, trackState, config);
    let successfulAssets = 0;
    let hadRecoverableIssue = false;

    for (const asset of assetQueue) {
        const response = await attemptFetchBinary(asset.url, "segment", config, metrics, {
            accept: "video/mp4,video/mp2t,application/octet-stream;q=0.9,*/*;q=0.1"
        });

        if (!response) {
            hadRecoverableIssue = true;
            continue;
        }

        if (!response.ok) {
            if (isRecoverablePlaybackStatus(response.status)) {
                hadRecoverableIssue = true;
                continue;
            }

            hadRecoverableIssue = true;
            continue;
        }

        trackState.seenAssets.add(asset.key);
        if (asset.kind === "map") {
            trackState.loadedMapKey = asset.key;
        }
        successfulAssets += 1;
    }

    if (parsed.endList && config.vodLoop) {
        trackState.started = false;
        trackState.loadedMapKey = "";
        trackState.seenAssets.clear();
    }

    return {
        successfulAssets,
        hadRecoverableIssue,
        pollIntervalMs: computePlaylistPollIntervalMs(parsed, config)
    };
}

function planPlaybackAssets(parsed, trackState, config) {
    const assets = [];

    if (parsed.mapUrl) {
        const mapKey = canonicalAssetKey(parsed.mapUrl);
        if (trackState.loadedMapKey !== mapKey && !trackState.seenAssets.has(mapKey)) {
            assets.push({
                kind: "map",
                url: parsed.mapUrl,
                key: mapKey
            });
        }
    }

    const segmentTail = parsed.segments.slice(-Math.max(1, config.liveEdgeSegments));
    const partTailSize = trackState.started ? config.liveEdgeParts * 2 : config.liveEdgeParts;
    const partTail = parsed.parts.slice(-Math.max(1, partTailSize));
    const segmentCandidates = trackState.started ? segmentTail : segmentTail.slice(-1);
    const partCandidates = trackState.started ? partTail : partTail.slice(-Math.max(1, config.liveEdgeParts));

    for (const segment of segmentCandidates) {
        if (!trackState.seenAssets.has(segment.key)) {
            assets.push({
                kind: "segment",
                url: segment.url,
                key: segment.key
            });
        }
    }

    for (const part of partCandidates) {
        if (!trackState.seenAssets.has(part.key)) {
            assets.push({
                kind: "part",
                url: part.url,
                key: part.key
            });
        }
    }

    trackState.started = true;
    return assets;
}

function resolvePlaybackTracks(parsedMaster, config) {
    if (parsedMaster.variants.length === 0) {
        return [];
    }

    const variant = chooseVariant(parsedMaster.variants, config.variantStrategy);
    const tracks = [{
        key: `video:${canonicalAssetKey(variant.url)}`,
        url: variant.url
    }];

    if (variant.audioGroupId) {
        const audio = chooseAudioRendition(parsedMaster.audioRenditions, variant.audioGroupId);
        if (audio) {
            tracks.push({
                key: `audio:${canonicalAssetKey(audio.url)}`,
                url: audio.url
            });
        }
    }

    return tracks;
}

function chooseAudioRendition(renditions, groupId) {
    const candidates = renditions.filter((rendition) => rendition.groupId === groupId);
    if (candidates.length === 0) {
        return null;
    }

    return candidates.find((rendition) => rendition.default) ?? candidates[0];
}

function ensureTrackState(trackStates, key) {
    if (!trackStates.has(key)) {
        trackStates.set(key, {
            started: false,
            loadedMapKey: "",
            seenAssets: new RollingSet(4096)
        });
    }

    return trackStates.get(key);
}

async function fetchJsonEndpoint(url, kind, config, metrics) {
    const response = await fetchTextEndpoint(url, kind, config, metrics, {
        accept: "application/json,text/plain;q=0.5,*/*;q=0.1"
    });

    if (!response.ok) {
        return { ...response, payload: null };
    }

    try {
        return { ...response, payload: JSON.parse(response.text) };
    } catch (error) {
        metrics.recordError(kind, `invalid-json:${error.message}`);
        return { ...response, payload: null };
    }
}

async function fetchTextEndpoint(url, kind, config, metrics, extraHeaders = {}) {
    return withMetrics(kind, config, metrics, url, extraHeaders, async (response) => {
        const { text, bytes } = await readTextBody(response);
        return { bytes, text };
    });
}

async function fetchBinaryEndpoint(url, kind, config, metrics, extraHeaders = {}) {
    return withMetrics(kind, config, metrics, url, extraHeaders, async (response) => {
        const { bytes } = await drainBody(response);
        return { bytes };
    });
}

async function attemptFetchText(url, kind, config, metrics, extraHeaders = {}) {
    try {
        return await fetchTextEndpoint(url, kind, config, metrics, extraHeaders);
    } catch {
        return null;
    }
}

async function attemptFetchBinary(url, kind, config, metrics, extraHeaders = {}) {
    try {
        return await fetchBinaryEndpoint(url, kind, config, metrics, extraHeaders);
    } catch {
        return null;
    }
}

async function withMetrics(kind, config, metrics, url, extraHeaders, consumeResponse) {
    const startedAt = Date.now();

    try {
        const response = await fetch(url, {
            headers: {
                "user-agent": config.userAgent,
                ...extraHeaders
            },
            signal: AbortSignal.timeout(config.requestTimeoutMs),
            redirect: "follow"
        });

        const consumed = await consumeResponse(response);
        metrics.recordRequest(kind, response.status, Date.now() - startedAt, consumed.bytes);
        return {
            ok: response.ok,
            status: response.status,
            headers: response.headers,
            url: response.url,
            ...consumed
        };
    } catch (error) {
        metrics.recordRequestError(kind, Date.now() - startedAt, error);
        throw error;
    }
}

async function readTextBody(response) {
    if (!response.body) {
        return { bytes: 0, text: "" };
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let bytes = 0;
    let text = "";

    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            break;
        }
        bytes += value.byteLength;
        text += decoder.decode(value, { stream: true });
    }

    text += decoder.decode();
    return { bytes, text };
}

async function drainBody(response) {
    if (!response.body) {
        return { bytes: 0 };
    }

    const reader = response.body.getReader();
    let bytes = 0;

    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            break;
        }
        bytes += value.byteLength;
    }

    return { bytes };
}

function parsePlaylist(text, baseUrl) {
    const lines = String(text ?? "")
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean);

    const variants = [];
    const audioRenditions = [];
    const segments = [];
    const parts = [];
    let pendingStreamAttributes = null;
    let pendingSegmentDuration = null;
    let targetDurationSeconds = 4;
    let partTargetDurationSeconds = null;
    let endList = false;
    let mapUrl = "";

    for (const line of lines) {
        if (line.startsWith("#EXT-X-MEDIA:")) {
            const attributes = parseAttributeList(line.slice("#EXT-X-MEDIA:".length));
            if (String(attributes.TYPE ?? "").toUpperCase() === "AUDIO" && attributes.URI) {
                audioRenditions.push({
                    groupId: attributes["GROUP-ID"] ?? "",
                    name: attributes.NAME ?? "",
                    default: String(attributes.DEFAULT ?? "").toUpperCase() === "YES",
                    url: new URL(attributes.URI, baseUrl).href
                });
            }
            continue;
        }

        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            pendingStreamAttributes = parseAttributeList(line.slice("#EXT-X-STREAM-INF:".length));
            continue;
        }

        if (line.startsWith("#EXT-X-PART-INF:")) {
            const attributes = parseAttributeList(line.slice("#EXT-X-PART-INF:".length));
            const rawValue = Number.parseFloat(attributes["PART-TARGET"] ?? "0");
            if (Number.isFinite(rawValue) && rawValue > 0) {
                partTargetDurationSeconds = rawValue;
            }
            continue;
        }

        if (line.startsWith("#EXT-X-TARGETDURATION:")) {
            const rawValue = Number.parseInt(line.slice("#EXT-X-TARGETDURATION:".length), 10);
            if (Number.isFinite(rawValue) && rawValue > 0) {
                targetDurationSeconds = rawValue;
            }
            continue;
        }

        if (line.startsWith("#EXT-X-MAP:")) {
            const attributes = parseAttributeList(line.slice("#EXT-X-MAP:".length));
            if (attributes.URI) {
                mapUrl = new URL(attributes.URI, baseUrl).href;
            }
            continue;
        }

        if (line.startsWith("#EXT-X-PART:")) {
            const attributes = parseAttributeList(line.slice("#EXT-X-PART:".length));
            if (attributes.URI) {
                const absoluteUrl = new URL(attributes.URI, baseUrl).href;
                parts.push({
                    url: absoluteUrl,
                    key: canonicalAssetKey(absoluteUrl),
                    durationSeconds: Number.parseFloat(attributes.DURATION ?? "0") || null
                });
            }
            continue;
        }

        if (line.startsWith("#EXTINF:")) {
            const rawDuration = Number.parseFloat(line.slice("#EXTINF:".length).split(",")[0]);
            pendingSegmentDuration = Number.isFinite(rawDuration) ? rawDuration : null;
            continue;
        }

        if (line === "#EXT-X-ENDLIST") {
            endList = true;
            continue;
        }

        if (line.startsWith("#")) {
            continue;
        }

        const absoluteUrl = new URL(line, baseUrl).href;
        if (pendingStreamAttributes) {
            variants.push({
                url: absoluteUrl,
                bandwidth: Number.parseInt(pendingStreamAttributes.BANDWIDTH ?? "0", 10) || 0,
                resolution: pendingStreamAttributes.RESOLUTION ?? "",
                audioGroupId: pendingStreamAttributes.AUDIO ?? ""
            });
            pendingStreamAttributes = null;
            continue;
        }

        segments.push({
            url: absoluteUrl,
            key: canonicalAssetKey(absoluteUrl),
            durationSeconds: pendingSegmentDuration
        });
        pendingSegmentDuration = null;
    }

    if (variants.length > 0) {
        return {
            type: "master",
            variants,
            audioRenditions
        };
    }

    return {
        type: "media",
        endList,
        targetDurationSeconds,
        partTargetDurationSeconds,
        mapUrl,
        segments,
        parts
    };
}

function chooseVariant(variants, strategy) {
    const sorted = [...variants].sort((left, right) => left.bandwidth - right.bandwidth);
    if (strategy === "highest") {
        return sorted.at(-1);
    }
    if (strategy === "lowest") {
        return sorted[0];
    }
    if (strategy === "random") {
        return sorted[randomIndex(sorted.length)];
    }

    if (sorted.length === 1) {
        return sorted[0];
    }

    const middleIndex = Math.floor((sorted.length - 1) / 2);
    const draw = Math.random();
    if (draw < 0.2) {
        return sorted[0];
    }
    if (draw < 0.8) {
        return sorted[middleIndex];
    }
    return sorted.at(-1);
}

function extractLocalAssetUrls(html, pageUrl) {
    const matches = new Set();
    const assetPattern = /<(?:script|link)\b[^>]+(?:src|href)=["']([^"']+)["']/gi;
    let match;

    while ((match = assetPattern.exec(html)) !== null) {
        try {
            const absoluteUrl = new URL(match[1], pageUrl);
            if (absoluteUrl.origin === new URL(pageUrl).origin) {
                matches.add(absoluteUrl.href);
            }
        } catch {
            continue;
        }
    }

    return [...matches];
}

function parseAttributeList(rawValue) {
    const result = {};
    let key = "";
    let value = "";
    let readingValue = false;
    let quoted = false;

    for (const character of rawValue) {
        if (!readingValue) {
            if (character === "=") {
                readingValue = true;
            } else if (character !== ",") {
                key += character;
            }
            continue;
        }

        if (character === "\"") {
            quoted = !quoted;
            continue;
        }

        if (character === "," && !quoted) {
            if (key) {
                result[key.trim()] = value.trim();
            }
            key = "";
            value = "";
            readingValue = false;
            continue;
        }

        value += character;
    }

    if (key) {
        result[key.trim()] = value.trim();
    }

    return result;
}

function computePlaylistPollIntervalMs(parsedPlaylist, config) {
    const liveEdgeHintSeconds = parsedPlaylist.partTargetDurationSeconds
        ? parsedPlaylist.partTargetDurationSeconds * 4
        : Math.max(parsedPlaylist.targetDurationSeconds, 1) * 0.75;
    const candidate = Math.round(liveEdgeHintSeconds * 1000);
    return clamp(candidate, config.playlistPollFloorMs, config.playlistPollCeilingMs);
}

function computeTargetViewerCount(config, startedAt, endsAt, now) {
    const elapsedMs = now - startedAt;
    const steadyEndsAt = Math.max(startedAt + config.rampUpMs, endsAt - config.rampDownMs);

    if (elapsedMs <= 0) {
        return 0;
    }

    if (elapsedMs < config.rampUpMs && config.rampUpMs > 0) {
        return Math.max(1, Math.round((elapsedMs / config.rampUpMs) * config.targetViewers));
    }

    if (now < steadyEndsAt) {
        return config.targetViewers;
    }

    if (config.rampDownMs <= 0) {
        return 0;
    }

    const remainingMs = Math.max(endsAt - now, 0);
    return Math.max(0, Math.round((remainingMs / config.rampDownMs) * config.targetViewers));
}

class RollingSet {
    constructor(limit) {
        this.limit = limit;
        this.values = new Map();
    }

    add(value) {
        if (this.values.has(value)) {
            this.values.delete(value);
        }
        this.values.set(value, true);
        if (this.values.size > this.limit) {
            const firstKey = this.values.keys().next().value;
            this.values.delete(firstKey);
        }
    }

    has(value) {
        return this.values.has(value);
    }

    clear() {
        this.values.clear();
    }
}

class Reservoir {
    constructor(limit) {
        this.limit = limit;
        this.values = [];
        this.count = 0;
    }

    add(value) {
        if (!Number.isFinite(value)) {
            return;
        }

        this.count += 1;
        if (this.values.length < this.limit) {
            this.values.push(value);
            return;
        }

        const slot = Math.floor(Math.random() * this.count);
        if (slot < this.limit) {
            this.values[slot] = value;
        }
    }

    quantile(percentile) {
        if (this.values.length === 0) {
            return null;
        }

        const sorted = [...this.values].sort((left, right) => left - right);
        const index = Math.min(
            sorted.length - 1,
            Math.max(0, Math.ceil((percentile / 100) * sorted.length) - 1)
        );
        return sorted[index];
    }
}

class Metrics {
    constructor() {
        this.startedAt = Date.now();
        this.activeSessions = 0;
        this.maxActiveSessions = 0;
        this.startedSessions = 0;
        this.completedSessions = 0;
        this.failedSessions = 0;
        this.pageSessions = 0;
        this.traceSessions = 0;
        this.requestCount = 0;
        this.errorCount = 0;
        this.totalBytes = 0;
        this.latencies = new Reservoir(8192);
        this.startupLatencies = new Reservoir(4096);
        this.requestsByKind = new Map();
        this.statusCodes = new Map();
        this.errorsByKind = new Map();
        this.sessionFailures = new Map();
    }

    recordSessionStart(pageViewer, traceViewer) {
        this.startedSessions += 1;
        this.activeSessions += 1;
        this.maxActiveSessions = Math.max(this.maxActiveSessions, this.activeSessions);
        if (pageViewer) {
            this.pageSessions += 1;
        }
        if (traceViewer) {
            this.traceSessions += 1;
        }
    }

    recordSessionComplete() {
        this.completedSessions += 1;
        this.activeSessions = Math.max(0, this.activeSessions - 1);
    }

    recordSessionFailure(error) {
        this.failedSessions += 1;
        this.activeSessions = Math.max(0, this.activeSessions - 1);
        const label = summarizeSessionFailure(error);
        this.sessionFailures.set(label, (this.sessionFailures.get(label) ?? 0) + 1);
    }

    recordStartupLatency(value) {
        this.startupLatencies.add(value);
    }

    recordRequest(kind, statusCode, latencyMs, bytes) {
        this.requestCount += 1;
        this.totalBytes += bytes;
        this.latencies.add(latencyMs);

        const kindMetrics = this.ensureKind(kind);
        kindMetrics.requests += 1;
        kindMetrics.bytes += bytes;
        kindMetrics.latencies.add(latencyMs);
        kindMetrics.statusCodes.set(statusCode, (kindMetrics.statusCodes.get(statusCode) ?? 0) + 1);
        this.statusCodes.set(statusCode, (this.statusCodes.get(statusCode) ?? 0) + 1);

        if (statusCode >= 400) {
            this.errorCount += 1;
            kindMetrics.errors += 1;
        }
    }

    recordRequestError(kind, latencyMs, error) {
        this.requestCount += 1;
        this.errorCount += 1;
        this.latencies.add(latencyMs);

        const kindMetrics = this.ensureKind(kind);
        kindMetrics.requests += 1;
        kindMetrics.errors += 1;
        kindMetrics.latencies.add(latencyMs);

        const label = classifyError(error);
        kindMetrics.errorsByLabel.set(label, (kindMetrics.errorsByLabel.get(label) ?? 0) + 1);
        this.errorsByKind.set(`${kind}:${label}`, (this.errorsByKind.get(`${kind}:${label}`) ?? 0) + 1);
    }

    recordError(kind, label) {
        this.errorCount += 1;
        const kindMetrics = this.ensureKind(kind);
        kindMetrics.errors += 1;
        kindMetrics.errorsByLabel.set(label, (kindMetrics.errorsByLabel.get(label) ?? 0) + 1);
        this.errorsByKind.set(`${kind}:${label}`, (this.errorsByKind.get(`${kind}:${label}`) ?? 0) + 1);
    }

    ensureKind(kind) {
        if (!this.requestsByKind.has(kind)) {
            this.requestsByKind.set(kind, {
                requests: 0,
                bytes: 0,
                errors: 0,
                latencies: new Reservoir(4096),
                statusCodes: new Map(),
                errorsByLabel: new Map()
            });
        }

        return this.requestsByKind.get(kind);
    }
}

function printScenario(config) {
    console.log("Broadcast load scenario");
    console.log(`  base URL: ${config.baseUrl}`);
    console.log(`  target viewers: ${config.targetViewers}`);
    console.log(`  duration: ${formatDurationMs(config.durationMs)} (${formatDurationMs(config.rampUpMs)} ramp-up, ${formatDurationMs(config.rampDownMs)} ramp-down)`);
    console.log(`  sessions: ${formatDurationMs(config.sessionMinMs)} to ${formatDurationMs(config.sessionMaxMs)}`);
    console.log(`  page viewer ratio: ${formatRatio(config.pageViewerRatio)} | trace-map ratio: ${formatRatio(config.traceMapSessionRatio)}`);
    console.log(`  live edge: ${config.liveEdgeSegments} full segments, ${config.liveEdgeParts} parts, ${config.maxPlaybackErrors} playback misses before fail`);
    console.log(`  endpoints: page=${config.pagePath} status=${config.statusPath} playlist=${config.playlistPath} trace=${config.traceMapPath}`);
    console.log("");
}

function printProgress(config, metrics, startedAt, endsAt, activeSessions) {
    const elapsedMs = Date.now() - startedAt;
    const rps = elapsedMs > 0 ? metrics.requestCount / (elapsedMs / 1000) : 0;
    const p95 = metrics.latencies.quantile(95);
    const target = computeTargetViewerCount(config, startedAt, endsAt, Date.now());

    console.log(
        `[${formatDurationMs(elapsedMs)}] active=${activeSessions}/${target} started=${metrics.startedSessions} completed=${metrics.completedSessions} ` +
        `failed=${metrics.failedSessions} requests=${metrics.requestCount} rps=${rps.toFixed(1)} p95=${formatMetricMs(p95)} bytes=${formatBytes(metrics.totalBytes)}`
    );
}

function printSummary(metrics) {
    const elapsedMs = Date.now() - metrics.startedAt;
    console.log("");
    console.log("Summary");
    console.log(`  elapsed: ${formatDurationMs(elapsedMs)}`);
    console.log(`  sessions: started=${metrics.startedSessions} completed=${metrics.completedSessions} failed=${metrics.failedSessions} peak_active=${metrics.maxActiveSessions}`);
    console.log(`  requests: total=${metrics.requestCount} errors=${metrics.errorCount} bytes=${formatBytes(metrics.totalBytes)} avg_rps=${(metrics.requestCount / Math.max(elapsedMs / 1000, 1)).toFixed(1)}`);
    console.log(`  latency: p50=${formatMetricMs(metrics.latencies.quantile(50))} p95=${formatMetricMs(metrics.latencies.quantile(95))} p99=${formatMetricMs(metrics.latencies.quantile(99))}`);
    console.log(`  startup: p50=${formatMetricMs(metrics.startupLatencies.quantile(50))} p95=${formatMetricMs(metrics.startupLatencies.quantile(95))}`);
    console.log(`  session mix: page=${metrics.pageSessions} trace=${metrics.traceSessions} direct=${metrics.startedSessions - metrics.pageSessions}`);
    console.log("");
    console.log("By endpoint kind");

    for (const kind of REQUEST_KIND_ORDER) {
        const snapshot = metrics.requestsByKind.get(kind);
        if (!snapshot) {
            continue;
        }
        console.log(
            `  ${kind}: requests=${snapshot.requests} errors=${snapshot.errors} bytes=${formatBytes(snapshot.bytes)} ` +
            `p95=${formatMetricMs(snapshot.latencies.quantile(95))} statuses=${formatStatusCodes(snapshot.statusCodes)}`
        );
    }

    if (metrics.errorsByKind.size > 0) {
        console.log("");
        console.log("Errors");
        for (const [label, count] of [...metrics.errorsByKind.entries()].sort((left, right) => right[1] - left[1])) {
            console.log(`  ${label}: ${count}`);
        }
    }

    if (metrics.sessionFailures.size > 0) {
        console.log("");
        console.log("Session Failures");
        for (const [label, count] of [...metrics.sessionFailures.entries()].sort((left, right) => right[1] - left[1])) {
            console.log(`  ${label}: ${count}`);
        }
    }
}

function parseConfig(argv, env) {
    const options = {
        helpRequested: false,
        baseUrl: env.LOADGEN_BASE_URL ?? "http://127.0.0.1:8080",
        pagePath: env.LOADGEN_PAGE_PATH ?? "/broadcast",
        statusPath: env.LOADGEN_STATUS_PATH ?? "/api/v1/demo/public/broadcast/current",
        playlistPath: env.LOADGEN_PLAYLIST_PATH ?? "/api/v1/demo/public/broadcast/live/index.m3u8",
        traceMapPath: env.LOADGEN_TRACE_MAP_PATH ?? "/api/v1/demo/public/trace-map",
        targetViewers: parseInteger(env.LOADGEN_TARGET_VIEWERS ?? "60", "LOADGEN_TARGET_VIEWERS"),
        durationMs: parseDuration(env.LOADGEN_DURATION ?? "10m", "LOADGEN_DURATION"),
        rampUpMs: parseDuration(env.LOADGEN_RAMP_UP ?? "2m", "LOADGEN_RAMP_UP"),
        rampDownMs: parseDuration(env.LOADGEN_RAMP_DOWN ?? "1m", "LOADGEN_RAMP_DOWN"),
        sessionMinMs: parseDuration(env.LOADGEN_SESSION_MIN ?? "90s", "LOADGEN_SESSION_MIN"),
        sessionMaxMs: parseDuration(env.LOADGEN_SESSION_MAX ?? "4m", "LOADGEN_SESSION_MAX"),
        pageViewerRatio: parseRatio(env.LOADGEN_PAGE_VIEWER_RATIO ?? "0.35", "LOADGEN_PAGE_VIEWER_RATIO"),
        traceMapSessionRatio: parseRatio(env.LOADGEN_TRACE_MAP_SESSION_RATIO ?? "0.10", "LOADGEN_TRACE_MAP_SESSION_RATIO"),
        statusPollIntervalMs: parseDuration(env.LOADGEN_STATUS_POLL_INTERVAL ?? "5s", "LOADGEN_STATUS_POLL_INTERVAL"),
        requestTimeoutMs: parseDuration(env.LOADGEN_REQUEST_TIMEOUT ?? "15s", "LOADGEN_REQUEST_TIMEOUT"),
        playlistPollFloorMs: parseDuration(env.LOADGEN_PLAYLIST_POLL_FLOOR ?? "1s", "LOADGEN_PLAYLIST_POLL_FLOOR"),
        playlistPollCeilingMs: parseDuration(env.LOADGEN_PLAYLIST_POLL_CEILING ?? "6s", "LOADGEN_PLAYLIST_POLL_CEILING"),
        variantStrategy: env.LOADGEN_VARIANT_STRATEGY ?? "balanced",
        liveEdgeSegments: parseInteger(env.LOADGEN_LIVE_EDGE_SEGMENTS ?? "1", "LOADGEN_LIVE_EDGE_SEGMENTS"),
        liveEdgeParts: parseInteger(env.LOADGEN_LIVE_EDGE_PARTS ?? "8", "LOADGEN_LIVE_EDGE_PARTS"),
        maxPlaybackErrors: parseInteger(env.LOADGEN_MAX_PLAYBACK_ERRORS ?? "6", "LOADGEN_MAX_PLAYBACK_ERRORS"),
        logEveryMs: parseDuration(env.LOADGEN_LOG_EVERY ?? "5s", "LOADGEN_LOG_EVERY"),
        vodLoop: parseBoolean(env.LOADGEN_VOD_LOOP ?? "true", "LOADGEN_VOD_LOOP"),
        userAgent: "streaming-service-app-broadcast-loadgen/1.0"
    };

    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (argument === "--help" || argument === "-h") {
            options.helpRequested = true;
            continue;
        }

        const nextValue = argv[index + 1];
        if (!argument.startsWith("--")) {
            throw new Error(`Unexpected argument: ${argument}`);
        }
        if (nextValue === undefined) {
            throw new Error(`Missing value for ${argument}`);
        }

        switch (argument) {
            case "--base-url":
                options.baseUrl = nextValue;
                break;
            case "--page-path":
                options.pagePath = nextValue;
                break;
            case "--status-path":
                options.statusPath = nextValue;
                break;
            case "--playlist-path":
                options.playlistPath = nextValue;
                break;
            case "--trace-map-path":
                options.traceMapPath = nextValue;
                break;
            case "--target-viewers":
                options.targetViewers = parseInteger(nextValue, argument);
                break;
            case "--duration":
                options.durationMs = parseDuration(nextValue, argument);
                break;
            case "--ramp-up":
                options.rampUpMs = parseDuration(nextValue, argument);
                break;
            case "--ramp-down":
                options.rampDownMs = parseDuration(nextValue, argument);
                break;
            case "--session-min":
                options.sessionMinMs = parseDuration(nextValue, argument);
                break;
            case "--session-max":
                options.sessionMaxMs = parseDuration(nextValue, argument);
                break;
            case "--page-viewer-ratio":
                options.pageViewerRatio = parseRatio(nextValue, argument);
                break;
            case "--trace-map-session-ratio":
                options.traceMapSessionRatio = parseRatio(nextValue, argument);
                break;
            case "--status-poll-interval":
                options.statusPollIntervalMs = parseDuration(nextValue, argument);
                break;
            case "--request-timeout":
                options.requestTimeoutMs = parseDuration(nextValue, argument);
                break;
            case "--playlist-poll-floor":
                options.playlistPollFloorMs = parseDuration(nextValue, argument);
                break;
            case "--playlist-poll-ceiling":
                options.playlistPollCeilingMs = parseDuration(nextValue, argument);
                break;
            case "--variant-strategy":
                options.variantStrategy = nextValue;
                break;
            case "--live-edge-segments":
                options.liveEdgeSegments = parseInteger(nextValue, argument);
                break;
            case "--live-edge-parts":
                options.liveEdgeParts = parseInteger(nextValue, argument);
                break;
            case "--max-playback-errors":
                options.maxPlaybackErrors = parseInteger(nextValue, argument);
                break;
            case "--log-every":
                options.logEveryMs = parseDuration(nextValue, argument);
                break;
            case "--vod-loop":
                options.vodLoop = parseBoolean(nextValue, argument);
                break;
            default:
                throw new Error(`Unknown option: ${argument}`);
        }

        index += 1;
    }

    return options;
}

function validateConfig(config) {
    config.baseUrl = normalizeBaseUrl(config.baseUrl);

    if (config.durationMs <= 0) {
        throw new Error("Duration must be greater than zero.");
    }
    if (config.targetViewers <= 0) {
        throw new Error("Target viewers must be greater than zero.");
    }
    if (config.sessionMinMs <= 0 || config.sessionMaxMs <= 0) {
        throw new Error("Session durations must be greater than zero.");
    }
    if (config.sessionMaxMs < config.sessionMinMs) {
        throw new Error("session-max must be greater than or equal to session-min.");
    }
    if (config.playlistPollCeilingMs < config.playlistPollFloorMs) {
        throw new Error("playlist-poll-ceiling must be greater than or equal to playlist-poll-floor.");
    }
    if (config.liveEdgeSegments <= 0) {
        throw new Error("live-edge-segments must be greater than zero.");
    }
    if (config.liveEdgeParts <= 0) {
        throw new Error("live-edge-parts must be greater than zero.");
    }
    if (config.maxPlaybackErrors <= 0) {
        throw new Error("max-playback-errors must be greater than zero.");
    }
    if (!["balanced", "highest", "lowest", "random"].includes(config.variantStrategy)) {
        throw new Error(`Unsupported variant strategy: ${config.variantStrategy}`);
    }
}

function normalizeBaseUrl(value) {
    const url = new URL(value);
    url.pathname = "/";
    url.search = "";
    url.hash = "";
    return url.toString().replace(/\/$/, "");
}

function resolveUrl(baseUrl, maybeRelativeUrl) {
    return new URL(maybeRelativeUrl, `${baseUrl}/`).href;
}

function canonicalAssetKey(url) {
    return String(url ?? "").replace(/[?&]v=\d+/, "");
}

function parseInteger(value, label) {
    const parsed = Number.parseInt(String(value), 10);
    if (!Number.isFinite(parsed)) {
        throw new Error(`Invalid integer for ${label}: ${value}`);
    }
    return parsed;
}

function parseRatio(value, label) {
    const normalized = String(value).trim();
    const parsed = normalized.endsWith("%")
        ? Number.parseFloat(normalized.slice(0, -1)) / 100
        : Number.parseFloat(normalized);
    if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
        throw new Error(`Invalid ratio for ${label}: ${value}`);
    }
    return parsed;
}

function parseBoolean(value, label) {
    const normalized = String(value).trim().toLowerCase();
    if (["1", "true", "yes", "on"].includes(normalized)) {
        return true;
    }
    if (["0", "false", "no", "off"].includes(normalized)) {
        return false;
    }
    throw new Error(`Invalid boolean for ${label}: ${value}`);
}

function parseDuration(value, label) {
    const normalized = String(value).trim().toLowerCase();
    const match = normalized.match(/^(\d+(?:\.\d+)?)(ms|s|m|h)?$/);
    if (!match) {
        throw new Error(`Invalid duration for ${label}: ${value}`);
    }

    const amount = Number.parseFloat(match[1]);
    const unit = match[2] ?? "s";
    const factor = {
        ms: 1,
        s: 1000,
        m: 60_000,
        h: 3_600_000
    }[unit];

    return Math.round(amount * factor);
}

function classifyError(error) {
    if (!error) {
        return "unknown";
    }
    const message = String(error.message ?? error).toLowerCase();
    if (message.includes("timeout")) {
        return "timeout";
    }
    if (message.includes("fetch failed")) {
        return "fetch-failed";
    }
    if (message.includes("aborted")) {
        return "aborted";
    }
    return "request-error";
}

function summarizeSessionFailure(error) {
    const message = String(error?.message ?? error ?? "unknown failure").trim();
    return message.length > 120 ? `${message.slice(0, 117)}...` : message;
}

function isRecoverablePlaybackStatus(statusCode) {
    return [404, 408, 409, 412, 425, 429, 500, 502, 503, 504].includes(statusCode);
}

function formatBytes(bytes) {
    const units = ["B", "KiB", "MiB", "GiB"];
    let value = bytes;
    let unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
        value /= 1024;
        unitIndex += 1;
    }
    return `${value.toFixed(value >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

function formatMetricMs(value) {
    if (!Number.isFinite(value)) {
        return "n/a";
    }
    return `${Math.round(value)} ms`;
}

function formatRatio(value) {
    return `${Math.round(value * 100)}%`;
}

function formatDurationMs(value) {
    const totalSeconds = Math.max(0, Math.round(value / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    if (hours > 0) {
        return `${hours}h${String(minutes).padStart(2, "0")}m${String(seconds).padStart(2, "0")}s`;
    }
    if (minutes > 0) {
        return `${minutes}m${String(seconds).padStart(2, "0")}s`;
    }
    return `${seconds}s`;
}

function formatStatusCodes(statusCodes) {
    if (!statusCodes || statusCodes.size === 0) {
        return "none";
    }
    return [...statusCodes.entries()]
        .sort((left, right) => left[0] - right[0])
        .map(([statusCode, count]) => `${statusCode}:${count}`)
        .join(",");
}

function randomBetween(minimum, maximum) {
    if (minimum === maximum) {
        return minimum;
    }
    return minimum + Math.floor(Math.random() * (maximum - minimum + 1));
}

function randomIndex(length) {
    return Math.floor(Math.random() * length);
}

function clamp(value, minimum, maximum) {
    return Math.min(Math.max(value, minimum), maximum);
}

main().catch((error) => {
    console.error(error.message ?? error);
    process.exitCode = 1;
});
