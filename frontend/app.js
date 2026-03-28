const runtimeConfig = window.STREAMING_CONFIG ?? {};

const storageKeys = {
    session: "streaming-frontend.session"
};

const roleCapabilities = {
    billing_admin: ["operations", "governance", "billing", "billing_write"],
    finance_analyst: ["operations", "billing", "billing_write"],
    platform_admin: ["operations", "governance", "ingest", "billing", "billing_write"],
    programming_manager: ["operations", "governance", "ingest"],
    executive: ["operations", "governance", "billing"],
    qa_reviewer: ["operations"],
    staff_operator: ["operations"]
};

const demoMonkeyPresets = {
    clear: {
        label: "Clear",
        enabled: false,
        preset: "clear",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "viewer-startup-spike": {
        label: "Slow startup",
        enabled: true,
        preset: "viewer-startup-spike",
        startupDelayMs: 2500,
        throttleKbps: 768,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "viewer-brownout": {
        label: "Viewer brownout",
        enabled: true,
        preset: "viewer-brownout",
        startupDelayMs: 1800,
        throttleKbps: 512,
        disconnectAfterKb: 384,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "ad-break-delay": {
        label: "Ad break delay",
        enabled: true,
        preset: "ad-break-delay",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: true,
        adLoadFailureEnabled: false
    },
    "sponsor-pod-miss": {
        label: "Sponsor pod miss",
        enabled: true,
        preset: "sponsor-pod-miss",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: true,
        adLoadFailureEnabled: true
    },
    "slow-start": {
        label: "Slow start",
        enabled: true,
        preset: "slow-start",
        startupDelayMs: 2500,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "saturated-link": {
        label: "Saturated link",
        enabled: true,
        preset: "saturated-link",
        startupDelayMs: 1200,
        throttleKbps: 768,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "carrier-brownout": {
        label: "Carrier brownout",
        enabled: true,
        preset: "carrier-brownout",
        startupDelayMs: 1800,
        throttleKbps: 512,
        disconnectAfterKb: 384,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    }
};

const boothPersonas = {
    viewer: {
        label: "Viewer",
        mode: "public",
        targetPage: "home"
    },
    operator: {
        label: "Operator",
        mode: "session",
        personaKey: "operator",
        targetPage: "operations"
    },
    exec: {
        label: "Exec",
        mode: "session",
        personaKey: "exec",
        targetPage: "billing"
    }
};

const boothScenarioShortcuts = {
    "viewer-startup-spike": {
        label: "Slow startup",
        personaKey: "operator",
        targetPage: "operations"
    },
    "ad-break-delay": {
        label: "Ad stall",
        personaKey: "operator",
        targetPage: "operations"
    },
    "sponsor-pod-miss": {
        label: "Sponsor miss",
        personaKey: "operator",
        targetPage: "operations"
    },
    clear: {
        label: "Recovery",
        personaKey: "operator",
        targetPage: "operations"
    }
};

function defaultDemoMonkeyState() {
    return {
        enabled: false,
        preset: "clear",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false,
        nextBreakOnlyEnabled: false,
        autoClearAt: "",
        updatedAt: "",
        summary: "Incident simulation is bypassed. Screening, sponsor, and external distribution paths are flowing normally.",
        scope: "Applies to protected screening playback, the external broadcast feed, sponsor insertion between queued videos, the public trace pivot, and optional browser-error injection for incident walkthroughs.",
        affectedPaths: [
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
        ]
    };
}

function defaultAdStatus() {
    return {
        state: "ARMED",
        podLabel: "Sponsor pod A",
        sponsorLabel: "North Coast Sports Network",
        decisioningMode: "Server-side stitched pod",
        breakStartAt: "",
        breakEndAt: "",
        detail: "The next sponsor pod is staged for the primary channel."
    };
}

function defaultAdIssueState() {
    return {
        enabled: false,
        preset: "clear",
        responseDelayMs: 0,
        adLoadFailureEnabled: false,
        updatedAt: "",
        summary: "Ad service is healthy. Sponsor clips are inserted between queued titles without additional delay.",
        affectedPaths: [
            "/api/v1/demo/ads/current",
            "/api/v1/demo/ads/program-queue",
            "/api/v1/demo/ads/health",
            "/api/v1/demo/public/broadcast/current"
        ]
    };
}

function defaultBroadcastChannelLabel() {
    return runtimeConfig.defaultBroadcastChannelLabel ?? "Acme Network East";
}

function defaultBroadcastTitle() {
    return runtimeConfig.defaultBroadcastTitle ?? defaultBroadcastChannelLabel();
}

function defaultBroadcastDetail() {
    return runtimeConfig.defaultBroadcastDetail ?? "The house lineup is filling the external channel until a contribution feed is taken live.";
}

const seedContent = [
    {
        id: "11111111-1111-1111-1111-111111111111",
        title: "Big Buck Bunny",
        type: "MOVIE",
        description: "A classic Blender open movie featuring a rabbit and three very unlucky woodland bullies.",
        ageRating: "G",
        releaseDate: "2008-05-30",
        runtimeLabel: "9m 57s",
        headline: "Blender classic",
        featureLine: "Forest slapstick with open-movie pedigree",
        channelLabel: "Prime East",
        programmingTrack: "Family Matinee",
        readinessLabel: "QC cleared",
        signalProfile: "1080p mezzanine",
        genreList: ["Animation", "Comedy", "Open Movie"],
        watchUrl: "/api/v1/demo/media/library/big-buck-bunny.mp4",
        posterTone: "linear-gradient(180deg, rgba(107, 169, 125, 0.26), rgba(10, 20, 30, 0.88)), radial-gradient(circle at 20% 20%, rgba(233, 194, 112, 0.55), transparent 30%)",
        backdropTone: "linear-gradient(120deg, rgba(107, 169, 125, 0.3), rgba(10, 20, 30, 0.88)), radial-gradient(circle at 80% 20%, rgba(233, 194, 112, 0.42), transparent 26%)"
    },
    {
        id: "22222222-2222-2222-2222-222222222222",
        title: "Elephants Dream",
        type: "MOVIE",
        description: "A surreal tour through a machine world where two travelers collide over what the place is meant to become.",
        ageRating: "PG",
        releaseDate: "2006-03-24",
        runtimeLabel: "10m 54s",
        headline: "Experimental sci-fi",
        featureLine: "Foundational Blender feature with a dream-logic visual world",
        channelLabel: "Events",
        programmingTrack: "Innovation Desk",
        readinessLabel: "Standards review",
        signalProfile: "Primary contribution feed",
        genreList: ["Animation", "Science Fiction", "Open Movie"],
        watchUrl: "/api/v1/demo/media/library/elephants-dream.mp4",
        posterTone: "linear-gradient(180deg, rgba(78, 84, 119, 0.24), rgba(7, 12, 18, 0.92)), radial-gradient(circle at 24% 18%, rgba(143, 171, 235, 0.45), transparent 26%)",
        backdropTone: "linear-gradient(120deg, rgba(43, 52, 84, 0.36), rgba(7, 12, 18, 0.92)), radial-gradient(circle at 82% 18%, rgba(143, 171, 235, 0.26), transparent 24%)"
    },
    {
        id: "33333333-3333-3333-3333-333333333333",
        title: "Sintel",
        type: "MOVIE",
        description: "A lone traveler searches for a lost dragon companion across frozen valleys and ruined cities.",
        ageRating: "PG-13",
        releaseDate: "2010-09-30",
        runtimeLabel: "14m 48s",
        headline: "Epic fantasy",
        featureLine: "Adventure feature with a stronger dramatic arc for review screenings",
        channelLabel: "Review Desk",
        programmingTrack: "Premium Window",
        readinessLabel: "Executive notes pending",
        signalProfile: "HDR mezzanine",
        genreList: ["Animation", "Fantasy", "Adventure"],
        watchUrl: "/api/v1/demo/media/library/sintel.mp4",
        posterTone: "linear-gradient(180deg, rgba(31, 57, 83, 0.22), rgba(5, 11, 18, 0.92)), radial-gradient(circle at 70% 18%, rgba(126, 169, 243, 0.42), transparent 28%)",
        backdropTone: "linear-gradient(120deg, rgba(22, 47, 75, 0.34), rgba(5, 11, 18, 0.92)), radial-gradient(circle at 16% 22%, rgba(226, 191, 102, 0.22), transparent 22%)"
    },
    {
        id: "44444444-4444-4444-4444-444444444444",
        title: "Tears of Steel",
        type: "MOVIE",
        description: "A near-future team attempts to repair a broken relationship before a robotic threat overruns Amsterdam.",
        ageRating: "PG",
        releaseDate: "2012-09-26",
        runtimeLabel: "12m 14s",
        headline: "Live-action hybrid",
        featureLine: "Sci-fi short with VFX-heavy sequences and live-action review value",
        channelLabel: "Prime West",
        programmingTrack: "VFX Showcase",
        readinessLabel: "Ready for playout",
        signalProfile: "1080p clean feed",
        genreList: ["Science Fiction", "Drama", "Open Movie"],
        watchUrl: "/api/v1/demo/media/library/tears-of-steel.mp4",
        posterTone: "linear-gradient(180deg, rgba(82, 34, 24, 0.22), rgba(7, 10, 15, 0.92)), radial-gradient(circle at 18% 18%, rgba(216, 109, 63, 0.46), transparent 28%)",
        backdropTone: "linear-gradient(120deg, rgba(80, 28, 18, 0.34), rgba(7, 10, 15, 0.92)), radial-gradient(circle at 82% 18%, rgba(216, 109, 63, 0.24), transparent 24%)"
    }
];

const seedBillingAccounts = [
    {
        id: "8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5001",
        label: "North Coast Sports Network",
        desk: "Regional sports carriage",
        contact: "finance@northcoastsports.example"
    },
    {
        id: "8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5002",
        label: "Metro Weather Desk",
        desk: "Always-on forecast service",
        contact: "ap@metroweather.example"
    },
    {
        id: "8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5003",
        label: "City Arts Channel",
        desk: "Public culture and events",
        contact: "billing@cityarts.example"
    },
    {
        id: "8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5004",
        label: "Pop-Up Event East",
        desk: "Temporary live-event window",
        contact: "controller@popupeast.example"
    }
];

const elements = {
    authOverlay: document.querySelector("#auth-overlay"),
    authForm: document.querySelector("#auth-form"),
    authEmail: document.querySelector("#auth-email"),
    authPassword: document.querySelector("#auth-password"),
    authMessage: document.querySelector("#auth-message"),
    launchMessage: document.querySelector("#launch-message"),
    launchChannelLabel: document.querySelector("#launch-channel-label"),
    launchChannelCopy: document.querySelector("#launch-channel-copy"),
    launchSponsorLabel: document.querySelector("#launch-sponsor-label"),
    launchSponsorCopy: document.querySelector("#launch-sponsor-copy"),
    launchIncidentLabel: document.querySelector("#launch-incident-label"),
    launchIncidentCopy: document.querySelector("#launch-incident-copy"),
    launchBroadcastLink: document.querySelector("#launch-broadcast-link"),
    launchTraceLink: document.querySelector("#launch-trace-link"),
    launchPersonaGrid: document.querySelector("#launch-persona-grid"),
    launchScenarioGrid: document.querySelector("#launch-scenario-grid"),
    scopedNodes: [...document.querySelectorAll("[data-role-scope]")],
    navLinks: [...document.querySelectorAll("[data-page-link]")],
    pageNodes: [...document.querySelectorAll("[data-page]")],
    catalogStatus: document.querySelector("#catalog-status"),
    surpriseMe: document.querySelector("#surprise-me"),
    presentationToggle: document.querySelector("#presentation-toggle"),
    sessionChip: document.querySelector("#session-chip"),
    signOut: document.querySelector("#sign-out"),
    suiteFacility: document.querySelector("#suite-facility"),
    suiteRegion: document.querySelector("#suite-region"),
    suiteEnvironment: document.querySelector("#suite-environment"),
    suitePosture: document.querySelector("#suite-posture"),
    heroReadyCount: document.querySelector("#hero-ready-count"),
    heroActiveCount: document.querySelector("#hero-active-count"),
    heroQueueCount: document.querySelector("#hero-queue-count"),
    channelGrid: document.querySelector("#channel-grid"),
    rundownList: document.querySelector("#rundown-list"),
    serviceWall: document.querySelector("#service-wall"),
    deskHighlights: document.querySelector("#desk-highlights"),
    clusterContext: document.querySelector("#cluster-context"),
    deploymentNamespace: document.querySelector("#deployment-namespace"),
    runtimeMode: document.querySelector("#runtime-mode"),
    opsSummaryCopy: document.querySelector("#ops-summary-copy"),
    sessionRoleLabel: document.querySelector("#session-role-label"),
    opsLibrarySize: document.querySelector("#ops-library-size"),
    opsStreamReady: document.querySelector("#ops-stream-ready"),
    opsInProgress: document.querySelector("#ops-in-progress"),
    opsSource: document.querySelector("#ops-source"),
    opsDistribution: document.querySelector("#ops-distribution"),
    opsAuth: document.querySelector("#ops-auth"),
    opsSelected: document.querySelector("#ops-selected"),
    opsPublicTitle: document.querySelector("#ops-public-title"),
    opsPublicCopy: document.querySelector("#ops-public-copy"),
    opsPublicLink: document.querySelector("#ops-public-link"),
    opsPublicRtspLink: document.querySelector("#ops-public-rtsp-link"),
    opsPublicRtspChip: document.querySelector("#ops-public-rtsp-chip"),
    opsPublicRtspUrl: document.querySelector("#ops-public-rtsp-url"),
    rtspIntakeCopy: document.querySelector("#rtsp-intake-copy"),
    rtspJobForm: document.querySelector("#rtsp-job-form"),
    rtspTargetContent: document.querySelector("#rtsp-target-content"),
    rtspSourceUrl: document.querySelector("#rtsp-source-url"),
    rtspDuration: document.querySelector("#rtsp-duration"),
    rtspFormMessage: document.querySelector("#rtsp-form-message"),
    rtspJobsCopy: document.querySelector("#rtsp-jobs-copy"),
    rtspJobs: document.querySelector("#rtsp-jobs"),
    rtspRefresh: document.querySelector("#rtsp-refresh"),
    rtspSubmit: document.querySelector("#rtsp-submit"),
    adIssueSummary: document.querySelector("#ad-issue-summary"),
    adIssueForm: document.querySelector("#ad-issue-form"),
    adIssuePreset: document.querySelector("#ad-issue-preset"),
    adIssueDelay: document.querySelector("#ad-issue-delay"),
    adIssueEnabled: document.querySelector("#ad-issue-enabled"),
    adIssueFailLoad: document.querySelector("#ad-issue-fail-load"),
    adIssueMessage: document.querySelector("#ad-issue-message"),
    adIssueApply: document.querySelector("#ad-issue-apply"),
    adIssueClear: document.querySelector("#ad-issue-clear"),
    demoMonkeyStatus: document.querySelector("#demo-monkey-status"),
    demoMonkeySummary: document.querySelector("#demo-monkey-summary"),
    demoMonkeyScope: document.querySelector("#demo-monkey-scope"),
    demoMonkeyProfile: document.querySelector("#demo-monkey-profile"),
    demoMonkeyDetail: document.querySelector("#demo-monkey-detail"),
    demoMonkeyUpdatedAt: document.querySelector("#demo-monkey-updated-at"),
    demoMonkeyForm: document.querySelector("#demo-monkey-form"),
    demoMonkeyPresets: document.querySelector("#demo-monkey-presets"),
    demoMonkeyEnabled: document.querySelector("#demo-monkey-enabled"),
    demoMonkeyNextBreakOnlyEnabled: document.querySelector("#demo-monkey-next-break-only"),
    demoMonkeyLatencyEnabled: document.querySelector("#demo-monkey-latency-enabled"),
    demoMonkeyLatencyValue: document.querySelector("#demo-monkey-latency-value"),
    demoMonkeyThrottleEnabled: document.querySelector("#demo-monkey-throttle-enabled"),
    demoMonkeyThrottleValue: document.querySelector("#demo-monkey-throttle-value"),
    demoMonkeyDisconnectEnabled: document.querySelector("#demo-monkey-disconnect-enabled"),
    demoMonkeyDisconnectValue: document.querySelector("#demo-monkey-disconnect-value"),
    demoMonkeyPlaybackFailureEnabled: document.querySelector("#demo-monkey-playback-failure-enabled"),
    demoMonkeyTraceMapFailureEnabled: document.querySelector("#demo-monkey-trace-map-failure-enabled"),
    demoMonkeyFrontendExceptionEnabled: document.querySelector("#demo-monkey-frontend-exception-enabled"),
    demoMonkeySlowAdEnabled: document.querySelector("#demo-monkey-slow-ad-enabled"),
    demoMonkeyAdLoadFailureEnabled: document.querySelector("#demo-monkey-ad-load-failure-enabled"),
    demoMonkeyMessage: document.querySelector("#demo-monkey-message"),
    demoMonkeyApply: document.querySelector("#demo-monkey-apply"),
    demoMonkeyDisable: document.querySelector("#demo-monkey-disable"),
    billingPortfolioCopy: document.querySelector("#billing-portfolio-copy"),
    billingTotalOutstanding: document.querySelector("#billing-total-outstanding"),
    billingOpenCount: document.querySelector("#billing-open-count"),
    billingPastDueCount: document.querySelector("#billing-past-due-count"),
    billingNextDueDate: document.querySelector("#billing-next-due-date"),
    billingAccountFilter: document.querySelector("#billing-account-filter"),
    billingStatusFilter: document.querySelector("#billing-status-filter"),
    billingOverdueOnly: document.querySelector("#billing-overdue-only"),
    billingRefresh: document.querySelector("#billing-refresh"),
    billingInvoiceList: document.querySelector("#billing-invoice-list"),
    billingSourceCopy: document.querySelector("#billing-source-copy"),
    billingSourceLabel: document.querySelector("#billing-source-label"),
    billingHealthLabel: document.querySelector("#billing-health-label"),
    billingSelectedLabel: document.querySelector("#billing-selected-label"),
    billingLastSync: document.querySelector("#billing-last-sync"),
    billingDetail: document.querySelector("#billing-detail"),
    billingCreateForm: document.querySelector("#billing-create-form"),
    billingCreateAccount: document.querySelector("#billing-create-account"),
    billingLineTitle: document.querySelector("#billing-line-title"),
    billingLineQuantity: document.querySelector("#billing-line-quantity"),
    billingLineAmount: document.querySelector("#billing-line-amount"),
    billingCycle: document.querySelector("#billing-cycle"),
    billingDueDate: document.querySelector("#billing-due-date"),
    billingNotes: document.querySelector("#billing-notes"),
    billingCreateSubmit: document.querySelector("#billing-create-submit"),
    billingFormMessage: document.querySelector("#billing-form-message"),
    featuredMeta: document.querySelector("#featured-meta"),
    featuredTitle: document.querySelector("#featured-title"),
    featuredDescription: document.querySelector("#featured-description"),
    playFeatured: document.querySelector("#play-featured"),
    saveFeatured: document.querySelector("#save-featured"),
    spotlightTitle: document.querySelector("#spotlight-title"),
    spotlightCopy: document.querySelector("#spotlight-copy"),
    spotlightTags: document.querySelector("#spotlight-tags"),
    myListCount: document.querySelector("#my-list-count"),
    myListPreview: document.querySelector("#my-list-preview"),
    moviePlayer: document.querySelector("#movie-player"),
    movieTitle: document.querySelector("#movie-title"),
    movieDescription: document.querySelector("#movie-description"),
    movieBadges: document.querySelector("#movie-badges"),
    playerStatusBadge: document.querySelector("#player-status-badge"),
    playerStatusText: document.querySelector("#player-status-text"),
    playbackProgressLabel: document.querySelector("#playback-progress-label"),
    playbackProgressFill: document.querySelector("#playback-progress-fill"),
    playerForm: document.querySelector("#player-form"),
    playerUrl: document.querySelector("#player-url"),
    loadDemo: document.querySelector("#load-demo"),
    continueCopy: document.querySelector("#continue-copy"),
    continueSection: document.querySelector("#continue-section"),
    continueRow: document.querySelector("#continue-row"),
    featuredLineupSection: document.querySelector("#featured-lineup-section"),
    featuredLineupCopy: document.querySelector("#featured-lineup-copy"),
    featuredRow: document.querySelector("#featured-row"),
    catalogStatusCopy: document.querySelector("#catalog-status-copy"),
    librarySearch: document.querySelector("#library-search"),
    genreChips: document.querySelector("#genre-chips"),
    libraryGrid: document.querySelector("#library-grid"),
    catalogSourceLabel: document.querySelector("#catalog-source-label"),
    selectedTitleLabel: document.querySelector("#selected-title-label"),
    savedCountLabel: document.querySelector("#saved-count-label"),
    refreshCatalog: document.querySelector("#refresh-catalog")
};

const state = {
    session: readJson(storageKeys.session, null),
    library: [],
    catalogSource: "seed",
    selectedId: "",
    activeGenre: "All",
    searchTerm: "",
    currentPage: "home",
    myList: new Set(),
    watchState: {},
    playerUrl: "",
    playerMeta: null,
    pendingResumeId: "",
    lastProgressCommit: 0,
    rtspJobs: [],
    serviceHealth: {
        auth: "checking",
        content: "checking",
        media: "checking",
        ads: "checking",
        billing: "checking"
    },
    adQueue: [],
    adIssue: defaultAdIssueState(),
    broadcast: {
        channelLabel: defaultBroadcastChannelLabel(),
        title: defaultBroadcastTitle(),
        status: "DEMO_LOOP",
        sourceType: "DEMO_LIBRARY",
        detail: defaultBroadcastDetail(),
        updatedAt: new Date().toISOString(),
        jobId: "",
        publicPlaybackUrl: runtimeConfig.publicBroadcastPlaybackUrl ?? "/api/v1/demo/public/broadcast/live/index.m3u8",
        operatorPlaybackUrl: runtimeConfig.demoMovieUrl ?? "/api/v1/demo/media/movie.mp4",
        publicPageUrl: runtimeConfig.publicBroadcastPageUrl ?? "/broadcast",
        adStatus: defaultAdStatus()
    },
    lastHealthCheck: 0,
    billing: {
        source: "locked",
        invoices: [],
        selectedAccountId: "all",
        selectedInvoiceId: "",
        statusFilter: "ALL",
        overdueOnly: false,
        lastSyncLabel: "Awaiting sync"
    },
    demoMonkey: defaultDemoMonkeyState()
};

let rtspPollHandle = null;
let hlsPlayer = null;
let presentationFallbackEnabled = false;
let lastDemoMonkeyFrontendFaultKey = "";

function readJson(key, fallback) {
    try {
        const raw = localStorage.getItem(key);
        return raw ? JSON.parse(raw) : fallback;
    } catch (error) {
        console.warn(`Failed to read localStorage key ${key}.`, error);
        return fallback;
    }
}

function writeJson(key, value) {
    try {
        localStorage.setItem(key, JSON.stringify(value));
    } catch (error) {
        console.warn(`Failed to write localStorage key ${key}.`, error);
    }
}

function scopedKey(name) {
    const identity = (state.session?.email ?? "guest").trim().toLowerCase() || "guest";
    return `streaming-frontend.${identity}.${name}`;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

function escapeAttribute(value) {
    return escapeHtml(value);
}

function absoluteUrl(path) {
    try {
        return new URL(path || "/", window.location.origin).href;
    } catch (_error) {
        return path || "/";
    }
}

function resolveInitialPlayerUrl(savedUrl) {
    const normalizedSavedUrl = savedUrl?.trim() ?? "";
    if (!normalizedSavedUrl) {
        return runtimeConfig.demoMovieUrl ?? "";
    }

    const isLegacyFrontendDemo =
        normalizedSavedUrl === "./movies/demo.mp4" ||
        normalizedSavedUrl === "movies/demo.mp4" ||
        normalizedSavedUrl.includes("/movies/demo.mp4");

    if (isLegacyFrontendDemo) {
        return runtimeConfig.demoMovieUrl ?? normalizedSavedUrl;
    }

    try {
        const saved = new URL(normalizedSavedUrl, window.location.origin);
        const currentDemo = new URL(runtimeConfig.demoMovieUrl ?? "", window.location.origin);
        if (saved.pathname === currentDemo.pathname && saved.origin !== currentDemo.origin) {
            return runtimeConfig.demoMovieUrl ?? normalizedSavedUrl;
        }
    } catch (error) {
        console.warn("Failed to normalize saved player URL.", error);
    }

    return normalizedSavedUrl;
}

function deriveProfileName(email) {
    return email
        .split("@")[0]
        .split(/[._-]/)
        .filter(Boolean)
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(" ");
}

function loadUserState() {
    state.myList = new Set(readJson(scopedKey("myList"), []));
    state.watchState = readJson(scopedKey("watchState"), {});
    state.selectedId = localStorage.getItem(scopedKey("selectedId")) ?? "";
    state.playerUrl = resolveInitialPlayerUrl(localStorage.getItem(scopedKey("playerUrl")) ?? "");
}

function persistSelectedId() {
    if (state.session && state.selectedId) {
        localStorage.setItem(scopedKey("selectedId"), state.selectedId);
    }
}

function persistPlayerUrl() {
    if (state.session) {
        localStorage.setItem(scopedKey("playerUrl"), state.playerUrl);
    }
}

function persistMyList() {
    if (state.session) {
        writeJson(scopedKey("myList"), [...state.myList]);
    }
}

function persistWatchState() {
    if (state.session) {
        writeJson(scopedKey("watchState"), state.watchState);
    }
}

function normalizeGenres(genreList, fallbackList) {
    const normalized = Array.isArray(genreList)
        ? genreList
            .map((genre) => {
                if (typeof genre === "string") return genre;
                return genre?.name ?? genre?.tag ?? "";
            })
            .filter(Boolean)
        : [];

    return normalized.length ? normalized : fallbackList;
}

function normalizeItem(item, index) {
    const fallback = seedContent.find((seed) => seed.id === item.id || seed.title === item.title) ?? seedContent[index % seedContent.length];
    const type = item.type ?? fallback.type ?? "MOVIE";
    const releaseDate = String(item.releaseDate ?? fallback.releaseDate ?? "2026-01-01");
    const runtimeLabel = item.runtimeLabel ?? fallback.runtimeLabel ?? (type === "SERIES" ? "8 episodes" : "1h 52m");

    return {
        id: String(item.id ?? fallback.id ?? `seed-${index}`),
        title: item.title ?? fallback.title ?? `Title ${index + 1}`,
        description: item.description ?? fallback.description ?? "No description available.",
        type,
        ageRating: item.ageRating ?? fallback.ageRating ?? "13+",
        releaseDate,
        year: releaseDate.slice(0, 4),
        runtimeLabel,
        headline: item.headline ?? fallback.headline ?? "Acme premiere",
        featureLine: item.featureLine ?? fallback.featureLine ?? "Ready to stream",
        channelLabel: item.channelLabel ?? fallback.channelLabel ?? "Prime East",
        programmingTrack: item.programmingTrack ?? fallback.programmingTrack ?? "Programming Desk",
        readinessLabel: item.readinessLabel ?? fallback.readinessLabel ?? "Ready for review",
        signalProfile: item.signalProfile ?? fallback.signalProfile ?? "1080p house feed",
        genreList: normalizeGenres(item.genreList, fallback.genreList ?? []),
        watchUrl: item.watchUrl ?? item.playbackUrl ?? item.streamUrl ?? item.videoUrl ?? fallback.watchUrl ?? runtimeConfig.demoMovieUrl ?? "",
        posterTone: item.posterTone ?? fallback.posterTone,
        backdropTone: item.backdropTone ?? fallback.backdropTone
    };
}

function normalizeRtspJob(job, index) {
    return {
        jobId: String(job?.jobId ?? `rtsp-job-${index}`),
        jobNumber: Number(job?.jobNumber ?? index + 1),
        contentId: job?.contentId ? String(job.contentId) : "",
        mediaType: String(job?.mediaType ?? "MOVIE"),
        targetTitle: String(job?.targetTitle ?? "Review Source"),
        operatorEmail: String(job?.operatorEmail ?? "unknown@acmebroadcasting.com"),
        sourceUrl: String(job?.sourceUrl ?? ""),
        captureDurationSeconds: Number.parseInt(job?.captureDurationSeconds, 10) || 300,
        status: String(job?.status ?? "QUEUED").toUpperCase(),
        createdAt: job?.createdAt ?? new Date().toISOString(),
        updatedAt: job?.updatedAt ?? job?.createdAt ?? new Date().toISOString(),
        playbackUrl: String(job?.playbackUrl ?? ""),
        playbackFormat: String(job?.playbackFormat ?? (isLikelyHlsSource(job?.playbackUrl ?? "") ? "HLS" : "MP4")).toUpperCase(),
        errorMessage: String(job?.errorMessage ?? "")
    };
}

function normalizeBroadcastStatus(payload) {
    return {
        channelLabel: resolveBroadcastChannelLabel(payload?.channelLabel),
        title: resolveBroadcastTitle(payload?.title),
        status: String(payload?.status ?? "DEMO_LOOP").toUpperCase(),
        sourceType: String(payload?.sourceType ?? "DEMO_LIBRARY").toUpperCase(),
        detail: resolveBroadcastDetail(payload?.detail),
        updatedAt: payload?.updatedAt ?? new Date().toISOString(),
        jobId: payload?.jobId ? String(payload.jobId) : "",
        publicPlaybackUrl: String(payload?.publicPlaybackUrl ?? runtimeConfig.publicBroadcastPlaybackUrl ?? "/api/v1/demo/public/broadcast/live/index.m3u8"),
        operatorPlaybackUrl: String(payload?.operatorPlaybackUrl ?? runtimeConfig.demoMovieUrl ?? "/api/v1/demo/media/movie.mp4"),
        publicPageUrl: String(payload?.publicPageUrl ?? runtimeConfig.publicBroadcastPageUrl ?? "/broadcast"),
        adStatus: normalizeAdStatus(payload?.adStatus)
    };
}

function resolveBroadcastChannelLabel(label) {
    return String(label ?? "").trim() || defaultBroadcastChannelLabel();
}

function resolveBroadcastTitle(title) {
    return String(title ?? "").trim() || defaultBroadcastTitle();
}

function resolveBroadcastDetail(detail) {
    return String(detail ?? "").trim() || defaultBroadcastDetail();
}

function normalizeAdStatus(payload) {
    const fallback = defaultAdStatus();
    return {
        state: String(payload?.state ?? fallback.state).toUpperCase(),
        podLabel: String(payload?.podLabel ?? fallback.podLabel),
        sponsorLabel: String(payload?.sponsorLabel ?? fallback.sponsorLabel),
        decisioningMode: String(payload?.decisioningMode ?? fallback.decisioningMode),
        breakStartAt: String(payload?.breakStartAt ?? fallback.breakStartAt),
        breakEndAt: String(payload?.breakEndAt ?? fallback.breakEndAt),
        detail: String(payload?.detail ?? fallback.detail)
    };
}

function normalizeAdIssueStatus(payload) {
    const fallback = defaultAdIssueState();
    return {
        enabled: Boolean(payload?.enabled),
        preset: String(payload?.preset ?? fallback.preset).trim().toLowerCase() || fallback.preset,
        responseDelayMs: Number.parseInt(payload?.responseDelayMs, 10) || 0,
        adLoadFailureEnabled: Boolean(payload?.adLoadFailureEnabled),
        updatedAt: String(payload?.updatedAt ?? fallback.updatedAt),
        summary: String(payload?.summary ?? fallback.summary),
        affectedPaths: Array.isArray(payload?.affectedPaths) && payload.affectedPaths.length
            ? payload.affectedPaths.map((path) => String(path))
            : fallback.affectedPaths
    };
}

function normalizeAdProgramQueue(payload) {
    const items = Array.isArray(payload?.items) ? payload.items : Array.isArray(payload) ? payload : [];
    return items.map((item, index) => ({
        entryId: String(item?.entryId ?? `queue-${index}`),
        contentId: item?.contentId ? String(item.contentId) : "",
        kind: String(item?.kind ?? "CONTENT").toUpperCase(),
        title: String(item?.title ?? "Queued item"),
        channelLabel: String(item?.channelLabel ?? state.broadcast.channelLabel ?? defaultBroadcastChannelLabel()),
        slotLabel: String(item?.slotLabel ?? "TBD"),
        status: String(item?.status ?? "QUEUED").toUpperCase(),
        detail: String(item?.detail ?? "No queue detail available."),
        playbackUrl: String(item?.playbackUrl ?? ""),
        durationLabel: String(item?.durationLabel ?? "")
    }));
}

function deriveAdIssuePreset(enabled, responseDelayMs, adLoadFailureEnabled) {
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

function demoMonkeySummaryFromConfig(config) {
    if (!config?.enabled) {
        return "Incident simulation is bypassed. Screening, sponsor, and external distribution paths are flowing normally.";
    }

    const effects = [];
    if (config.startupDelayMs > 0) {
        effects.push(`${config.startupDelayMs} ms startup lag`);
    }
    if (config.throttleKbps > 0) {
        effects.push(`${config.throttleKbps} kbps bandwidth clamp`);
    }
    if (config.disconnectAfterKb > 0) {
        effects.push(`connection reset after ${config.disconnectAfterKb} KiB`);
    }
    if (config.playbackFailureEnabled) {
        effects.push("playback responses return HTTP 503");
    }
    if (config.traceMapFailureEnabled) {
        effects.push("trace pivot returns HTTP 503");
    }
    if (config.frontendExceptionEnabled) {
        effects.push("browser exception fires on page load");
    }
    if (config.slowAdEnabled) {
        effects.push("ad decisioning is delayed");
    }
    if (config.adLoadFailureEnabled) {
        effects.push("ad loads fail before the sponsor clip plays");
    }
    if (config.nextBreakOnlyEnabled) {
        effects.push("auto-clears after the next sponsor pod");
    }

    if (!effects.length) {
        return "Incident simulation is armed, but no faults are configured.";
    }

    return `Incident simulation is active with ${effects.join(", ")}.`;
}

function normalizeDemoMonkeyStatus(payload) {
    const fallback = defaultDemoMonkeyState();
    const enabled = Boolean(payload?.enabled);
    const startupDelayMs = Number.parseInt(payload?.startupDelayMs, 10) || 0;
    const throttleKbps = Number.parseInt(payload?.throttleKbps, 10) || 0;
    const disconnectAfterKb = Number.parseInt(payload?.disconnectAfterKb, 10) || 0;

    const normalized = {
        enabled,
        preset: String(payload?.preset ?? (enabled ? "custom" : "clear")).trim().toLowerCase() || (enabled ? "custom" : "clear"),
        startupDelayMs,
        throttleKbps,
        disconnectAfterKb,
        playbackFailureEnabled: Boolean(payload?.playbackFailureEnabled),
        traceMapFailureEnabled: Boolean(payload?.traceMapFailureEnabled),
        frontendExceptionEnabled: Boolean(payload?.frontendExceptionEnabled),
        slowAdEnabled: Boolean(payload?.slowAdEnabled),
        adLoadFailureEnabled: Boolean(payload?.adLoadFailureEnabled),
        nextBreakOnlyEnabled: Boolean(payload?.nextBreakOnlyEnabled),
        autoClearAt: String(payload?.autoClearAt ?? fallback.autoClearAt),
        updatedAt: String(payload?.updatedAt ?? fallback.updatedAt),
        summary: String(payload?.summary ?? ""),
        scope: String(payload?.scope ?? fallback.scope),
        affectedPaths: Array.isArray(payload?.affectedPaths) && payload.affectedPaths.length
            ? payload.affectedPaths.map((path) => String(path))
            : fallback.affectedPaths
    };

    if (!normalized.summary) {
        normalized.summary = demoMonkeySummaryFromConfig(normalized);
    }

    return normalized;
}

function buildLibrary(liveItems = []) {
    const sourceItems = Array.isArray(liveItems) && liveItems.length ? liveItems : seedContent;
    return sourceItems.map((item, index) => normalizeItem(item, index));
}

function currentItem() {
    return state.library.find((item) => item.id === state.selectedId) ?? state.library[0] ?? null;
}

function syncSelection() {
    if (!state.library.length) {
        state.selectedId = "";
        return null;
    }

    if (!state.library.some((item) => item.id === state.selectedId)) {
        state.selectedId = state.library[0].id;
    }

    persistSelectedId();
    return currentItem();
}

function playerItem() {
    if (state.playerMeta?.id) {
        return state.library.find((item) => item.id === state.playerMeta.id) ?? state.playerMeta;
    }

    return currentItem();
}

function watchRecord(id) {
    return id ? state.watchState[id] ?? null : null;
}

function progressPercent(id) {
    const record = watchRecord(id);
    if (!record?.duration) {
        return 0;
    }

    return Math.max(0, Math.min(100, Math.round((record.currentTime / record.duration) * 100)));
}

function formatPercent(value) {
    return `${Math.round(value)}%`;
}

function formatTime(seconds) {
    const totalSeconds = Math.max(0, Math.floor(seconds));
    const minutes = Math.floor(totalSeconds / 60);
    const remainingSeconds = totalSeconds % 60;
    return `${minutes}:${String(remainingSeconds).padStart(2, "0")}`;
}

function formatClock(dateLike) {
    const date = dateLike instanceof Date ? dateLike : new Date(dateLike);
    return date.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

function formatOptionalTime(dateLike, fallback = "TBD") {
    if (!dateLike) {
        return fallback;
    }

    const date = dateLike instanceof Date ? dateLike : new Date(dateLike);
    if (Number.isNaN(date.getTime())) {
        return fallback;
    }

    return formatClock(date);
}

function formatDateTimeLabel(dateLike) {
    if (!dateLike) {
        return "Awaiting operator change";
    }

    const date = dateLike instanceof Date ? dateLike : new Date(dateLike);
    if (Number.isNaN(date.getTime())) {
        return String(dateLike);
    }

    return date.toLocaleString([], { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function formatDateLabel(dateString) {
    if (!dateString) {
        return "No due date";
    }

    const date = new Date(`${dateString}T00:00:00`);
    if (Number.isNaN(date.getTime())) {
        return dateString;
    }

    return date.toLocaleDateString([], { month: "short", day: "numeric", year: "numeric" });
}

function formatMoney(amount, currency = "USD") {
    const value = Number.isFinite(Number(amount)) ? Number(amount) : 0;
    try {
        return new Intl.NumberFormat("en-US", {
            style: "currency",
            currency
        }).format(value);
    } catch (error) {
        console.warn("Failed to format billing amount.", error);
        return `$${value.toFixed(2)}`;
    }
}

function pluralize(count, singular, plural = `${singular}s`) {
    return `${count} ${count === 1 ? singular : plural}`;
}

function formatDemoMonkeyPreset(preset) {
    const key = String(preset ?? "").trim().toLowerCase();
    if (demoMonkeyPresets[key]?.label) {
        return demoMonkeyPresets[key].label;
    }

    return key
        ? key
            .split(/[-_]/)
            .filter(Boolean)
            .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
            .join(" ")
        : "Custom";
}

function demoMonkeyTone(config = state.demoMonkey) {
    if (!config?.enabled) {
        return "standby";
    }

    if (config.playbackFailureEnabled || config.traceMapFailureEnabled || config.frontendExceptionEnabled) {
        return "blocked";
    }

    if (config.adLoadFailureEnabled) {
        return "blocked";
    }

    if (config.disconnectAfterKb > 0) {
        return "blocked";
    }

    if (config.throttleKbps > 0 || config.startupDelayMs > 0 || config.slowAdEnabled) {
        return "risk";
    }

    return "ready";
}

function maybeEmitDemoMonkeyFrontendException(config = state.demoMonkey) {
    if (!config?.enabled || !config.frontendExceptionEnabled) {
        return;
    }

    const faultKey = [config.updatedAt, config.preset, window.location.pathname].join(":");
    if (!config.updatedAt || lastDemoMonkeyFrontendFaultKey === faultKey) {
        return;
    }

    lastDemoMonkeyFrontendFaultKey = faultKey;
    window.setTimeout(() => {
        throw new Error(`Incident simulation injected browser exception for ${formatDemoMonkeyPreset(config.preset)} on ${window.location.pathname}.`);
    }, 0);
}

function savedItems() {
    return state.library.filter((item) => state.myList.has(item.id));
}

function activeRtspJobs() {
    return state.rtspJobs.filter((job) => job.status && job.status !== "READY" && job.status !== "ERROR");
}

function failedRtspJobs() {
    return state.rtspJobs.filter((job) => job.status === "ERROR");
}

function readyRtspJobs() {
    return state.rtspJobs.filter((job) => job.status === "READY");
}

function isRtspJobActivatable(job) {
    const status = String(job?.status ?? "").toUpperCase();
    return status === "CAPTURING" || status === "TRANSCODING" || status === "READY";
}

function catalogSourceLabel() {
    if (state.catalogSource === "live") {
        return "Protected live catalog";
    }
    if (state.catalogSource === "locked") {
        return "Protected workspace";
    }
    return "Fallback program stack";
}

function distributionLabel() {
    return runtimeConfig.distributionModel ?? "Single public frontend with internal content and media services";
}

function readyAssetCount() {
    if (!state.session) {
        return 0;
    }

    return state.library.filter((item) => Boolean(item.watchUrl)).length;
}

function operatorRoleLabel() {
    return state.session?.roleLabel ?? runtimeConfig.defaultOperatorRole ?? "Programming Operations";
}

function currentRole() {
    return state.session?.role ?? "staff_operator";
}

function hasCapability(capability) {
    const permissions = roleCapabilities[currentRole()] ?? roleCapabilities.staff_operator;
    return Boolean(state.session) && permissions.includes(capability);
}

function derivePageFromHash(hash = window.location.hash) {
    const page = String(hash ?? "")
        .replace(/^#/, "")
        .trim()
        .toLowerCase();

    return ["home", "player", "library", "operations", "billing"].includes(page) ? page : "home";
}

function pageAvailable(page) {
    if (page === "operations") {
        return hasCapability("operations");
    }

    if (page === "billing") {
        return hasCapability("billing");
    }

    return true;
}

function ensurePageAccess() {
    const requestedPage = derivePageFromHash();
    state.currentPage = pageAvailable(requestedPage) ? requestedPage : "home";

    if (window.location.hash !== `#${state.currentPage}`) {
        window.history.replaceState(null, "", `#${state.currentPage}`);
    }
}

function renderPageNavigation() {
    document.body.dataset.page = state.currentPage;

    for (const link of elements.navLinks) {
        link.classList.toggle("is-active", link.dataset.pageLink === state.currentPage);
    }

    for (const node of elements.pageNodes) {
        node.hidden = node.dataset.page !== state.currentPage;
    }
}

function goToPage(page) {
    state.currentPage = page;
    if (window.location.hash !== `#${page}`) {
        window.location.hash = page;
        return;
    }

    renderPageNavigation();
}

function normalizeSession(payload) {
    const user = payload?.user ?? payload ?? {};
    const role = user.role ?? "staff_operator";

    return {
        email: String(user.email ?? "").trim().toLowerCase(),
        name: user.name ?? "Acme Viewer",
        role,
        roleLabel: user.roleLabel ?? role
            .split("_")
            .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
            .join(" "),
        expiresAt: payload?.expiresAt ?? null
    };
}

function clearSessionState(removeStorage = true) {
    elements.moviePlayer.pause();
    if (removeStorage) {
        localStorage.removeItem(storageKeys.session);
    }

    state.session = null;
    state.myList = new Set();
    state.watchState = {};
    state.playerMeta = null;
    state.playerUrl = "";
    state.selectedId = state.library[0]?.id ?? "";
    state.pendingResumeId = "";
    state.lastProgressCommit = 0;
    state.rtspJobs = [];
    state.adQueue = [];
    state.adIssue = defaultAdIssueState();
    state.billing = {
        source: "locked",
        invoices: [],
        selectedAccountId: "all",
        selectedInvoiceId: "",
        statusFilter: "ALL",
        overdueOnly: false,
        lastSyncLabel: "Awaiting sync"
    };
    stopRtspPolling();
    setLaunchMessage("", false);
}

function setRtspFormMessage(message, isError = false) {
    elements.rtspFormMessage.textContent = message;
    elements.rtspFormMessage.dataset.state = isError ? "error" : "success";
}

function setAdIssueMessage(message, isError = false) {
    elements.adIssueMessage.textContent = message;
    elements.adIssueMessage.dataset.state = isError ? "error" : "success";
}

function setDemoMonkeyMessage(message, isError = false) {
    elements.demoMonkeyMessage.textContent = message;
    elements.demoMonkeyMessage.dataset.state = isError ? "error" : "success";
}

function setBillingFormMessage(message, isError = false) {
    elements.billingFormMessage.textContent = message;
    elements.billingFormMessage.dataset.state = isError ? "error" : "success";
}

function stopRtspPolling() {
    if (rtspPollHandle) {
        window.clearInterval(rtspPollHandle);
        rtspPollHandle = null;
    }
}

function startRtspPolling() {
    stopRtspPolling();
    if (!state.session) {
        return;
    }

    rtspPollHandle = window.setInterval(() => {
        Promise.all([
            loadRtspJobs({ silent: true }),
            loadBroadcastStatus({ silent: true }),
            loadAdProgramQueue({ silent: true }),
            loadAdIssueStatus({ silent: true })
        ]).catch((error) => {
            console.warn("Failed to refresh RTSP ingest jobs.", error);
        });
    }, 15000);
}

async function probeServiceEndpoint(url) {
    if (!url) {
        return "blocked";
    }

    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), runtimeConfig.probeTimeoutMs ?? 4500);

    try {
        const response = await fetch(url, {
            cache: "no-store",
            credentials: "same-origin",
            signal: controller.signal
        });

        if (response.ok) {
            return "ready";
        }

        if (response.status === 401 || response.status === 403) {
            return "locked";
        }

        return "blocked";
    } catch (error) {
        console.warn(`Service probe failed for ${url}.`, error);
        return "blocked";
    } finally {
        window.clearTimeout(timeout);
    }
}

async function refreshServiceHealth() {
    const [auth, content, media, ads, billing] = await Promise.all([
        probeServiceEndpoint(runtimeConfig.authHealthUrl),
        probeServiceEndpoint(runtimeConfig.contentHealthUrl),
        probeServiceEndpoint(runtimeConfig.mediaHealthUrl),
        probeServiceEndpoint(runtimeConfig.adHealthUrl),
        probeServiceEndpoint(runtimeConfig.billingHealthUrl)
    ]);

    state.serviceHealth = { auth, content, media, ads, billing };
    state.lastHealthCheck = Date.now();
}

function renderTags(tags) {
    return tags.map((tag) => `<span>${escapeHtml(tag)}</span>`).join("");
}

function metaFromItem(item) {
    return {
        id: item.id,
        title: item.title,
        description: item.description,
        badges: [item.channelLabel, item.signalProfile, item.ageRating, item.runtimeLabel],
        kind: "catalog"
    };
}

function continueWatchingItems() {
    return state.library
        .filter((item) => {
            const progress = progressPercent(item.id);
            return progress > 4 && progress < 98;
        })
        .sort((left, right) => (watchRecord(right.id)?.updatedAt ?? 0) - (watchRecord(left.id)?.updatedAt ?? 0));
}

function filteredLibrary() {
    const search = state.searchTerm.trim().toLowerCase();

    return state.library.filter((item) => {
        const genreMatch = state.activeGenre === "All" || item.genreList.includes(state.activeGenre);
        if (!genreMatch) {
            return false;
        }

        if (!search) {
            return true;
        }

        const haystack = [
            item.title,
            item.description,
            item.headline,
            item.featureLine,
            item.type,
            ...item.genreList
        ].join(" ").toLowerCase();

        return haystack.includes(search);
    });
}

function derivedGenres() {
    const counts = new Map();

    for (const item of state.library) {
        for (const genre of item.genreList) {
            counts.set(genre, (counts.get(genre) ?? 0) + 1);
        }
    }

    return [...counts.entries()]
        .sort((left, right) => right[1] - left[1])
        .map(([genre]) => genre)
        .slice(0, 8);
}

function featuredShelfItems() {
    const selected = currentItem();
    return filteredLibrary()
        .filter((item) => item.id !== selected?.id)
        .slice(0, 8);
}

function buildBroadcastLineup() {
    if (!state.library.length) {
        return [];
    }

    const rotatingIndex = new Date().getHours() * 2 + Math.floor(new Date().getMinutes() / 30);
    const rotatingItem = (offset) => state.library[(rotatingIndex + offset) % state.library.length];
    const selected = currentItem() ?? state.library[0];
    const reviewQueue = savedItems();
    const inProgress = continueWatchingItems();
    const readyJobs = readyRtspJobs();
    const liveJobs = activeRtspJobs();
    const liveJob = readyJobs[0] ?? liveJobs[0] ?? null;
    const adStatus = state.broadcast.adStatus ?? defaultAdStatus();
    const liveItem = liveJob?.contentId
        ? state.library.find((item) => item.id === liveJob.contentId) ?? rotatingItem(1)
        : rotatingItem(1);
    const sponsorNote = adStatus.state === "IN_BREAK"
        ? `${adStatus.sponsorLabel} is live inside ${adStatus.podLabel.toLowerCase()}.`
        : `${adStatus.podLabel} is staged for ${formatOptionalTime(adStatus.breakStartAt, "the next break")} with ${adStatus.sponsorLabel}.`;

    return [
        {
            id: "prime",
            label: runtimeConfig.channelLabels?.[0] ?? "Prime East",
            desk: "Linear playout",
            tone: "ready",
            status: "On air",
            now: selected,
            next: rotatingItem(1),
            note: `${selected.programmingTrack} is carrying the main programmed feed. ${sponsorNote}`
        },
        {
            id: "events",
            label: runtimeConfig.channelLabels?.[1] ?? "Events",
            desk: "Contribution ingest",
            tone: readyJobs.length ? "ready" : liveJobs.length ? "risk" : "standby",
            status: readyJobs.length ? "Ingest ready" : liveJobs.length ? liveJobs[0].status : "Standby",
            now: liveItem,
            next: rotatingItem(2),
            note: liveJob
                ? `${liveJob.targetTitle} is tied to the ingest desk and can be routed into playback.`
                : "No active RTSP contribution feed is attached right now."
        },
        {
            id: "review",
            label: runtimeConfig.channelLabels?.[2] ?? "Review Desk",
            desk: "Editorial QA",
            tone: inProgress.length || reviewQueue.length ? "ready" : "standby",
            status: inProgress.length ? "Resume review" : reviewQueue.length ? "Queue ready" : "Hold",
            now: inProgress[0] ?? reviewQueue[0] ?? rotatingItem(3),
            next: reviewQueue[1] ?? rotatingItem(0),
            note: inProgress.length
                ? `${inProgress[0].title} already has saved progress and is ready for follow-up review.`
                : reviewQueue.length
                    ? `${reviewQueue[0].title} is staged in the personal review queue.`
                    : "Queue a title to build out the review desk."
        }
    ];
}

function startOfHalfHour(reference = new Date()) {
    return new Date(
        reference.getFullYear(),
        reference.getMonth(),
        reference.getDate(),
        reference.getHours(),
        reference.getMinutes() < 30 ? 0 : 30,
        0,
        0
    );
}

function buildRundown() {
    if (state.adQueue.length) {
        return state.adQueue.map((entry) => ({
            id: entry.entryId,
            startAt: entry.entryId,
            channelLabel: entry.channelLabel,
            desk: entry.kind === "AD" ? "Ad decisioning" : "Program queue",
            status: entry.status,
            tone: entry.status === "FAILED_TO_LOAD"
                ? "blocked"
                : entry.status === "DEGRADED"
                    ? "risk"
                    : "ready",
            slotLabel: entry.slotLabel,
            title: entry.kind === "AD"
                ? `${entry.title} · ${entry.durationLabel}`
                : entry.title,
            detail: entry.detail,
            routeable: Boolean(entry.playbackUrl),
            routeLabel: entry.kind === "AD" ? "Preview" : "Route",
            playbackUrl: entry.playbackUrl,
            metaTitle: entry.kind === "AD" ? `${entry.title} Sponsor Clip` : entry.title,
            metaDescription: entry.detail,
            metaKind: entry.kind.toLowerCase(),
            item: entry.contentId ? state.library.find((item) => item.id === entry.contentId) ?? null : null
        }));
    }

    const lineup = buildBroadcastLineup();
    const slotStart = startOfHalfHour();
    const slotMinutes = 30;
    const contentSlots = lineup.flatMap((channel, channelIndex) => [channel.now, channel.next].map((item, slotIndex) => {
        const start = new Date(slotStart.getTime() + (channelIndex * 2 + slotIndex) * slotMinutes * 60 * 1000);
        const end = new Date(start.getTime() + slotMinutes * 60 * 1000);
        return {
            id: `${channel.id}-${slotIndex}-${item?.id ?? slotIndex}`,
            startAt: start.toISOString(),
            channelLabel: channel.label,
            desk: channel.desk,
            status: slotIndex === 0 ? channel.status : "Next",
            tone: slotIndex === 0 ? channel.tone : "standby",
            slotLabel: `${formatClock(start)} - ${formatClock(end)}`,
            item,
            playbackUrl: item?.watchUrl ?? "",
            metaTitle: item?.title ?? "Queued asset",
            metaDescription: item?.description ?? "No detail available.",
            metaKind: "catalog",
            routeable: Boolean(item?.id),
            routeLabel: "Route"
        };
    }));
    const adStatus = state.broadcast.adStatus ?? defaultAdStatus();
    const adSlots = adStatus.breakStartAt
        ? [
            {
                id: `ad-break-${adStatus.breakStartAt}`,
                startAt: adStatus.breakStartAt,
                channelLabel: state.broadcast.channelLabel ?? runtimeConfig.channelLabels?.[0] ?? "Acme Network East",
                desk: "Ad decisioning",
                status: adStatus.state === "IN_BREAK" ? "Sponsor pod live" : "Next ad break",
                tone: adStatus.state === "IN_BREAK" ? "ready" : "standby",
                slotLabel: `${formatOptionalTime(adStatus.breakStartAt)} - ${formatOptionalTime(adStatus.breakEndAt)}`,
                title: `${adStatus.podLabel} · ${adStatus.sponsorLabel}`,
                detail: adStatus.detail,
                playbackUrl: "",
                metaTitle: `${adStatus.podLabel} · ${adStatus.sponsorLabel}`,
                metaDescription: adStatus.detail,
                metaKind: "ad",
                routeable: false,
                routeLabel: "Monitor"
            }
        ]
        : [];

    return [...contentSlots, ...adSlots].sort((left, right) => {
        const leftStart = new Date(left.startAt).getTime();
        const rightStart = new Date(right.startAt).getTime();
        return leftStart - rightStart;
    });
}

function billingAmount(value) {
    const parsed = Number.parseFloat(String(value ?? "0"));
    if (!Number.isFinite(parsed)) {
        return 0;
    }

    return Math.round(parsed * 100) / 100;
}

function createClientId(prefix) {
    if (window.crypto?.randomUUID) {
        return window.crypto.randomUUID();
    }

    return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
}

function isoDateFromOffset(days) {
    const date = new Date();
    date.setHours(12, 0, 0, 0);
    date.setDate(date.getDate() + days);
    return date.toISOString().slice(0, 10);
}

function isoStampFromOffset(hours) {
    return new Date(Date.now() + hours * 60 * 60 * 1000).toISOString();
}

function billingAccountInfo(userId) {
    return seedBillingAccounts.find((account) => account.id === userId) ?? {
        id: userId,
        label: userId ? `Account ${String(userId).slice(0, 8).toUpperCase()}` : "Unknown account",
        desk: "External account",
        contact: "Not mapped"
    };
}

function billingAccountDirectory() {
    const accounts = new Map(seedBillingAccounts.map((account) => [account.id, account]));
    for (const invoice of state.billing.invoices) {
        if (!accounts.has(invoice.userId)) {
            accounts.set(invoice.userId, billingAccountInfo(invoice.userId));
        }
    }
    return [...accounts.values()];
}

function deriveBillingStatus(invoice) {
    const currentStatus = String(invoice?.status ?? "OPEN").toUpperCase();
    if (currentStatus === "PAID" || currentStatus === "VOID" || currentStatus === "DRAFT") {
        return currentStatus;
    }

    if (!invoice?.dueDate) {
        return currentStatus;
    }

    return invoice.dueDate < isoDateFromOffset(0) ? "PAST_DUE" : currentStatus === "PAST_DUE" ? "OPEN" : currentStatus;
}

function billingStatusTone(status) {
    switch (status) {
        case "PAID":
            return "ready";
        case "PAST_DUE":
            return "blocked";
        case "VOID":
            return "standby";
        case "DRAFT":
            return "standby";
        default:
            return "risk";
    }
}

function billingStatusLabel(status) {
    return String(status ?? "OPEN").replaceAll("_", " ");
}

function normalizeBillingLineItem(lineItem, index = 0) {
    const quantity = Math.max(1, Number.parseInt(lineItem?.quantity, 10) || 1);
    const unitAmount = billingAmount(lineItem?.unitAmount);
    const lineTotal = lineItem?.lineTotal != null ? billingAmount(lineItem.lineTotal) : billingAmount(quantity * unitAmount);

    return {
        id: String(lineItem?.id ?? createClientId(`line-${index}`)),
        title: lineItem?.title ?? `Line item ${index + 1}`,
        description: lineItem?.description ?? "",
        quantity,
        unitAmount,
        lineTotal,
        servicePeriodStart: lineItem?.servicePeriodStart ?? null,
        servicePeriodEnd: lineItem?.servicePeriodEnd ?? null
    };
}

function normalizeBillingInvoice(invoice, index = 0) {
    const lineItems = Array.isArray(invoice?.lineItems) && invoice.lineItems.length
        ? invoice.lineItems.map((lineItem, lineIndex) => normalizeBillingLineItem(lineItem, lineIndex))
        : [];

    const subtotalAmount = invoice?.subtotalAmount != null
        ? billingAmount(invoice.subtotalAmount)
        : billingAmount(lineItems.reduce((total, lineItem) => total + lineItem.lineTotal, 0));
    const taxAmount = billingAmount(invoice?.taxAmount);
    const discountAmount = billingAmount(invoice?.discountAmount);
    const totalAmount = invoice?.totalAmount != null
        ? billingAmount(invoice.totalAmount)
        : billingAmount(Math.max(0, subtotalAmount + taxAmount - discountAmount));
    const provisionalStatus = String(invoice?.status ?? "OPEN").toUpperCase();
    const effectiveStatus = deriveBillingStatus({
        status: provisionalStatus,
        dueDate: invoice?.dueDate ?? isoDateFromOffset(10)
    });
    const balanceDue = effectiveStatus === "PAID" || effectiveStatus === "VOID"
        ? 0
        : invoice?.balanceDue != null
            ? billingAmount(invoice.balanceDue)
            : totalAmount;
    const account = billingAccountInfo(String(invoice?.userId ?? seedBillingAccounts[0].id));

    return {
        id: String(invoice?.id ?? createClientId(`invoice-${index}`)),
        invoiceNumber: String(invoice?.invoiceNumber ?? `BILL-${isoDateFromOffset(0).replaceAll("-", "")}-${String(index + 1).padStart(3, "0")}`),
        userId: String(invoice?.userId ?? seedBillingAccounts[0].id),
        orderId: invoice?.orderId ?? null,
        subscriptionId: invoice?.subscriptionId ?? null,
        status: effectiveStatus,
        billingCycle: String(invoice?.billingCycle ?? "MONTHLY").toUpperCase(),
        currency: String(invoice?.currency ?? "USD").toUpperCase(),
        subtotalAmount,
        taxAmount,
        discountAmount,
        totalAmount,
        balanceDue,
        issuedDate: invoice?.issuedDate ?? isoDateFromOffset(-2),
        dueDate: invoice?.dueDate ?? isoDateFromOffset(10),
        servicePeriodStart: invoice?.servicePeriodStart ?? null,
        servicePeriodEnd: invoice?.servicePeriodEnd ?? null,
        externalPaymentReference: invoice?.externalPaymentReference ?? null,
        notes: invoice?.notes ?? "",
        createdAt: invoice?.createdAt ?? isoStampFromOffset(-24 * (index + 1)),
        updatedAt: invoice?.updatedAt ?? invoice?.createdAt ?? isoStampFromOffset(-12 * (index + 1)),
        lineItems,
        accountLabel: account.label,
        accountDesk: account.desk
    };
}

function buildSeedBillingInvoices() {
    return [
        normalizeBillingInvoice({
            id: "6b544210-a532-4705-bf22-3aa1d3931001",
            invoiceNumber: `BILL-${isoDateFromOffset(0).replaceAll("-", "")}-041`,
            userId: seedBillingAccounts[0].id,
            status: "OPEN",
            billingCycle: "MONTHLY",
            currency: "USD",
            issuedDate: isoDateFromOffset(-3),
            dueDate: isoDateFromOffset(9),
            servicePeriodStart: isoDateFromOffset(-33),
            servicePeriodEnd: isoDateFromOffset(-2),
            taxAmount: 650,
            notes: "Regional sports carriage and late-night replay rights for March programming.",
            createdAt: isoStampFromOffset(-96),
            updatedAt: isoStampFromOffset(-18),
            lineItems: [
                {
                    id: "line-sports-1",
                    title: "Prime East carriage window",
                    description: "Monthly linear carriage allocation for the East sports feed.",
                    quantity: 1,
                    unitAmount: 12000
                },
                {
                    id: "line-sports-2",
                    title: "Replay rights package",
                    description: "Short-form replay clearance for nightly wrap programs.",
                    quantity: 1,
                    unitAmount: 5800
                }
            ]
        }),
        normalizeBillingInvoice({
            id: "6b544210-a532-4705-bf22-3aa1d3931002",
            invoiceNumber: `BILL-${isoDateFromOffset(0).replaceAll("-", "")}-042`,
            userId: seedBillingAccounts[1].id,
            status: "OPEN",
            billingCycle: "MONTHLY",
            currency: "USD",
            issuedDate: isoDateFromOffset(-21),
            dueDate: isoDateFromOffset(-4),
            servicePeriodStart: isoDateFromOffset(-52),
            servicePeriodEnd: isoDateFromOffset(-22),
            taxAmount: 240,
            notes: "Always-on forecast desk support, graphics feed, and emergency weather cut-in support.",
            createdAt: isoStampFromOffset(-220),
            updatedAt: isoStampFromOffset(-90),
            lineItems: [
                {
                    id: "line-weather-1",
                    title: "Weather graphics service",
                    description: "Forecast desk package with lower-third and map updates.",
                    quantity: 1,
                    unitAmount: 4200
                },
                {
                    id: "line-weather-2",
                    title: "Emergency alert readiness",
                    description: "Standby capacity for storm cut-ins and operator escalation.",
                    quantity: 1,
                    unitAmount: 1800
                }
            ]
        }),
        normalizeBillingInvoice({
            id: "6b544210-a532-4705-bf22-3aa1d3931003",
            invoiceNumber: `BILL-${isoDateFromOffset(0).replaceAll("-", "")}-043`,
            userId: seedBillingAccounts[2].id,
            status: "PAID",
            billingCycle: "ONE_TIME",
            currency: "USD",
            issuedDate: isoDateFromOffset(-32),
            dueDate: isoDateFromOffset(-12),
            servicePeriodStart: isoDateFromOffset(-61),
            servicePeriodEnd: isoDateFromOffset(-32),
            notes: "Festival coverage package settled by bank transfer.",
            externalPaymentReference: "ACH-984413",
            createdAt: isoStampFromOffset(-320),
            updatedAt: isoStampFromOffset(-48),
            lineItems: [
                {
                    id: "line-arts-1",
                    title: "Festival live window",
                    description: "Two-night event coverage with arts desk staffing.",
                    quantity: 1,
                    unitAmount: 4980
                }
            ]
        }),
        normalizeBillingInvoice({
            id: "6b544210-a532-4705-bf22-3aa1d3931004",
            invoiceNumber: `BILL-${isoDateFromOffset(0).replaceAll("-", "")}-044`,
            userId: seedBillingAccounts[3].id,
            status: "OPEN",
            billingCycle: "ONE_TIME",
            currency: "USD",
            issuedDate: isoDateFromOffset(-1),
            dueDate: isoDateFromOffset(14),
            servicePeriodStart: isoDateFromOffset(0),
            servicePeriodEnd: isoDateFromOffset(5),
            discountAmount: 250,
            notes: "Temporary event pop-up feed for opening-week sponsor activations.",
            createdAt: isoStampFromOffset(-16),
            updatedAt: isoStampFromOffset(-6),
            lineItems: [
                {
                    id: "line-popup-1",
                    title: "Event transmission window",
                    description: "Five-day event pop-up distribution package.",
                    quantity: 1,
                    unitAmount: 7500
                },
                {
                    id: "line-popup-2",
                    title: "On-site operator block",
                    description: "Two operator shifts for control-room coordination.",
                    quantity: 2,
                    unitAmount: 1250
                }
            ]
        }),
        normalizeBillingInvoice({
            id: "6b544210-a532-4705-bf22-3aa1d3931005",
            invoiceNumber: `BILL-${isoDateFromOffset(0).replaceAll("-", "")}-045`,
            userId: seedBillingAccounts[0].id,
            status: "VOID",
            billingCycle: "MONTHLY",
            currency: "USD",
            issuedDate: isoDateFromOffset(-9),
            dueDate: isoDateFromOffset(6),
            servicePeriodStart: isoDateFromOffset(-39),
            servicePeriodEnd: isoDateFromOffset(-9),
            notes: "Reissued under updated carriage terms after contract change order.",
            createdAt: isoStampFromOffset(-180),
            updatedAt: isoStampFromOffset(-110),
            lineItems: [
                {
                    id: "line-void-1",
                    title: "Superseded carriage package",
                    description: "Cancelled draft after revised commercial agreement.",
                    quantity: 1,
                    unitAmount: 8900
                }
            ]
        })
    ];
}

function readSeedBillingInvoices() {
    const stored = readJson(scopedKey("billingSeedInvoices"), null);
    if (Array.isArray(stored) && stored.length) {
        return stored.map((invoice, index) => normalizeBillingInvoice(invoice, index));
    }

    const seeded = buildSeedBillingInvoices();
    if (state.session) {
        writeJson(scopedKey("billingSeedInvoices"), seeded);
    }
    return seeded;
}

function persistSeedBillingInvoices() {
    if (state.session) {
        writeJson(scopedKey("billingSeedInvoices"), state.billing.invoices);
    }
}

function billingSourceLabel() {
    switch (state.billing.source) {
        case "live":
            return "Live billing ledger";
        case "seed":
            return "Seeded billing preview";
        case "restricted":
            return "Role restricted";
        default:
            return "Awaiting finance access";
    }
}

function sortBillingInvoices(invoices) {
    const rank = {
        PAST_DUE: 0,
        OPEN: 1,
        DRAFT: 2,
        PAID: 3,
        VOID: 4
    };

    return [...invoices].sort((left, right) => {
        const leftStatus = deriveBillingStatus(left);
        const rightStatus = deriveBillingStatus(right);
        const statusDelta = (rank[leftStatus] ?? 9) - (rank[rightStatus] ?? 9);
        if (statusDelta !== 0) {
            return statusDelta;
        }

        const dueDelta = String(left.dueDate ?? "").localeCompare(String(right.dueDate ?? ""));
        if (dueDelta !== 0) {
            return dueDelta;
        }

        return String(right.createdAt ?? "").localeCompare(String(left.createdAt ?? ""));
    });
}

function filteredBillingInvoices() {
    const selectedAccountId = state.billing.selectedAccountId;
    const statusFilter = state.billing.statusFilter;

    return sortBillingInvoices(
        state.billing.invoices.filter((invoice) => {
            const derivedStatus = deriveBillingStatus(invoice);
            if (selectedAccountId !== "all" && invoice.userId !== selectedAccountId) {
                return false;
            }
            if (statusFilter !== "ALL" && derivedStatus !== statusFilter) {
                return false;
            }
            if (state.billing.overdueOnly && derivedStatus !== "PAST_DUE") {
                return false;
            }
            return true;
        })
    );
}

function deriveBillingSummary(invoices) {
    const currency = invoices.map((invoice) => invoice.currency).find(Boolean) ?? "USD";
    const totalOutstanding = invoices.reduce((total, invoice) => total + billingAmount(invoice.balanceDue), 0);
    const totalInvoiced = invoices.reduce((total, invoice) => total + billingAmount(invoice.totalAmount), 0);
    const totalPaid = invoices
        .filter((invoice) => deriveBillingStatus(invoice) === "PAID")
        .reduce((total, invoice) => total + billingAmount(invoice.totalAmount), 0);
    const openCount = invoices.filter((invoice) => deriveBillingStatus(invoice) === "OPEN").length;
    const pastDueCount = invoices.filter((invoice) => deriveBillingStatus(invoice) === "PAST_DUE").length;
    const paidCount = invoices.filter((invoice) => deriveBillingStatus(invoice) === "PAID").length;
    const nextDueDate = sortBillingInvoices(
        invoices.filter((invoice) => {
            const status = deriveBillingStatus(invoice);
            return invoice.balanceDue > 0 && status !== "PAID" && status !== "VOID";
        })
    )[0]?.dueDate ?? null;

    return {
        invoiceCount: invoices.length,
        openCount,
        pastDueCount,
        paidCount,
        totalOutstanding,
        totalInvoiced,
        totalPaid,
        nextDueDate,
        currency
    };
}

function selectedBillingInvoice() {
    return state.billing.invoices.find((invoice) => invoice.id === state.billing.selectedInvoiceId) ?? null;
}

function ensureBillingSelection() {
    const accountIds = new Set(billingAccountDirectory().map((account) => account.id));
    if (state.billing.selectedAccountId !== "all" && !accountIds.has(state.billing.selectedAccountId)) {
        state.billing.selectedAccountId = "all";
    }

    const candidates = filteredBillingInvoices();
    if (!candidates.some((invoice) => invoice.id === state.billing.selectedInvoiceId)) {
        state.billing.selectedInvoiceId = candidates[0]?.id ?? state.billing.invoices[0]?.id ?? "";
    }
}

function serviceWallEntries() {
    const mediaHealth = state.serviceHealth.media;
    const contentHealth = state.serviceHealth.content;
    const authHealth = state.serviceHealth.auth;
    const adHealth = state.serviceHealth.ads;
    const billingHealth = state.serviceHealth.billing;
    const billingSummary = deriveBillingSummary(state.billing.invoices);
    const demoMonkeyHealth = demoMonkeyTone();
    const adStatus = state.broadcast.adStatus ?? defaultAdStatus();
    const adIssue = state.adIssue ?? defaultAdIssueState();

    return [
        {
            label: "Auth edge",
            status: authHealth === "ready" ? (state.session ? "ready" : "standby") : authHealth,
            headline: state.session ? operatorRoleLabel() : authHealth === "ready" ? "Awaiting operator sign-in" : "Unavailable",
            detail: state.session
                ? `${state.session.name} is authenticated and can use the protected workspace.`
                : authHealth === "ready"
                    ? "Authentication is healthy but no operator session is active."
                    : "The sign-in edge did not respond to a health probe."
        },
        {
            label: "Catalog",
            status: contentHealth === "ready"
                ? state.session
                    ? state.catalogSource === "live" ? "ready" : "risk"
                    : "locked"
                : contentHealth,
            headline: state.session
                ? state.catalogSource === "live" ? "Live library online" : "Fallback library only"
                : contentHealth === "ready" ? "Protected until sign-in" : "Unavailable",
            detail: state.session
                ? state.catalogSource === "live"
                    ? `${pluralize(state.library.length, "title")} are available through the backend catalog.`
                    : "The library is running on seeded fallback content while the live catalog is unavailable."
                : contentHealth === "ready"
                    ? "The catalog service is up, but access is intentionally gated."
                    : "The catalog surface did not pass a health probe."
        },
        {
            label: "Media and ingest",
            status: mediaHealth === "ready"
                ? state.demoMonkey.enabled
                    ? demoMonkeyHealth
                    : failedRtspJobs().length
                    ? "blocked"
                    : activeRtspJobs().length ? "risk" : "ready"
                : mediaHealth,
            headline: state.demoMonkey.enabled
                ? `${formatDemoMonkeyPreset(state.demoMonkey.preset)} active`
                : failedRtspJobs().length
                ? `${pluralize(failedRtspJobs().length, "ingest job")} failed`
                : activeRtspJobs().length
                ? `${pluralize(activeRtspJobs().length, "ingest job")} moving`
                : mediaHealth === "ready"
                    ? "Screening edge ready"
                    : "Unavailable",
            detail: state.demoMonkey.enabled
                ? state.demoMonkey.summary
                : failedRtspJobs().length
                ? "One or more RTSP captures failed and need operator review."
                : activeRtspJobs().length
                ? "RTSP contribution work is active, so the media surface is healthy but busy."
                : mediaHealth === "ready"
                    ? "Screening and asset delivery are healthy."
                    : "The media edge did not pass a health probe."
        },
        {
            label: "Ad decisioning",
            status: adHealth === "ready"
                ? adIssue.enabled
                    ? adIssue.adLoadFailureEnabled ? "blocked" : "risk"
                    : adStatus.state === "IN_BREAK" ? "ready" : "standby"
                : adHealth,
            headline: adIssue.enabled
                ? `${adIssue.preset.replaceAll("-", " ")} active`
                : adStatus.state === "IN_BREAK"
                    ? `${adStatus.sponsorLabel} live`
                    : `${adStatus.podLabel} armed`,
            detail: adIssue.enabled
                ? adIssue.summary
                : adStatus.state === "IN_BREAK"
                    ? adStatus.detail
                    : `${adStatus.sponsorLabel} is queued for ${formatOptionalTime(adStatus.breakStartAt, "the next break")} via ${adStatus.decisioningMode.toLowerCase()}.`
        },
        {
            label: "Billing ledger",
            status: billingHealth === "ready"
                ? hasCapability("billing")
                    ? state.billing.source === "live" ? "ready" : "risk"
                    : "locked"
                : billingHealth,
            headline: hasCapability("billing")
                ? state.billing.source === "live"
                    ? `${pluralize(billingSummary.openCount + billingSummary.pastDueCount, "invoice")} active`
                    : "Seeded finance preview"
                : billingHealth === "ready"
                    ? "Restricted desk"
                    : "Unavailable",
            detail: hasCapability("billing")
                ? state.billing.source === "live"
                    ? `${formatMoney(billingSummary.totalOutstanding, billingSummary.currency)} remains outstanding across the live ledger.`
                    : "The finance UI is available, but it is currently showing seeded data until the live billing service is reachable."
                : billingHealth === "ready"
                    ? "Billing health is visible, but invoice tooling is limited to finance and executive roles."
                    : "The billing health endpoint did not pass a probe."
        }
    ];
}

function deskHighlights() {
    const selected = currentItem();
    const liveJob = readyRtspJobs()[0] ?? activeRtspJobs()[0] ?? null;
    const adStatus = state.broadcast.adStatus ?? defaultAdStatus();

    return [
        {
            label: "Selected asset",
            value: selected?.title ?? "No title selected",
            detail: selected ? `${selected.channelLabel} · ${selected.signalProfile}` : "Choose a title to focus the desk."
        },
        {
            label: "Broadcast model",
            value: runtimeConfig.broadcastModel ?? "Protected review workspace",
            detail: runtimeConfig.regionLabel ?? runtimeConfig.environment ?? "Primary Operations"
        },
        {
            label: "Live intake",
            value: liveJob ? `#${liveJob.jobNumber} ${liveJob.status}` : "Idle",
            detail: liveJob
                ? `${liveJob.targetTitle} from ${liveJob.operatorEmail}`
                : "No RTSP contribution feed is registered yet."
        },
        {
            label: "Sponsor pod",
            value: adStatus.sponsorLabel,
            detail: adStatus.state === "IN_BREAK"
                ? `${adStatus.podLabel} is live until ${formatOptionalTime(adStatus.breakEndAt, "the break closes")}.`
                : `${adStatus.podLabel} opens at ${formatOptionalTime(adStatus.breakStartAt, "the next break")} via ${adStatus.decisioningMode.toLowerCase()}.`
        }
    ];
}

function renderHeader() {
    elements.catalogStatus.textContent = state.catalogSource === "live"
        ? "Live schedule"
        : state.catalogSource === "locked"
            ? "Protected workspace"
            : "Fallback schedule";
    elements.sessionChip.innerHTML = state.session
        ? `<strong>${escapeHtml(state.session.name)}</strong><span>${escapeHtml(operatorRoleLabel())}</span>`
        : `<strong>Booth launch</strong><span>Viewer and persona shortcuts</span>`;
    elements.suiteFacility.textContent = runtimeConfig.controlRoomLabel ?? "Acme Broadcast Center";
    elements.suiteRegion.textContent = runtimeConfig.regionLabel ?? runtimeConfig.environment ?? "Primary Operations";
    elements.suiteEnvironment.textContent = runtimeConfig.environment ?? "Primary Operations";
    elements.suitePosture.textContent = state.session ? operatorRoleLabel() : "Attendee launch mode";
    elements.signOut.hidden = !state.session;
    elements.surpriseMe.disabled = !state.session;
    syncPresentationMode();
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
        console.warn("Unable to toggle presentation mode.", error);
        presentationFallbackEnabled = !presentationFallbackEnabled;
    }

    syncPresentationMode();
}

function updateRoleScopedView() {
    for (const node of elements.scopedNodes) {
        const scope = node.dataset.roleScope ?? "";
        node.hidden = !hasCapability(scope);
    }
}

function renderLaunchOverlay() {
    const adStatus = state.broadcast.adStatus ?? defaultAdStatus();
    const publicPageUrl = state.broadcast.publicPageUrl || runtimeConfig.publicBroadcastPageUrl || "/broadcast";
    const traceUrl = runtimeConfig.publicTraceMapUrl || "/api/v1/demo/public/trace-map";
    const publicStatusLabel = state.broadcast.status === "ON_AIR" ? "On air" : "House loop";
    const sponsorCopy = adStatus.state === "IN_BREAK"
        ? `${adStatus.sponsorLabel} is live inside ${adStatus.podLabel.toLowerCase()} until ${formatOptionalTime(adStatus.breakEndAt, "the break closes")}.`
        : `${adStatus.podLabel} opens at ${formatOptionalTime(adStatus.breakStartAt, "the next break")} with ${adStatus.sponsorLabel}.`;

    elements.launchChannelLabel.textContent = `${state.broadcast.title} · ${publicStatusLabel}`;
    elements.launchChannelCopy.textContent = state.broadcast.detail || defaultBroadcastDetail();
    elements.launchSponsorLabel.textContent = `${adStatus.podLabel} · ${adStatus.sponsorLabel}`;
    elements.launchSponsorCopy.textContent = sponsorCopy;
    elements.launchIncidentLabel.textContent = state.demoMonkey.enabled ? formatDemoMonkeyPreset(state.demoMonkey.preset) : "Healthy";
    elements.launchIncidentCopy.textContent = state.demoMonkey.enabled
        ? state.demoMonkey.summary
        : "No demo incident is currently armed on the public viewer path.";
    elements.launchBroadcastLink.href = publicPageUrl;
    elements.launchTraceLink.href = traceUrl;
}

function renderFeatured() {
    const item = currentItem();
    if (!item) {
        return;
    }

    const lineup = buildBroadcastLineup();
    const liveFeeds = state.rtspJobs.length;
    const stagedReviewCount = new Set([
        ...savedItems().map((candidate) => candidate.id),
        ...continueWatchingItems().map((candidate) => candidate.id)
    ]).size;
    const resumeProgress = progressPercent(item.id);
    const spotlightTags = [item.channelLabel, item.programmingTrack, item.readinessLabel];
    spotlightTags.push(resumeProgress > 4 && resumeProgress < 98 ? `Resume ${formatPercent(resumeProgress)}` : item.signalProfile);

    elements.featuredMeta.innerHTML = renderTags([item.channelLabel, item.programmingTrack, item.type, item.year, item.runtimeLabel]);
    elements.featuredTitle.textContent = item.title;
    elements.featuredDescription.textContent = resumeProgress > 4 && resumeProgress < 98
        ? `${item.description} Resume from ${formatPercent(resumeProgress)} while ${item.channelLabel} remains programmed on the desk.`
        : item.description;
    elements.spotlightTitle.textContent = state.session ? "Operations focus" : "Attendee launch briefing";
    elements.spotlightCopy.textContent = state.catalogSource === "live"
        ? `${item.featureLine}. ${item.readinessLabel} on ${item.signalProfile}, and the protected programming catalog is connected.`
        : state.catalogSource === "locked"
            ? `${item.featureLine}. Start with the public broadcast, then use a booth persona to enter the protected operator suite without manual credentials.`
            : `${item.featureLine}. The suite is showing the fallback program stack while the live catalog is unavailable.`;
    elements.spotlightTags.innerHTML = renderTags(spotlightTags);
    elements.myListCount.textContent = state.myList.size ? `${state.myList.size} staged` : "Queue clear";
    elements.selectedTitleLabel.textContent = item.title;
    elements.savedCountLabel.textContent = `${state.myList.size} staged`;
    elements.saveFeatured.textContent = state.myList.has(item.id) ? "Staged" : "Stage for desk";
    elements.heroReadyCount.textContent = `${lineup.length}`;
    elements.heroActiveCount.textContent = `${liveFeeds}`;
    elements.heroQueueCount.textContent = `${stagedReviewCount}`;
}

function renderPlayerMeta() {
    const activeItem = playerItem();
    const record = watchRecord(activeItem?.id);
    const badgeList = activeItem?.badges
        ?? (activeItem ? [activeItem.channelLabel, activeItem.signalProfile, activeItem.ageRating, activeItem.runtimeLabel] : runtimeConfig.demoMovieBadges ?? ["House asset"]);

    elements.movieTitle.textContent = activeItem?.title ?? runtimeConfig.demoMovieTitle ?? "Operator Screening";
    elements.movieDescription.textContent = activeItem?.description ?? runtimeConfig.demoMovieDescription ?? "Select an asset to begin screening playback.";
    elements.movieBadges.innerHTML = renderTags(badgeList);

    if (record?.duration) {
        elements.playbackProgressLabel.textContent = formatPercent(progressPercent(activeItem.id));
        elements.playbackProgressFill.style.width = `${progressPercent(activeItem.id)}%`;
    } else {
        elements.playbackProgressLabel.textContent = "0%";
        elements.playbackProgressFill.style.width = "0%";
    }

    elements.playerUrl.value = state.playerUrl;
}

function renderMyListPreview() {
    const items = state.library.filter((item) => state.myList.has(item.id)).slice(0, 4);

    if (!items.length) {
        elements.myListPreview.innerHTML = `<div class="compact-empty">Stage assets here so they are ready for desk review.</div>`;
        return;
    }

    elements.myListPreview.innerHTML = items
        .map((item) => {
            const progress = progressPercent(item.id);
            const detail = progress > 4 && progress < 98
                ? `Resume from ${formatPercent(progress)}`
                : `${item.channelLabel} · ${item.readinessLabel}`;

            return `
                <button class="compact-item" type="button" data-action="play" data-id="${escapeAttribute(item.id)}">
                    <strong>${escapeHtml(item.title)}</strong>
                    <span>${escapeHtml(detail)}</span>
                </button>
            `;
        })
        .join("");
}

function renderGenreChips() {
    const genres = ["All", ...derivedGenres()];
    elements.genreChips.innerHTML = genres
        .map(
            (genre) => `
                <button
                    type="button"
                    data-genre="${escapeAttribute(genre)}"
                    class="${genre === state.activeGenre ? "is-active" : ""}"
                >
                    ${escapeHtml(genre)}
                </button>
            `
        )
        .join("");
}

function cardMarkup(item) {
    const progress = progressPercent(item.id);
    const primaryLabel = progress > 4 && progress < 98 ? "Resume" : "Route";
    const isSaved = state.myList.has(item.id);
    const isSelected = item.id === state.selectedId;
    const badges = [item.channelLabel, item.readinessLabel, item.ageRating];

    return `
        <article class="media-card ${isSelected ? "is-selected" : ""}" data-id="${escapeAttribute(item.id)}">
            <div class="media-art" style="background:${escapeAttribute(item.posterTone)}">
                <div class="media-topline">${escapeHtml(isSelected ? "Desk focus" : item.programmingTrack ?? item.headline)}</div>
                <div class="media-actions">
                    <button class="media-button is-primary" type="button" data-action="play" data-id="${escapeAttribute(item.id)}">${primaryLabel}</button>
                    <button class="media-button" type="button" data-action="toggle-list" data-id="${escapeAttribute(item.id)}">${isSaved ? "Queued" : "Queue"}</button>
                </div>
            </div>

            <div class="media-copy">
                <div class="media-meta">
                    <span>${escapeHtml(item.type)}</span>
                    <span>${escapeHtml(item.signalProfile)}</span>
                </div>
                <h3>${escapeHtml(item.title)}</h3>
                <p>${escapeHtml(item.description)}</p>
                <div class="tag-row">${renderTags(badges)}</div>
            </div>

            ${progress
                ? `
                    <div class="card-progress">
                        <strong>${progress > 98 ? "Completed" : `Resume from ${formatPercent(progress)}`}</strong>
                        <div class="card-progress-track">
                            <span style="width:${progress}%"></span>
                        </div>
                    </div>
                `
                : ""}
        </article>
    `;
}

function renderRow(container, items, emptyMessage) {
    if (!items.length) {
        container.innerHTML = `<div class="empty-row">${escapeHtml(emptyMessage)}</div>`;
        return;
    }

    container.innerHTML = items.map(cardMarkup).join("");
}

function renderLibrary() {
    const continueItems = continueWatchingItems();
    const featureItems = featuredShelfItems();
    const libraryItems = filteredLibrary();

    elements.continueCopy.textContent = continueItems.length
        ? "Resume assets already in operator review."
        : "Route an asset to screening and it will appear here.";
    elements.featuredLineupCopy.textContent = featureItems.length
        ? "Prime, event, and review desks are surfacing these priority assets."
        : "The active focus asset is carrying the desk right now.";
    elements.catalogStatusCopy.textContent = state.catalogSource === "live"
        ? "You are signed in and connected to the protected Acme programming catalog."
        : state.catalogSource === "locked"
            ? "Sign in to unlock the protected catalog, line-up wall, and screening paths."
            : "The suite is showing fallback assets while the live catalog is unavailable.";
    elements.catalogSourceLabel.textContent = catalogSourceLabel();
    elements.librarySearch.value = state.searchTerm;
    elements.continueSection.hidden = !continueItems.length;
    elements.featuredLineupSection.hidden = !featureItems.length;

    renderGenreChips();
    renderRow(elements.continueRow, continueItems, "No titles in progress yet.");
    renderRow(elements.featuredRow, featureItems, "No additional priority titles right now.");
    renderRow(
        elements.libraryGrid,
        libraryItems,
        state.searchTerm
            ? `No titles match "${state.searchTerm}".`
            : "No titles match the current filter."
    );
}

function renderOperationsBoard() {
    const selected = currentItem();
    const activeReviews = continueWatchingItems().length;
    const liveJobs = activeRtspJobs();
    const readyAssets = readyAssetCount();
    const totalAssets = state.library.length;
    const publicStatusLabel = state.broadcast.status === "ON_AIR" ? "On air" : "House loop";
    const adStatus = state.broadcast.adStatus ?? defaultAdStatus();
    const demoMonkeyNotice = state.demoMonkey.enabled ? ` ${formatDemoMonkeyPreset(state.demoMonkey.preset)} is active across the incident surfaces.` : "";
    const adNotice = adStatus.state === "IN_BREAK"
        ? ` ${adStatus.sponsorLabel} is currently occupying ${adStatus.podLabel.toLowerCase()}.`
        : ` Next sponsor pod: ${adStatus.sponsorLabel} at ${formatOptionalTime(adStatus.breakStartAt, "the next break")}.`;

    elements.opsSummaryCopy.textContent = state.session
        ? `${state.session.name} is working inside ${runtimeConfig.controlRoomLabel ?? "the broadcast center"} with ${pluralize(totalAssets, "programmed asset")} and ${pluralize(liveJobs.length, "active contribution feed")} in flight.${demoMonkeyNotice}`
        : "Open the live broadcast, then use a booth persona or scenario shortcut to move into the protected operator workflow.";
    elements.sessionRoleLabel.textContent = state.session ? `${state.session.name} · ${operatorRoleLabel()}` : "Booth launch ready";
    elements.opsLibrarySize.textContent = state.session ? `${totalAssets} titles` : `${totalAssets} preview`;
    elements.opsStreamReady.textContent = state.session ? (totalAssets ? `${readyAssets}/${totalAssets} ready` : "No assets") : "Public channel live";
    elements.opsInProgress.textContent = state.session ? `${activeReviews} active` : "Scenario shortcuts";
    elements.opsSource.textContent = catalogSourceLabel();
    elements.opsDistribution.textContent = runtimeConfig.broadcastModel ?? distributionLabel();
    elements.opsAuth.textContent = state.session ? `${operatorRoleLabel()} access` : "Attendee launch mode";
    elements.opsSelected.textContent = selected ? `${selected.title} · ${selected.channelLabel}` : "No asset selected";
    elements.opsPublicTitle.textContent = `${state.broadcast.title} · ${publicStatusLabel}`;
    elements.opsPublicCopy.textContent = state.broadcast.status === "ON_AIR"
        ? `${state.broadcast.channelLabel} is carrying a live contribution. Share ${absoluteUrl(state.broadcast.publicPageUrl)} for external monitoring or stakeholder review.${adNotice}`
        : `The external distribution page is carrying the house loop until a capturing or ready RTSP job is taken live. Share ${absoluteUrl(state.broadcast.publicPageUrl)} for external playback.${adNotice}`;
    elements.opsPublicLink.href = state.broadcast.publicPageUrl || runtimeConfig.publicBroadcastPageUrl || "/broadcast";
    const publicRtspUrl = String(runtimeConfig.publicBroadcastRtspUrl ?? "").trim();
    const hasPublicRtspUrl = Boolean(publicRtspUrl);
    elements.opsPublicRtspChip.hidden = !hasPublicRtspUrl;
    elements.opsPublicRtspLink.hidden = !hasPublicRtspUrl;
    if (hasPublicRtspUrl) {
        elements.opsPublicRtspUrl.textContent = publicRtspUrl;
        elements.opsPublicRtspLink.href = publicRtspUrl;
        elements.opsPublicRtspLink.setAttribute("aria-label", `Open external RTSP feed at ${publicRtspUrl}`);
    } else {
        elements.opsPublicRtspUrl.textContent = "RTSP endpoint unavailable";
        elements.opsPublicRtspLink.removeAttribute("href");
        elements.opsPublicRtspLink.setAttribute("aria-label", "External RTSP feed unavailable");
    }
    elements.rtspIntakeCopy.textContent = hasCapability("ingest")
        ? "Register a contribution feed against a programmed asset. Use rtsp://demo.acmebroadcasting.local/live for the bundled control-room feed, or enter an approved live camera URL."
        : "Contribution intake controls are available to programming and platform roles.";
    elements.rtspJobsCopy.textContent = !state.session
        ? "Sign in to use master control tools and review contribution activity."
        : state.rtspJobs.length
            ? "Recent contribution jobs are listed below, including any feeds that are ready for screening or can be taken live on the external channel."
            : "No contribution ingest jobs have been created yet.";
}

function renderAdIssueConsole() {
    const current = state.adIssue ?? defaultAdIssueState();
    elements.adIssueSummary.textContent = current.summary;
    elements.adIssuePreset.value = current.preset || "clear";
    elements.adIssueDelay.value = String(current.responseDelayMs || 0);
    elements.adIssueEnabled.checked = current.enabled;
    elements.adIssueFailLoad.checked = current.adLoadFailureEnabled;
    elements.adIssueApply.disabled = !state.session;
    elements.adIssueClear.disabled = !state.session || !current.enabled;
}

function renderDemoMonkeyConsole() {
    const presetLabel = formatDemoMonkeyPreset(state.demoMonkey.preset);
    const startupDelayValue = state.demoMonkey.startupDelayMs || Number.parseInt(elements.demoMonkeyLatencyValue.value, 10) || 2500;
    const throttleValue = state.demoMonkey.throttleKbps || Number.parseInt(elements.demoMonkeyThrottleValue.value, 10) || 768;
    const disconnectValue = state.demoMonkey.disconnectAfterKb || Number.parseInt(elements.demoMonkeyDisconnectValue.value, 10) || 384;

    elements.demoMonkeyStatus.textContent = state.demoMonkey.enabled ? `${presetLabel} active` : "Bypassed";
    elements.demoMonkeySummary.textContent = state.demoMonkey.summary;
    elements.demoMonkeyScope.textContent = `${state.demoMonkey.scope} Targets: ${state.demoMonkey.affectedPaths.join(", ")}.`;
    elements.demoMonkeyProfile.textContent = state.demoMonkey.enabled ? presetLabel : "Faults disabled";
    elements.demoMonkeyDetail.textContent = state.demoMonkey.summary;
    elements.demoMonkeyUpdatedAt.textContent = formatDateTimeLabel(state.demoMonkey.updatedAt);

    elements.demoMonkeyEnabled.checked = state.demoMonkey.enabled;
    elements.demoMonkeyNextBreakOnlyEnabled.checked = state.demoMonkey.nextBreakOnlyEnabled;
    elements.demoMonkeyLatencyEnabled.checked = state.demoMonkey.startupDelayMs > 0;
    elements.demoMonkeyLatencyValue.value = String(startupDelayValue);
    elements.demoMonkeyThrottleEnabled.checked = state.demoMonkey.throttleKbps > 0;
    elements.demoMonkeyThrottleValue.value = String(throttleValue);
    elements.demoMonkeyDisconnectEnabled.checked = state.demoMonkey.disconnectAfterKb > 0;
    elements.demoMonkeyDisconnectValue.value = String(disconnectValue);
    elements.demoMonkeyPlaybackFailureEnabled.checked = state.demoMonkey.playbackFailureEnabled;
    elements.demoMonkeyTraceMapFailureEnabled.checked = state.demoMonkey.traceMapFailureEnabled;
    elements.demoMonkeyFrontendExceptionEnabled.checked = state.demoMonkey.frontendExceptionEnabled;
    elements.demoMonkeySlowAdEnabled.checked = state.demoMonkey.slowAdEnabled;
    elements.demoMonkeyAdLoadFailureEnabled.checked = state.demoMonkey.adLoadFailureEnabled;

    elements.demoMonkeyApply.disabled = !state.session;
    elements.demoMonkeyDisable.disabled = !state.session || !state.demoMonkey.enabled;

    for (const button of elements.demoMonkeyPresets.querySelectorAll("[data-preset]")) {
        button.classList.toggle("is-active", button.dataset.preset === state.demoMonkey.preset);
    }

    maybeEmitDemoMonkeyFrontendException(state.demoMonkey);
}

function renderRtspTargets() {
    const targets = state.library.filter((item) => item.type === "MOVIE" || item.type === "SERIES");
    if (!targets.length) {
        elements.rtspTargetContent.innerHTML = `<option value="">No authenticated catalog targets</option>`;
        elements.rtspTargetContent.disabled = true;
        return;
    }

    elements.rtspTargetContent.innerHTML = targets
        .map((item) => `<option value="${escapeAttribute(item.id)}">${escapeHtml(item.title)} · ${escapeHtml(item.type)}</option>`)
        .join("");
    elements.rtspTargetContent.disabled = !state.session || !hasCapability("ingest");

    if (!targets.some((item) => item.id === elements.rtspTargetContent.value)) {
        elements.rtspTargetContent.value = targets[0].id;
    }
}

function rtspStatusClass(status) {
    switch (status) {
        case "READY":
            return "status-pill-ready";
        case "QUEUED":
            return "status-pill-standby";
        case "TRANSCODING":
        case "CAPTURING":
        case "CONNECTING":
        case "FINALIZING":
            return "status-pill-risk";
        default:
            return "status-pill-blocked";
    }
}

function surfaceToneClass(status) {
    switch (status) {
        case "ready":
            return "status-pill-ready";
        case "risk":
            return "status-pill-risk";
        case "locked":
        case "blocked":
            return "status-pill-blocked";
        default:
            return "status-pill-standby";
    }
}

function surfaceToneLabel(status) {
    switch (status) {
        case "ready":
            return "Ready";
        case "risk":
            return "Attention";
        case "locked":
            return "Protected";
        case "blocked":
            return "Down";
        default:
            return "Standby";
    }
}

function renderBroadcastDeck() {
    const lineup = buildBroadcastLineup();
    const rundown = buildRundown();

    elements.channelGrid.innerHTML = lineup.length
        ? lineup.map((channel) => `
            <article class="channel-card" data-state="${escapeAttribute(channel.tone)}">
                <div class="channel-topline">
                    <div>
                        <span>${escapeHtml(channel.label)}</span>
                        <strong>${escapeHtml(channel.desk)}</strong>
                    </div>
                    <span class="status-pill ${surfaceToneClass(channel.tone)}">${escapeHtml(channel.status)}</span>
                </div>
                <div class="channel-stack">
                    <div class="channel-slot">
                        <span>Now</span>
                        <strong>${escapeHtml(channel.now?.title ?? "No asset")}</strong>
                        <p>${escapeHtml(channel.now?.programmingTrack ?? channel.note)}</p>
                    </div>
                    <div class="channel-slot">
                        <span>Next</span>
                        <strong>${escapeHtml(channel.next?.title ?? channel.now?.title ?? "No asset")}</strong>
                        <p>${escapeHtml(channel.next?.readinessLabel ?? channel.now?.readinessLabel ?? "Ready for operator routing")}</p>
                    </div>
                </div>
                <p class="channel-note">${escapeHtml(channel.note)}</p>
                <div class="media-actions">
                    <button class="media-button is-primary" type="button" data-action="play" data-id="${escapeAttribute(channel.now?.id ?? "")}">Open screening</button>
                    <button class="media-button" type="button" data-action="select" data-id="${escapeAttribute(channel.now?.id ?? "")}">Inspect asset</button>
                </div>
            </article>
        `).join("")
        : `<div class="compact-empty">No linear services are available until the catalog is loaded.</div>`;

    elements.rundownList.innerHTML = rundown.length
        ? rundown.map((slot) => `
            <article class="rundown-item" data-state="${escapeAttribute(slot.tone)}">
                <div class="rundown-time">${escapeHtml(slot.slotLabel)}</div>
                <div class="rundown-copy">
                    <span>${escapeHtml(slot.channelLabel)} · ${escapeHtml(slot.status)}</span>
                    <strong>${escapeHtml(slot.title ?? slot.item?.title ?? "No asset")}</strong>
                    <p>${escapeHtml(slot.detail ?? slot.item?.featureLine ?? "No asset is assigned to this slot.")}</p>
                </div>
                <button
                    class="button button-ghost"
                    type="button"
                    data-action="play"
                    data-id="${escapeAttribute(slot.item?.id ?? "")}"
                    data-url="${escapeAttribute(slot.playbackUrl ?? "")}"
                    data-title="${escapeAttribute(slot.metaTitle ?? slot.title ?? slot.item?.title ?? "Queued playback")}"
                    data-description="${escapeAttribute(slot.metaDescription ?? slot.detail ?? "Queued playback source.")}"
                    data-kind="${escapeAttribute(slot.metaKind ?? "queue")}"
                    data-status="${escapeAttribute(slot.status ?? "queued")}"
                    ${slot.routeable === false ? "disabled" : ""}>${escapeHtml(slot.routeLabel ?? "Route")}</button>
            </article>
        `).join("")
        : `<div class="compact-empty">Load the catalog to populate the desk rundown.</div>`;

    elements.serviceWall.innerHTML = serviceWallEntries()
        .map((entry) => `
            <article class="service-card" data-state="${escapeAttribute(entry.status)}">
                <div class="service-topline">
                    <span>${escapeHtml(entry.label)}</span>
                    <span class="status-pill ${surfaceToneClass(entry.status)}">${escapeHtml(surfaceToneLabel(entry.status))}</span>
                </div>
                <strong>${escapeHtml(entry.headline)}</strong>
                <p>${escapeHtml(entry.detail)}</p>
            </article>
        `)
        .join("");

    elements.deskHighlights.innerHTML = deskHighlights()
        .map((highlight) => `
            <article class="desk-note">
                <span>${escapeHtml(highlight.label)}</span>
                <strong>${escapeHtml(highlight.value)}</strong>
                <p>${escapeHtml(highlight.detail)}</p>
            </article>
        `)
        .join("");
}

function renderRtspJobs() {
    renderRtspTargets();
    elements.rtspSubmit.disabled = !hasCapability("ingest");
    elements.rtspRefresh.disabled = !state.session;
    elements.rtspSourceUrl.disabled = !hasCapability("ingest");
    elements.rtspDuration.disabled = !hasCapability("ingest");

    if (!state.rtspJobs.length) {
        elements.rtspJobs.innerHTML = `<div class="compact-empty">${state.session ? "No contribution jobs registered yet." : "Sign in to review contribution activity."}</div>`;
        return;
    }

    elements.rtspJobs.innerHTML = state.rtspJobs
        .map((job) => `
            <article class="job-card">
                <div class="job-topline">
                    <div>
                        <strong>#${escapeHtml(job.jobNumber)}</strong>
                        <span>${escapeHtml(job.targetTitle)}</span>
                    </div>
                    <span class="status-pill ${rtspStatusClass(job.status)}">${escapeHtml(job.status)}</span>
                </div>
                <div class="job-meta">
                    <span>${escapeHtml(job.mediaType)}</span>
                    <span>${escapeHtml(job.operatorEmail)}</span>
                    <span>${escapeHtml(formatClock(job.createdAt))}</span>
                    <span>${escapeHtml(job.captureDurationSeconds)}s window</span>
                </div>
                <p>${escapeHtml(job.sourceUrl)}</p>
                ${job.errorMessage ? `<p class="job-error">${escapeHtml(job.errorMessage)}</p>` : ""}
                ${(job.playbackUrl || isRtspJobActivatable(job) || state.broadcast.jobId === job.jobId)
                    ? `
                        <div class="job-actions">
                            ${job.playbackUrl
                                ? `<button class="button button-ghost" type="button" data-action="play-job" data-job-id="${escapeAttribute(job.jobId)}">Open capture</button>`
                                : ""}
                            ${hasCapability("ingest") && isRtspJobActivatable(job)
                                ? `<button class="button ${state.broadcast.jobId === job.jobId ? "button-solid" : "button-ghost"}" type="button" data-action="take-live" data-job-id="${escapeAttribute(job.jobId)}">${state.broadcast.jobId === job.jobId ? "On air" : "Take live"}</button>`
                                : ""}
                            ${state.broadcast.jobId === job.jobId
                                ? `<a class="button button-ghost" href="${escapeAttribute(state.broadcast.publicPageUrl || runtimeConfig.publicBroadcastPageUrl || "/broadcast")}" target="_blank" rel="noreferrer">Open external feed</a>`
                                : ""}
                        </div>
                    `
                    : ""}
            </article>
        `)
        .join("");
}

function renderBillingSelectOptions(selectElement, accounts, { includeAll = false, selectedValue = "" } = {}) {
    const options = [];

    if (includeAll) {
        options.push(`<option value="all">All accounts</option>`);
    }

    for (const account of accounts) {
        options.push(
            `<option value="${escapeAttribute(account.id)}">${escapeHtml(account.label)} · ${escapeHtml(account.desk)}</option>`
        );
    }

    selectElement.innerHTML = options.join("");

    if (selectedValue && [...selectElement.options].some((option) => option.value === selectedValue)) {
        selectElement.value = selectedValue;
    } else if (includeAll) {
        selectElement.value = "all";
    } else if (accounts[0]) {
        selectElement.value = accounts[0].id;
    }
}

function renderBillingConsole() {
    const accessible = hasCapability("billing");
    const writeAccess = hasCapability("billing_write");
    const accounts = billingAccountDirectory();
    const filteredInvoices = filteredBillingInvoices();
    const scopedInvoices = state.billing.selectedAccountId === "all"
        ? state.billing.invoices
        : state.billing.invoices.filter((invoice) => invoice.userId === state.billing.selectedAccountId);
    const summary = deriveBillingSummary(scopedInvoices);
    const selectedInvoice = selectedBillingInvoice();
    const billingHealth = state.serviceHealth.billing;

    elements.billingPortfolioCopy.textContent = !state.session
        ? "Sign in with a finance-capable role to open the invoice desk."
        : !accessible
            ? "This role can work in master control, but revenue operations are restricted."
            : state.billing.source === "live"
                ? `${pluralize(summary.invoiceCount, "invoice")} are in the current billing slice, with ${formatMoney(summary.totalOutstanding, summary.currency)} still outstanding.`
                : "The finance console is active in preview mode and will switch to the live billing ledger when the backend is reachable.";
    elements.billingTotalOutstanding.textContent = accessible
        ? formatMoney(summary.totalOutstanding, summary.currency)
        : "Restricted";
    elements.billingOpenCount.textContent = accessible ? `${summary.openCount}` : "Restricted";
    elements.billingPastDueCount.textContent = accessible ? `${summary.pastDueCount}` : "Restricted";
    elements.billingNextDueDate.textContent = accessible
        ? formatDateLabel(summary.nextDueDate)
        : "Restricted";
    elements.billingSourceCopy.textContent = accessible
        ? state.billing.source === "live"
            ? "Live invoice data is connected through the billing microservice."
            : "Seeded invoice data is standing in until the live billing service is available."
        : "Finance-specific detail is hidden for roles outside billing and executive access.";
    elements.billingSourceLabel.textContent = billingSourceLabel();
    elements.billingHealthLabel.textContent = billingHealth === "ready"
        ? state.billing.source === "live" ? "Connected" : "Preview only"
        : surfaceToneLabel(billingHealth);
    elements.billingSelectedLabel.textContent = selectedInvoice ? selectedInvoice.invoiceNumber : "No invoice selected";
    elements.billingLastSync.textContent = state.billing.lastSyncLabel;
    elements.billingRefresh.disabled = !state.session || !accessible;
    elements.billingStatusFilter.value = state.billing.statusFilter;
    elements.billingOverdueOnly.checked = state.billing.overdueOnly;

    renderBillingSelectOptions(elements.billingAccountFilter, accounts, {
        includeAll: true,
        selectedValue: state.billing.selectedAccountId
    });
    renderBillingSelectOptions(elements.billingCreateAccount, accounts, {
        includeAll: false,
        selectedValue: elements.billingCreateAccount.value
    });

    if (!elements.billingDueDate.value) {
        elements.billingDueDate.value = isoDateFromOffset(14);
    }

    elements.billingCreateAccount.disabled = !writeAccess;
    elements.billingLineTitle.disabled = !writeAccess;
    elements.billingLineQuantity.disabled = !writeAccess;
    elements.billingLineAmount.disabled = !writeAccess;
    elements.billingCycle.disabled = !writeAccess;
    elements.billingDueDate.disabled = !writeAccess;
    elements.billingNotes.disabled = !writeAccess;
    elements.billingCreateSubmit.disabled = !writeAccess;

    if (!accessible) {
        elements.billingInvoiceList.innerHTML = `<div class="compact-empty">Revenue Ops is available to finance, executive, and platform roles.</div>`;
        elements.billingDetail.innerHTML = `<div class="compact-empty">Ask for finance access if you need invoice status, account exposure, or payment follow-up.</div>`;
        return;
    }

    if (!filteredInvoices.length) {
        elements.billingInvoiceList.innerHTML = `<div class="compact-empty">${
            state.billing.invoices.length
                ? "No invoices match the current account and status filters."
                : "No invoices are available in the current billing source."
        }</div>`;
    } else {
        elements.billingInvoiceList.innerHTML = filteredInvoices
            .map((invoice) => {
                const status = deriveBillingStatus(invoice);
                const tone = billingStatusTone(status);
                const selectedClass = invoice.id === state.billing.selectedInvoiceId ? " is-selected" : "";
                return `
                    <article class="invoice-card${selectedClass}" data-id="${escapeAttribute(invoice.id)}">
                        <div class="invoice-topline">
                            <div>
                                <strong>${escapeHtml(invoice.invoiceNumber)}</strong>
                                <span>${escapeHtml(invoice.accountLabel)}</span>
                            </div>
                            <span class="status-pill ${surfaceToneClass(tone)}">${escapeHtml(billingStatusLabel(status))}</span>
                        </div>
                        <div class="invoice-meta">
                            <span>${escapeHtml(invoice.billingCycle)}</span>
                            <span>Due ${escapeHtml(formatDateLabel(invoice.dueDate))}</span>
                            <span>${escapeHtml(pluralize(invoice.lineItems.length, "line item"))}</span>
                        </div>
                        <div class="invoice-figures">
                            <div>
                                <span>Total</span>
                                <strong>${escapeHtml(formatMoney(invoice.totalAmount, invoice.currency))}</strong>
                            </div>
                            <div>
                                <span>Balance due</span>
                                <strong>${escapeHtml(formatMoney(invoice.balanceDue, invoice.currency))}</strong>
                            </div>
                        </div>
                        <p>${escapeHtml(invoice.notes || invoice.accountDesk)}</p>
                    </article>
                `;
            })
            .join("");
    }

    if (!selectedInvoice) {
        elements.billingDetail.innerHTML = `<div class="compact-empty">Select an invoice to inspect its line items, notes, and status actions.</div>`;
        return;
    }

    const selectedStatus = deriveBillingStatus(selectedInvoice);
    const actionButtons = writeAccess
        ? `
            <div class="detail-actions">
                ${selectedStatus !== "PAID" ? `<button class="button button-solid" type="button" data-action="billing-status" data-status="PAID" data-id="${escapeAttribute(selectedInvoice.id)}">Mark paid</button>` : ""}
                ${selectedStatus !== "VOID" ? `<button class="button button-ghost" type="button" data-action="billing-status" data-status="VOID" data-id="${escapeAttribute(selectedInvoice.id)}">Void</button>` : ""}
                ${selectedStatus === "DRAFT" ? `<button class="button button-ghost" type="button" data-action="billing-status" data-status="OPEN" data-id="${escapeAttribute(selectedInvoice.id)}">Reopen</button>` : ""}
            </div>
        `
        : `<p class="billing-detail-note">This role can review invoices but cannot create or update them.</p>`;

    elements.billingDetail.innerHTML = `
        <article class="billing-detail-card">
            <div class="invoice-topline">
                <div>
                    <strong>${escapeHtml(selectedInvoice.invoiceNumber)}</strong>
                    <span>${escapeHtml(selectedInvoice.accountLabel)} · ${escapeHtml(selectedInvoice.accountDesk)}</span>
                </div>
                <span class="status-pill ${surfaceToneClass(billingStatusTone(selectedStatus))}">${escapeHtml(billingStatusLabel(selectedStatus))}</span>
            </div>

            <div class="invoice-summary-grid">
                <div>
                    <span>Issued</span>
                    <strong>${escapeHtml(formatDateLabel(selectedInvoice.issuedDate))}</strong>
                </div>
                <div>
                    <span>Due</span>
                    <strong>${escapeHtml(formatDateLabel(selectedInvoice.dueDate))}</strong>
                </div>
                <div>
                    <span>Total</span>
                    <strong>${escapeHtml(formatMoney(selectedInvoice.totalAmount, selectedInvoice.currency))}</strong>
                </div>
                <div>
                    <span>Balance</span>
                    <strong>${escapeHtml(formatMoney(selectedInvoice.balanceDue, selectedInvoice.currency))}</strong>
                </div>
            </div>

            <div class="line-item-list">
                ${selectedInvoice.lineItems.map((lineItem) => `
                    <div class="line-item">
                        <div>
                            <strong>${escapeHtml(lineItem.title)}</strong>
                            <span>${escapeHtml(lineItem.description || "No additional note.")}</span>
                        </div>
                        <div class="line-item-amount">
                            <span>${escapeHtml(`${lineItem.quantity} × ${formatMoney(lineItem.unitAmount, selectedInvoice.currency)}`)}</span>
                            <strong>${escapeHtml(formatMoney(lineItem.lineTotal, selectedInvoice.currency))}</strong>
                        </div>
                    </div>
                `).join("")}
            </div>

            <p class="billing-detail-note">${escapeHtml(selectedInvoice.notes || "No finance note is attached to this invoice.")}</p>
            ${actionButtons}
        </article>
    `;
}

function renderLayout() {
    elements.clusterContext.textContent = runtimeConfig.clusterLabel ?? "cluster not set";
    elements.deploymentNamespace.textContent = runtimeConfig.namespace ?? "namespace not set";
    elements.runtimeMode.textContent = runtimeConfig.environment ?? "review";
    renderHeader();
    renderLaunchOverlay();
    updateRoleScopedView();
    ensurePageAccess();
    syncSelection();
    renderFeatured();
    renderBroadcastDeck();
    renderOperationsBoard();
    renderAdIssueConsole();
    renderDemoMonkeyConsole();
    renderRtspJobs();
    renderBillingConsole();
    renderMyListPreview();
    renderLibrary();
    renderPlayerMeta();
    renderPageNavigation();
    updateAuthView();
}

function setPlayerStatus(status, message) {
    elements.playerStatusBadge.className = `status-pill status-pill-${status}`;
    elements.playerStatusBadge.textContent = status === "ready" ? "Ready" : status === "risk" ? "Buffering" : "Blocked";
    elements.playerStatusText.textContent = message;
}

function updatePlaybackProgressVisual() {
    const player = elements.moviePlayer;
    const hasRuntime = Number.isFinite(player.duration) && player.duration > 0;
    const progress = hasRuntime ? Math.max(0, Math.min(100, (player.currentTime / player.duration) * 100)) : 0;
    elements.playbackProgressLabel.textContent = formatPercent(progress);
    elements.playbackProgressFill.style.width = `${progress}%`;
}

function saveProgressSnapshot(force = false) {
    if (!state.session || !state.playerMeta?.id) {
        return;
    }

    const player = elements.moviePlayer;
    if (!Number.isFinite(player.duration) || player.duration <= 0) {
        return;
    }

    const now = Date.now();
    if (!force && now - state.lastProgressCommit < 3000) {
        return;
    }

    state.lastProgressCommit = now;
    state.watchState[state.playerMeta.id] = {
        currentTime: Math.floor(player.currentTime),
        duration: Math.floor(player.duration),
        updatedAt: now
    };
    persistWatchState();
}

function resetPlayerElement() {
    const player = elements.moviePlayer;
    if (hlsPlayer) {
        hlsPlayer.destroy();
        hlsPlayer = null;
    }
    player.pause();
    player.removeAttribute("src");
    while (player.firstChild) {
        player.removeChild(player.firstChild);
    }
    player.load();
}

function isLikelyHlsSource(url) {
    const normalized = String(url ?? "").trim().toLowerCase();
    return normalized.endsWith(".m3u8") || normalized.includes("/api/v1/stream/playlist/");
}

function appendPlayerSource(player, sourceUrl, contentType) {
    const source = document.createElement("source");
    source.src = sourceUrl;
    if (contentType) {
        source.type = contentType;
    }
    player.appendChild(source);
    player.load();
}

function loadHlsPlayerSource(player, sourceUrl) {
    if (window.Hls?.isSupported?.()) {
        hlsPlayer = new window.Hls({
            enableWorker: true
        });
        hlsPlayer.on(window.Hls.Events.ERROR, (_event, data) => {
            if (!data?.fatal) {
                return;
            }

            setPlayerStatus("blocked", "The HLS review stream failed to load.");
            hlsPlayer.destroy();
            hlsPlayer = null;
        });
        hlsPlayer.loadSource(sourceUrl);
        hlsPlayer.attachMedia(player);
        return true;
    }

    if (player.canPlayType("application/vnd.apple.mpegurl") || player.canPlayType("application/x-mpegURL")) {
        player.src = sourceUrl;
        player.load();
        return true;
    }

    return false;
}

function clearPlayerForSignedOutState() {
    resetPlayerElement();
    state.playerMeta = {
        title: "Protected Player",
        description: "Sign in with your Acme Broadcasting account to unlock playback and continue where you left off.",
        badges: ["Authentication required", "Protected workspace"],
        kind: "locked"
    };
    state.playerUrl = "";
    state.pendingResumeId = "";
    renderPlayerMeta();
    setPlayerStatus("blocked", "Sign in to unlock protected playback.");
}

function loadPlayerSource(url, metadata) {
    const normalized = url.trim();
    if (!normalized) {
        setPlayerStatus("blocked", "No playback source is configured for the current review item.");
        return;
    }

    state.playerUrl = normalized;
    state.playerMeta = metadata ?? {
        title: "Manual Review Source",
        description: "A manual source was loaded into the player.",
        badges: ["Manual source", "Override"],
        kind: "custom"
    };
    state.pendingResumeId = metadata?.id ?? "";
    persistPlayerUrl();
    renderPlayerMeta();

    const player = elements.moviePlayer;
    resetPlayerElement();

    if (isLikelyHlsSource(normalized)) {
        if (!loadHlsPlayerSource(player, normalized)) {
            setPlayerStatus("blocked", "This browser cannot open the HLS review stream.");
            return;
        }
        setPlayerStatus("ready", metadata?.id ? "Review stream loaded. Press play to start or resume." : "Manual HLS source loaded.");
        return;
    }

    if (normalized.endsWith(".mp4")) {
        appendPlayerSource(player, normalized, "video/mp4");
    } else if (normalized.endsWith(".webm")) {
        appendPlayerSource(player, normalized, "video/webm");
    } else {
        appendPlayerSource(player, normalized, "");
    }
    setPlayerStatus("ready", metadata?.id ? "Playback source loaded. Press play to start or resume." : "Manual source loaded.");
}

function hydratePlayerForCurrentSelection() {
    if (!state.session) {
        clearPlayerForSignedOutState();
        return;
    }

    const item = currentItem();
    if (!item) {
        return;
    }

    const desiredUrl = state.playerUrl || item.watchUrl || runtimeConfig.demoMovieUrl || "";
    const shouldUseSelectedTitle =
        !state.playerMeta ||
        state.playerMeta.kind !== "custom" ||
        desiredUrl === item.watchUrl ||
        desiredUrl === runtimeConfig.demoMovieUrl;

    if (shouldUseSelectedTitle) {
        loadPlayerSource(item.watchUrl || runtimeConfig.demoMovieUrl || "", metaFromItem(item));
        return;
    }

    loadPlayerSource(desiredUrl, state.playerMeta);
}

function selectTitle(id) {
    state.selectedId = id;
    persistSelectedId();
    renderLayout();
}

function playItem(id) {
    const item = state.library.find((candidate) => candidate.id === id);
    if (!item) {
        return;
    }

    state.selectedId = item.id;
    persistSelectedId();
    loadPlayerSource(item.watchUrl || runtimeConfig.demoMovieUrl || "", metaFromItem(item));
    renderLayout();
    goToPage("player");
}

function toggleMyList(id) {
    if (!state.session) {
        return;
    }

    if (state.myList.has(id)) {
        state.myList.delete(id);
    } else {
        state.myList.add(id);
    }

    persistMyList();
    renderLayout();
}

function updateAuthView() {
    const authenticated = Boolean(state.session);
    document.body.classList.toggle("has-auth-session", authenticated);
    elements.authOverlay.hidden = authenticated;
}

function showAuthMessage(message, isError = true) {
    elements.authMessage.textContent = message;
    elements.authMessage.dataset.state = isError ? "error" : "success";
}

function setLaunchMessage(message, isError = false) {
    elements.launchMessage.textContent = message;
    elements.launchMessage.dataset.state = isError ? "error" : "success";
}

async function establishPersonaSession(personaKey) {
    const endpoint = `${runtimeConfig.authPersonaUrlBase ?? "/api/v1/demo/auth/persona"}/${encodeURIComponent(personaKey)}`;
    const response = await fetch(endpoint, {
        method: "POST",
        cache: "no-store",
        credentials: "same-origin"
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(payload.message ?? `HTTP ${response.status}`);
    }

    const session = normalizeSession(payload);
    if (!session.email) {
        throw new Error("Persona response was incomplete.");
    }

    state.session = session;
    writeJson(storageKeys.session, state.session);
    loadUserState();
    startRtspPolling();
    await refreshWorkspaceData();
    showAuthMessage("", false);
    return session;
}

async function launchPersona(personaKey, targetPage) {
    const persona = boothPersonas[personaKey];
    if (!persona) {
        return;
    }

    if (persona.mode === "public") {
        setLaunchMessage("", false);
        window.location.href = state.broadcast.publicPageUrl || runtimeConfig.publicBroadcastPageUrl || "/broadcast";
        return;
    }

    const label = persona.label || "Persona";
    setLaunchMessage(`Opening ${label} view...`, false);
    try {
        await establishPersonaSession(persona.personaKey);
        setLaunchMessage("", false);
        goToPage(targetPage || persona.targetPage || "home");
    } catch (error) {
        console.warn("Unable to establish persona session.", error);
        setLaunchMessage(error.message || `Unable to launch the ${label.toLowerCase()} persona right now.`, true);
    }
}

async function launchScenarioShortcut(presetKey) {
    const shortcut = boothScenarioShortcuts[presetKey];
    const preset = demoMonkeyPresets[presetKey];
    if (!shortcut || !preset) {
        return;
    }

    setLaunchMessage(`Arming ${shortcut.label.toLowerCase()}...`, false);
    try {
        if (!state.session) {
            await establishPersonaSession(shortcut.personaKey);
        }
        await updateDemoMonkeyConfig(
            preset,
            preset.enabled ? `${preset.label} is active across the incident surfaces.` : "Incident simulation bypassed."
        );
        setLaunchMessage("", false);
        goToPage(shortcut.targetPage || "operations");
    } catch (error) {
        console.warn("Unable to launch booth scenario.", error);
        setLaunchMessage(error.message || `Unable to arm ${shortcut.label.toLowerCase()} right now.`, true);
    }
}

async function restoreSession() {
    try {
        const response = await fetch(runtimeConfig.authSessionUrl ?? "", {
            cache: "no-store",
            credentials: "same-origin"
        });

        if (response.status === 401) {
            clearSessionState(true);
            return false;
        }

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const payload = await response.json();
        const session = normalizeSession(payload);
        if (!session.email) {
            throw new Error("Session payload did not include an email.");
        }

        state.session = session;
        writeJson(storageKeys.session, state.session);
        loadUserState();
        startRtspPolling();
        return true;
    } catch (error) {
        console.warn("Failed to restore authenticated session.", error);
        clearSessionState(true);
        return false;
    }
}

async function signIn(event) {
    event.preventDefault();

    const email = elements.authEmail.value.trim().toLowerCase();
    const password = elements.authPassword.value.trim();

    if (!email.endsWith("@acmebroadcasting.com")) {
        showAuthMessage("Use your Acme Broadcasting company email to continue.");
        return;
    }

    if (password.length < 8) {
        showAuthMessage("Password must be at least 8 characters.");
        return;
    }

    showAuthMessage("Signing in...", false);
    setLaunchMessage("", false);

    try {
        const response = await fetch(runtimeConfig.authLoginUrl ?? "", {
            method: "POST",
            cache: "no-store",
            credentials: "same-origin",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ email, password })
        });

        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(payload.message ?? `HTTP ${response.status}`);
        }

        const session = normalizeSession(payload);
        if (!session.email) {
            throw new Error("Authentication response was incomplete.");
        }

        state.session = session;
        writeJson(storageKeys.session, state.session);
        loadUserState();
        elements.authForm.reset();
        startRtspPolling();
        await refreshWorkspaceData();
        showAuthMessage("", false);
        setLaunchMessage("", false);
    } catch (error) {
        console.warn("Sign-in failed.", error);
        showAuthMessage(error.message || "Unable to sign in right now.");
    }
}

async function signOut() {
    try {
        await fetch(runtimeConfig.authLogoutUrl ?? "", {
            method: "POST",
            cache: "no-store",
            credentials: "same-origin"
        });
    } catch (error) {
        console.warn("Logout request failed.", error);
    }

    clearSessionState(true);
    state.catalogSource = "locked";
    state.library = buildLibrary([]);
    state.searchTerm = "";
    state.activeGenre = "All";
    elements.authForm.reset();
    showAuthMessage("Use a booth persona or manual sign-in to reopen the protected workspace.", false);
    setLaunchMessage("", false);
    hydratePlayerForCurrentSelection();
    renderLayout();
}

function savePlayerSettings(event) {
    event.preventDefault();
    if (!state.session) {
        showAuthMessage("Sign in to unlock the player.");
        return;
    }

    loadPlayerSource(elements.playerUrl.value, {
        title: "Manual Review Source",
        description: "A manual source was loaded into the player.",
        badges: ["Manual source", "Override"],
        kind: "custom"
    });
    renderLayout();
}

function loadFeaturedTitle() {
    const item = currentItem();
    if (item) {
        playItem(item.id);
    }
}

function handleCardInteraction(event) {
    const actionButton = event.target.closest("[data-action]");
    if (actionButton) {
        const id = actionButton.dataset.id;
        const action = actionButton.dataset.action;
        if (action === "play") {
            const directUrl = actionButton.dataset.url?.trim() ?? "";
            if (directUrl) {
                const libraryItem = id ? state.library.find((item) => item.id === id) ?? null : null;
                if (libraryItem) {
                    state.selectedId = libraryItem.id;
                    persistSelectedId();
                }

                loadPlayerSource(directUrl, libraryItem
                    ? metaFromItem(libraryItem)
                    : {
                        id: id ?? "",
                        title: actionButton.dataset.title ?? "Queued playback",
                        description: actionButton.dataset.description ?? "Queued playback source from the ad-service program queue.",
                        badges: [actionButton.dataset.kind ?? "queue", actionButton.dataset.status ?? "queued"].filter(Boolean),
                        kind: actionButton.dataset.kind ?? "queue"
                    });
                renderLayout();
                goToPage("player");
            } else {
                playItem(id);
            }
        } else if (action === "select") {
            selectTitle(id);
        } else if (action === "toggle-list") {
            toggleMyList(id);
        }
        return;
    }

    const card = event.target.closest(".media-card");
    if (card?.dataset.id) {
        selectTitle(card.dataset.id);
    }
}

function handleGenreSelection(event) {
    const trigger = event.target.closest("[data-genre]");
    if (!trigger) {
        return;
    }

    state.activeGenre = trigger.dataset.genre ?? "All";
    renderLibrary();
}

function handleLibrarySearch(event) {
    state.searchTerm = event.target.value ?? "";
    renderLibrary();
}

function playRandomTitle() {
    if (!state.session) {
        return;
    }

    const candidates = filteredLibrary();
    if (!candidates.length) {
        return;
    }

    const randomItem = candidates[Math.floor(Math.random() * candidates.length)];
    playItem(randomItem.id);
}

async function loadRtspJobs({ silent = false } = {}) {
    if (!state.session) {
        state.rtspJobs = [];
        renderOperationsBoard();
        renderRtspJobs();
        return;
    }

    try {
        const response = await fetch(runtimeConfig.rtspJobsUrl ?? "", {
            cache: "no-store",
            credentials: "same-origin"
        });

        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const jobs = await response.json();
        state.rtspJobs = Array.isArray(jobs) ? jobs.map((job, index) => normalizeRtspJob(job, index)) : [];
        renderOperationsBoard();
        renderRtspJobs();
    } catch (error) {
        console.warn("Unable to load RTSP ingest jobs.", error);
        if (!silent) {
            setRtspFormMessage("Unable to load RTSP ingest jobs right now.", true);
        }
    }
}

async function loadBroadcastStatus({ silent = false } = {}) {
    try {
        const response = await fetch(runtimeConfig.publicBroadcastStatusUrl ?? "", {
            cache: "no-store",
            credentials: "same-origin"
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const payload = await response.json();
        state.broadcast = normalizeBroadcastStatus(payload);
        renderLaunchOverlay();
        renderBroadcastDeck();
        renderOperationsBoard();
        renderRtspJobs();
    } catch (error) {
        console.warn("Unable to load public broadcast status.", error);
        if (!silent) {
            setRtspFormMessage("Unable to load public broadcast status right now.", true);
        }
    }
}

async function loadAdProgramQueue({ silent = false } = {}) {
    if (!state.session) {
        state.adQueue = [];
        renderBroadcastDeck();
        return;
    }

    try {
        const response = await fetch(runtimeConfig.adProgramQueueUrl ?? "", {
            cache: "no-store",
            credentials: "same-origin"
        });

        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const payload = await response.json();
        state.adQueue = normalizeAdProgramQueue(payload);
        renderBroadcastDeck();
    } catch (error) {
        console.warn("Unable to load the ad-service program queue.", error);
        state.adQueue = [];
        if (!silent) {
            setAdIssueMessage("Unable to load the ad-service queue right now.", true);
        }
    }
}

async function loadAdIssueStatus({ silent = false } = {}) {
    if (!state.session) {
        state.adIssue = defaultAdIssueState();
        renderBroadcastDeck();
        renderOperationsBoard();
        renderAdIssueConsole();
        return;
    }

    try {
        const response = await fetch(runtimeConfig.adIssueUrl ?? "", {
            cache: "no-store",
            credentials: "same-origin"
        });

        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const payload = await response.json();
        state.adIssue = normalizeAdIssueStatus(payload);
        renderBroadcastDeck();
        renderOperationsBoard();
        renderAdIssueConsole();
    } catch (error) {
        console.warn("Unable to load ad-service issue state.", error);
        if (!silent) {
            setAdIssueMessage("Unable to load ad-service issue state right now.", true);
        }
    }
}

function buildAdIssueRequest() {
    const enabled = Boolean(elements.adIssueEnabled.checked);
    const responseDelayMs = enabled ? Number.parseInt(elements.adIssueDelay.value, 10) || 0 : 0;
    const adLoadFailureEnabled = enabled && elements.adIssueFailLoad.checked;
    return {
        enabled,
        preset: deriveAdIssuePreset(enabled, responseDelayMs, adLoadFailureEnabled),
        responseDelayMs,
        adLoadFailureEnabled
    };
}

async function updateAdIssueStatus(request, successMessage) {
    try {
        const response = await fetch(runtimeConfig.adIssueUrl ?? "", {
            method: "PUT",
            cache: "no-store",
            credentials: "same-origin",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(request)
        });

        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(payload.message ?? `HTTP ${response.status}`);
        }

        state.adIssue = normalizeAdIssueStatus(payload);
        await Promise.all([
            loadAdProgramQueue({ silent: true }),
            loadBroadcastStatus({ silent: true }),
            refreshServiceHealth()
        ]);
        setAdIssueMessage(successMessage, false);
        renderLayout();
    } catch (error) {
        console.warn("Unable to update ad-service issue state.", error);
        setAdIssueMessage(error.message || "Unable to update ad-service issues.", true);
    }
}

async function submitAdIssueConfig(event) {
    event.preventDefault();
    const request = buildAdIssueRequest();

    if (request.enabled && request.responseDelayMs <= 0 && !request.adLoadFailureEnabled) {
        setAdIssueMessage("Enable a delay or fail ad loads before applying the ad-service issue.", true);
        return;
    }

    setAdIssueMessage(request.enabled ? "Applying ad-service issue..." : "Clearing ad-service issue...", false);
    await updateAdIssueStatus(
        request,
        request.enabled ? "Ad-service issue applied to the queued sponsor breaks." : "Ad-service issue cleared."
    );
}

async function clearAdIssueConfig() {
    elements.adIssuePreset.value = "clear";
    elements.adIssueDelay.value = "0";
    elements.adIssueEnabled.checked = false;
    elements.adIssueFailLoad.checked = false;
    setAdIssueMessage("Clearing ad-service issue...", false);
    await updateAdIssueStatus(
        {
            enabled: false,
            preset: "clear",
            responseDelayMs: 0,
            adLoadFailureEnabled: false
        },
        "Ad-service issue cleared."
    );
}

function handleAdIssuePresetChange() {
    switch (elements.adIssuePreset.value) {
        case "slow-decisioning":
            elements.adIssueEnabled.checked = true;
            elements.adIssueDelay.value = "3000";
            elements.adIssueFailLoad.checked = false;
            break;
        case "failed-ads":
            elements.adIssueEnabled.checked = true;
            elements.adIssueDelay.value = "0";
            elements.adIssueFailLoad.checked = true;
            break;
        case "slow-and-failed":
            elements.adIssueEnabled.checked = true;
            elements.adIssueDelay.value = "3000";
            elements.adIssueFailLoad.checked = true;
            break;
        default:
            elements.adIssueEnabled.checked = false;
            elements.adIssueDelay.value = "0";
            elements.adIssueFailLoad.checked = false;
            break;
    }
}

function buildDemoMonkeyRequest({ preset = "custom" } = {}) {
    const enabled = Boolean(elements.demoMonkeyEnabled.checked);
    return {
        enabled,
        preset,
        startupDelayMs: enabled && elements.demoMonkeyLatencyEnabled.checked
            ? Number.parseInt(elements.demoMonkeyLatencyValue.value, 10) || 0
            : 0,
        throttleKbps: enabled && elements.demoMonkeyThrottleEnabled.checked
            ? Number.parseInt(elements.demoMonkeyThrottleValue.value, 10) || 0
            : 0,
        disconnectAfterKb: enabled && elements.demoMonkeyDisconnectEnabled.checked
            ? Number.parseInt(elements.demoMonkeyDisconnectValue.value, 10) || 0
            : 0,
        playbackFailureEnabled: enabled && elements.demoMonkeyPlaybackFailureEnabled.checked,
        traceMapFailureEnabled: enabled && elements.demoMonkeyTraceMapFailureEnabled.checked,
        frontendExceptionEnabled: enabled && elements.demoMonkeyFrontendExceptionEnabled.checked,
        slowAdEnabled: enabled && elements.demoMonkeySlowAdEnabled.checked,
        adLoadFailureEnabled: enabled && elements.demoMonkeyAdLoadFailureEnabled.checked,
        nextBreakOnlyEnabled: enabled && elements.demoMonkeyNextBreakOnlyEnabled.checked
    };
}

async function loadDemoMonkeyStatus({ silent = false } = {}) {
    try {
        const endpoint = state.session
            ? runtimeConfig.demoMonkeyUrl ?? "/api/v1/demo/media/demo-monkey"
            : runtimeConfig.publicDemoMonkeyUrl ?? "/api/v1/demo/public/demo-monkey";
        const response = await fetch(endpoint, {
            cache: "no-store",
            credentials: "same-origin"
        });

        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            state.demoMonkey = defaultDemoMonkeyState();
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            syncSelection();
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const payload = await response.json();
        state.demoMonkey = normalizeDemoMonkeyStatus(payload);
        renderLaunchOverlay();
        renderDemoMonkeyConsole();
    } catch (error) {
        console.warn("Unable to load incident simulation status.", error);
        if (!silent) {
            setDemoMonkeyMessage("Unable to load incident simulation state right now.", true);
        }
    }
}

async function updateDemoMonkeyConfig(request, successMessage) {
    if (!state.session) {
        setDemoMonkeyMessage("Sign in to change incident simulation settings.", true);
        return;
    }

    try {
        const response = await fetch(runtimeConfig.demoMonkeyUrl ?? "/api/v1/demo/media/demo-monkey", {
            method: "PUT",
            cache: "no-store",
            credentials: "same-origin",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(request)
        });

        const payload = await response.json().catch(() => ({}));
        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            state.demoMonkey = defaultDemoMonkeyState();
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            syncSelection();
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(payload.message ?? `HTTP ${response.status}`);
        }

        state.demoMonkey = normalizeDemoMonkeyStatus(payload);
        await Promise.all([
            loadAdProgramQueue({ silent: true }),
            loadAdIssueStatus({ silent: true }),
            loadBroadcastStatus({ silent: true }),
            refreshServiceHealth()
        ]);
        renderLayout();
        setDemoMonkeyMessage(successMessage, false);
    } catch (error) {
        console.warn("Unable to update incident simulation state.", error);
        setDemoMonkeyMessage(error.message || "Unable to update incident simulation state.", true);
    }
}

async function tryLoadBillingWorkspace() {
    if (!state.session) {
        state.billing.source = "locked";
        state.billing.invoices = [];
        state.billing.selectedAccountId = "all";
        state.billing.selectedInvoiceId = "";
        state.billing.lastSyncLabel = "Awaiting finance access";
        renderBillingConsole();
        return;
    }

    if (!hasCapability("billing")) {
        state.billing.source = "restricted";
        state.billing.invoices = [];
        state.billing.selectedAccountId = "all";
        state.billing.selectedInvoiceId = "";
        state.billing.lastSyncLabel = `Role check ${formatClock(new Date())}`;
        renderBillingConsole();
        return;
    }

    try {
        const response = await fetch(runtimeConfig.billingInvoicesUrl ?? "", {
            cache: "no-store",
            credentials: "same-origin"
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const invoices = await response.json();
        state.billing.source = "live";
        state.billing.invoices = Array.isArray(invoices)
            ? invoices.map((invoice, index) => normalizeBillingInvoice(invoice, index))
            : [];
        state.billing.lastSyncLabel = `Live sync ${formatClock(new Date())}`;
        setBillingFormMessage("", false);
    } catch (error) {
        console.warn("Falling back to seeded billing data.", error);
        state.billing.source = "seed";
        state.billing.invoices = readSeedBillingInvoices();
        state.billing.lastSyncLabel = `Preview sync ${formatClock(new Date())}`;
        if (hasCapability("billing_write")) {
            setBillingFormMessage("Live billing service unavailable. Working in seeded preview mode.", false);
        }
    }

    ensureBillingSelection();
    renderBillingConsole();
}

function handleBillingAccountFilterChange(event) {
    state.billing.selectedAccountId = event.target.value ?? "all";
    ensureBillingSelection();
    renderBillingConsole();
}

function handleBillingStatusFilterChange(event) {
    state.billing.statusFilter = event.target.value ?? "ALL";
    ensureBillingSelection();
    renderBillingConsole();
}

function handleBillingOverdueToggle(event) {
    state.billing.overdueOnly = Boolean(event.target.checked);
    ensureBillingSelection();
    renderBillingConsole();
}

function handleBillingInvoiceSelection(event) {
    const invoiceCard = event.target.closest("[data-id]");
    if (!invoiceCard?.dataset.id) {
        return;
    }

    state.billing.selectedInvoiceId = invoiceCard.dataset.id;
    renderBillingConsole();
}

async function updateBillingInvoiceStatus(invoiceId, status) {
    if (!state.session || !hasCapability("billing_write")) {
        setBillingFormMessage("Your role cannot update invoice status.", true);
        return;
    }

    const invoice = state.billing.invoices.find((candidate) => candidate.id === invoiceId);
    if (!invoice) {
        setBillingFormMessage("Select an invoice before updating its status.", true);
        return;
    }

    const timestamp = new Date().toISOString();

    if (state.billing.source === "live") {
        try {
            const response = await fetch(`${runtimeConfig.billingInvoicesUrl ?? ""}/${invoiceId}/status`, {
                method: "PUT",
                cache: "no-store",
                credentials: "same-origin",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    status,
                    externalPaymentReference: status === "PAID" ? invoice.externalPaymentReference ?? `ACME-DEMO-${Date.now()}` : invoice.externalPaymentReference,
                    notes: invoice.notes || null
                })
            });

            const payload = await response.json().catch(() => ({}));
            if (!response.ok) {
                throw new Error(payload.message ?? `HTTP ${response.status}`);
            }

            setBillingFormMessage(`Invoice ${invoice.invoiceNumber} updated to ${billingStatusLabel(status)}.`, false);
            await tryLoadBillingWorkspace();
            return;
        } catch (error) {
            console.warn("Unable to update live billing invoice status.", error);
            setBillingFormMessage(error.message || "Unable to update the live billing ledger.", true);
            return;
        }
    }

    state.billing.invoices = state.billing.invoices.map((candidate) => {
        if (candidate.id !== invoiceId) {
            return candidate;
        }

        return normalizeBillingInvoice({
            ...candidate,
            status,
            balanceDue: status === "PAID" || status === "VOID" ? 0 : candidate.totalAmount,
            externalPaymentReference: status === "PAID"
                ? candidate.externalPaymentReference ?? `SEED-${Date.now()}`
                : candidate.externalPaymentReference,
            updatedAt: timestamp
        });
    });

    persistSeedBillingInvoices();
    state.billing.lastSyncLabel = `Preview update ${formatClock(new Date())}`;
    ensureBillingSelection();
    renderBillingConsole();
    setBillingFormMessage(`Seed invoice ${invoice.invoiceNumber} updated to ${billingStatusLabel(status)}.`, false);
}

function buildBillingInvoiceRequest() {
    const userId = elements.billingCreateAccount.value;
    const title = elements.billingLineTitle.value.trim();
    const quantity = Math.max(1, Number.parseInt(elements.billingLineQuantity.value, 10) || 1);
    const unitAmount = billingAmount(elements.billingLineAmount.value);
    const dueDate = elements.billingDueDate.value;
    const notes = elements.billingNotes.value.trim();

    return {
        userId,
        title,
        quantity,
        unitAmount,
        dueDate,
        notes,
        billingCycle: elements.billingCycle.value || "ONE_TIME"
    };
}

async function submitBillingInvoice(event) {
    event.preventDefault();

    if (!state.session || !hasCapability("billing_write")) {
        setBillingFormMessage("Your role cannot create invoices.", true);
        return;
    }

    const request = buildBillingInvoiceRequest();
    if (!request.userId) {
        setBillingFormMessage("Choose an account before creating an invoice.", true);
        return;
    }
    if (!request.title) {
        setBillingFormMessage("Add a line item title for the invoice.", true);
        return;
    }
    if (request.unitAmount <= 0) {
        setBillingFormMessage("Unit amount must be greater than zero.", true);
        return;
    }
    if (!request.dueDate) {
        setBillingFormMessage("Choose a due date for the invoice.", true);
        return;
    }

    const payload = {
        userId: request.userId,
        orderId: null,
        subscriptionId: null,
        billingCycle: request.billingCycle,
        currency: "USD",
        issuedDate: isoDateFromOffset(0),
        dueDate: request.dueDate,
        servicePeriodStart: isoDateFromOffset(-30),
        servicePeriodEnd: isoDateFromOffset(0),
        taxAmount: 0,
        discountAmount: 0,
        notes: request.notes || null,
        lineItems: [
            {
                title: request.title,
                description: request.notes || null,
                quantity: request.quantity,
                unitAmount: request.unitAmount,
                servicePeriodStart: isoDateFromOffset(-30),
                servicePeriodEnd: isoDateFromOffset(0)
            }
        ]
    };

    if (state.billing.source === "live") {
        setBillingFormMessage("Creating invoice in live billing ledger...", false);
        try {
            const response = await fetch(runtimeConfig.billingInvoicesUrl ?? "", {
                method: "POST",
                cache: "no-store",
                credentials: "same-origin",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(payload)
            });

            const invoice = await response.json().catch(() => ({}));
            if (!response.ok) {
                throw new Error(invoice.message ?? `HTTP ${response.status}`);
            }

            elements.billingCreateForm.reset();
            elements.billingLineQuantity.value = "1";
            elements.billingDueDate.value = isoDateFromOffset(14);
            setBillingFormMessage(`Invoice ${invoice.invoiceNumber ?? "created"} saved to the live ledger.`, false);
            await tryLoadBillingWorkspace();
            return;
        } catch (error) {
            console.warn("Unable to create live billing invoice.", error);
            setBillingFormMessage(error.message || "Unable to create invoice in the live billing ledger.", true);
            return;
        }
    }

    const rawInvoice = {
        id: createClientId("invoice"),
        invoiceNumber: `BILL-${isoDateFromOffset(0).replaceAll("-", "")}-${String(state.billing.invoices.length + 1).padStart(3, "0")}`,
        userId: request.userId,
        status: "OPEN",
        billingCycle: request.billingCycle,
        currency: "USD",
        subtotalAmount: request.quantity * request.unitAmount,
        taxAmount: 0,
        discountAmount: 0,
        totalAmount: request.quantity * request.unitAmount,
        balanceDue: request.quantity * request.unitAmount,
        issuedDate: isoDateFromOffset(0),
        dueDate: request.dueDate,
        servicePeriodStart: isoDateFromOffset(-30),
        servicePeriodEnd: isoDateFromOffset(0),
        notes: request.notes,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        lineItems: [
            {
                id: createClientId("line"),
                title: request.title,
                description: request.notes,
                quantity: request.quantity,
                unitAmount: request.unitAmount
            }
        ]
    };

    const invoice = normalizeBillingInvoice(rawInvoice);
    state.billing.invoices = sortBillingInvoices([invoice, ...state.billing.invoices]);
    state.billing.selectedAccountId = "all";
    state.billing.selectedInvoiceId = invoice.id;
    state.billing.lastSyncLabel = `Preview update ${formatClock(new Date())}`;
    persistSeedBillingInvoices();
    ensureBillingSelection();
    renderBillingConsole();

    elements.billingCreateForm.reset();
    elements.billingLineQuantity.value = "1";
    elements.billingDueDate.value = isoDateFromOffset(14);
    setBillingFormMessage(`Seed invoice ${invoice.invoiceNumber} created in preview mode.`, false);
}

function handleBillingDetailInteraction(event) {
    const trigger = event.target.closest("[data-action='billing-status']");
    if (!trigger?.dataset.id || !trigger.dataset.status) {
        return;
    }

    updateBillingInvoiceStatus(trigger.dataset.id, trigger.dataset.status);
}

async function tryLoadCatalog() {
    if (!state.session) {
        state.catalogSource = "locked";
        state.library = buildLibrary([]);
        syncSelection();
        hydratePlayerForCurrentSelection();
        renderLayout();
        return;
    }

    try {
        const response = await fetch(runtimeConfig.catalogUrl ?? "", {
            cache: "no-store",
            credentials: "same-origin"
        });
        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            syncSelection();
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const items = await response.json();
        if (!Array.isArray(items) || !items.length) {
            throw new Error("empty catalog");
        }

        state.catalogSource = "live";
        state.library = buildLibrary(items);
    } catch (error) {
        console.warn("Falling back to seeded library.", error);
        state.catalogSource = "seed";
        state.library = buildLibrary([]);
    }

    syncSelection();
    hydratePlayerForCurrentSelection();
    renderLayout();
}

async function submitRtspJob(event) {
    event.preventDefault();

    if (!state.session) {
        setRtspFormMessage("Sign in to register RTSP ingest jobs.", true);
        return;
    }

    if (!hasCapability("ingest")) {
        setRtspFormMessage("Your role can review ingest activity but cannot register new RTSP sources.", true);
        return;
    }

    const contentId = elements.rtspTargetContent.value;
    const target = state.library.find((item) => item.id === contentId);
    const sourceUrl = elements.rtspSourceUrl.value.trim();
    const captureDurationSeconds = Number.parseInt(elements.rtspDuration.value, 10) || 300;

    if (!target) {
        setRtspFormMessage("Select a portfolio asset before creating an ingest job.", true);
        return;
    }

    if (!sourceUrl.toLowerCase().startsWith("rtsp://")) {
        setRtspFormMessage("RTSP source URLs must begin with rtsp://", true);
        return;
    }

    setRtspFormMessage("Registering RTSP intake job...", false);

    try {
        const response = await fetch(runtimeConfig.rtspJobsUrl ?? "", {
            method: "POST",
            cache: "no-store",
            credentials: "same-origin",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                contentId,
                mediaType: target.type,
                targetTitle: target.title,
                sourceUrl,
                captureDurationSeconds,
                operatorEmail: state.session.email
            })
        });

        const payload = await response.json().catch(() => ({}));
        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(payload.message ?? `HTTP ${response.status}`);
        }

        elements.rtspSourceUrl.value = "";
        await loadRtspJobs({ silent: true });
        setRtspFormMessage(`RTSP intake registered for ${target.title}.`, false);
    } catch (error) {
        console.warn("Unable to create RTSP ingest job.", error);
        setRtspFormMessage(error.message || "Unable to create RTSP ingest job.", true);
    }
}

async function activateBroadcastJob(jobId) {
    if (!state.session || !hasCapability("ingest")) {
        setRtspFormMessage("Your role can review ingest activity but cannot switch the external distribution feed.", true);
        return;
    }

    const job = state.rtspJobs.find((candidate) => candidate.jobId === jobId);
    if (!isRtspJobActivatable(job)) {
        setRtspFormMessage("Only capturing or ready RTSP jobs can be taken live.", true);
        return;
    }

    setRtspFormMessage(`Taking ${job.targetTitle} live on the external distribution channel...`, false);

    try {
        const response = await fetch(`${runtimeConfig.broadcastActivateUrlBase ?? "/api/v1/demo/media/broadcast/jobs"}/${encodeURIComponent(jobId)}/activate`, {
            method: "POST",
            cache: "no-store",
            credentials: "same-origin"
        });

        const payload = await response.json().catch(() => ({}));
        if (response.status === 401) {
            clearSessionState(true);
            state.catalogSource = "locked";
            state.library = buildLibrary([]);
            showAuthMessage("Your session expired. Sign in again to continue.", true);
            hydratePlayerForCurrentSelection();
            renderLayout();
            return;
        }

        if (!response.ok) {
            throw new Error(payload.message ?? `HTTP ${response.status}`);
        }

        state.broadcast = normalizeBroadcastStatus(payload);
        renderLayout();
        setRtspFormMessage(`${job.targetTitle} is now live at ${absoluteUrl(state.broadcast.publicPageUrl)}.`, false);
    } catch (error) {
        console.warn("Unable to switch the external distribution feed.", error);
        setRtspFormMessage(error.message || "Unable to switch the external distribution feed.", true);
    }
}

function handleRtspJobInteraction(event) {
    const trigger = event.target.closest("[data-action]");
    if (!trigger?.dataset.jobId) {
        return;
    }

    if (trigger.dataset.action === "take-live") {
        activateBroadcastJob(trigger.dataset.jobId);
        return;
    }

    const job = state.rtspJobs.find((candidate) => candidate.jobId === trigger.dataset.jobId);
    if (!job?.playbackUrl) {
        return;
    }

    if (trigger.dataset.action !== "play-job") {
        return;
    }

    if (job.contentId && state.library.some((item) => item.id === job.contentId)) {
        state.selectedId = job.contentId;
        persistSelectedId();
    }

    loadPlayerSource(job.playbackUrl, {
        id: job.contentId ?? "",
        title: `${job.targetTitle} Ingest Review`,
        description: `Captured from ${job.sourceUrl} and routed into the Acme review floor for operator validation.`,
        badges: [job.mediaType, "RTSP intake", job.status],
        kind: "rtsp"
    });
    renderLayout();
    goToPage("player");
}

async function submitDemoMonkeyConfig(event) {
    event.preventDefault();

    const request = buildDemoMonkeyRequest({ preset: "custom" });
    if (
        request.enabled
        && request.startupDelayMs <= 0
        && request.throttleKbps <= 0
        && request.disconnectAfterKb <= 0
        && !request.playbackFailureEnabled
        && !request.traceMapFailureEnabled
        && !request.frontendExceptionEnabled
        && !request.slowAdEnabled
        && !request.adLoadFailureEnabled
    ) {
        setDemoMonkeyMessage("Enable at least one playback, sponsor, trace, or browser fault or disable simulation.", true);
        return;
    }

    setDemoMonkeyMessage(request.enabled ? "Applying playback, sponsor, and browser incident conditions..." : "Disabling incident simulation...", false);
    await updateDemoMonkeyConfig(
        request,
        request.enabled ? "Incident simulation changes applied across the operating surfaces." : "Incident simulation bypassed."
    );
}

async function disableDemoMonkey() {
    setDemoMonkeyMessage("Disabling incident simulation...", false);
    await updateDemoMonkeyConfig(demoMonkeyPresets.clear, "Incident simulation bypassed.");
}

async function handleDemoMonkeyPresetClick(event) {
    const trigger = event.target.closest("[data-preset]");
    if (!trigger?.dataset.preset) {
        return;
    }

    const preset = demoMonkeyPresets[trigger.dataset.preset];
    if (!preset) {
        return;
    }

    setDemoMonkeyMessage(`${preset.label} preset selected.`, false);
    await updateDemoMonkeyConfig(
        preset,
        preset.enabled ? `${preset.label} is active across the incident surfaces.` : "Incident simulation bypassed."
    );
}

async function handleLaunchPersonaClick(event) {
    const trigger = event.target.closest("[data-launch-persona]");
    if (!trigger?.dataset.launchPersona) {
        return;
    }

    await launchPersona(trigger.dataset.launchPersona, trigger.dataset.launchPage);
}

async function handleLaunchScenarioClick(event) {
    const trigger = event.target.closest("[data-launch-scenario]");
    if (!trigger?.dataset.launchScenario) {
        return;
    }

    await launchScenarioShortcut(trigger.dataset.launchScenario);
}

async function refreshWorkspaceData() {
    await tryLoadCatalog();
    const followUpLoads = [
        loadRtspJobs({ silent: true }),
        tryLoadBillingWorkspace(),
        refreshServiceHealth(),
        loadBroadcastStatus({ silent: true }),
        loadDemoMonkeyStatus({ silent: true })
    ];
    if (state.session) {
        followUpLoads.push(loadAdProgramQueue({ silent: true }));
        followUpLoads.push(loadAdIssueStatus({ silent: true }));
    }

    await Promise.all(followUpLoads);
    renderLayout();
}

elements.authForm.addEventListener("submit", signIn);
elements.authOverlay.addEventListener("click", handleLaunchPersonaClick);
elements.authOverlay.addEventListener("click", handleLaunchScenarioClick);
elements.signOut.addEventListener("click", signOut);
elements.surpriseMe.addEventListener("click", playRandomTitle);
elements.presentationToggle.addEventListener("click", () => {
    togglePresentationMode();
});
elements.playFeatured.addEventListener("click", loadFeaturedTitle);
elements.saveFeatured.addEventListener("click", () => {
    const item = currentItem();
    if (item) {
        toggleMyList(item.id);
    }
});
elements.myListPreview.addEventListener("click", (event) => {
    const trigger = event.target.closest("[data-action='play']");
    if (trigger?.dataset.id) {
        playItem(trigger.dataset.id);
    }
});
elements.playerForm.addEventListener("submit", savePlayerSettings);
elements.loadDemo.addEventListener("click", loadFeaturedTitle);
elements.refreshCatalog.addEventListener("click", refreshWorkspaceData);
elements.rtspJobForm.addEventListener("submit", submitRtspJob);
elements.adIssueForm.addEventListener("submit", submitAdIssueConfig);
elements.adIssueClear.addEventListener("click", clearAdIssueConfig);
elements.adIssuePreset.addEventListener("change", handleAdIssuePresetChange);
elements.rtspRefresh.addEventListener("click", () => {
    Promise.all([
        loadRtspJobs(),
        loadBroadcastStatus(),
        loadAdProgramQueue({ silent: true }),
        loadAdIssueStatus({ silent: true })
    ]);
});
elements.rtspJobs.addEventListener("click", handleRtspJobInteraction);
elements.demoMonkeyForm.addEventListener("submit", submitDemoMonkeyConfig);
elements.demoMonkeyDisable.addEventListener("click", disableDemoMonkey);
elements.demoMonkeyPresets.addEventListener("click", handleDemoMonkeyPresetClick);
elements.billingAccountFilter.addEventListener("change", handleBillingAccountFilterChange);
elements.billingStatusFilter.addEventListener("change", handleBillingStatusFilterChange);
elements.billingOverdueOnly.addEventListener("change", handleBillingOverdueToggle);
elements.billingRefresh.addEventListener("click", () => {
    tryLoadBillingWorkspace();
});
elements.billingInvoiceList.addEventListener("click", handleBillingInvoiceSelection);
elements.billingDetail.addEventListener("click", handleBillingDetailInteraction);
elements.billingCreateForm.addEventListener("submit", submitBillingInvoice);
elements.genreChips.addEventListener("click", handleGenreSelection);
elements.librarySearch.addEventListener("input", handleLibrarySearch);
elements.channelGrid.addEventListener("click", handleCardInteraction);
elements.rundownList.addEventListener("click", handleCardInteraction);
elements.continueRow.addEventListener("click", handleCardInteraction);
elements.featuredRow.addEventListener("click", handleCardInteraction);
elements.libraryGrid.addEventListener("click", handleCardInteraction);
window.addEventListener("hashchange", () => {
    renderLayout();
});
document.addEventListener("fullscreenchange", () => {
    if (!document.fullscreenElement) {
        presentationFallbackEnabled = false;
    }
    syncPresentationMode();
});

elements.moviePlayer.addEventListener("loadedmetadata", () => {
    const record = watchRecord(state.pendingResumeId);
    if (state.pendingResumeId && record?.currentTime && record.currentTime > 5 && record.currentTime < elements.moviePlayer.duration - 5) {
        elements.moviePlayer.currentTime = Math.min(record.currentTime, Math.max(0, elements.moviePlayer.duration - 3));
        setPlayerStatus("ready", `Ready to resume from ${formatTime(record.currentTime)}.`);
    } else {
        setPlayerStatus("ready", "Playback is ready.");
    }

    state.pendingResumeId = "";
    updatePlaybackProgressVisual();
});

elements.moviePlayer.addEventListener("playing", () => {
    setPlayerStatus("ready", "Stream is playing.");
});

elements.moviePlayer.addEventListener("waiting", () => {
    setPlayerStatus("risk", "Buffering playback.");
});

elements.moviePlayer.addEventListener("pause", () => {
    if (!elements.moviePlayer.ended && elements.moviePlayer.currentTime > 0) {
        setPlayerStatus("risk", "Playback paused.");
        saveProgressSnapshot(true);
        renderLayout();
    }
});

elements.moviePlayer.addEventListener("timeupdate", () => {
    updatePlaybackProgressVisual();
    saveProgressSnapshot(false);
});

elements.moviePlayer.addEventListener("ended", () => {
    updatePlaybackProgressVisual();
    saveProgressSnapshot(true);
    setPlayerStatus("ready", "Title complete.");
    renderLayout();
});

elements.moviePlayer.addEventListener("error", () => {
    setPlayerStatus("blocked", "The review source failed to load. Try the selected asset or another approved media URL.");
});

async function bootstrap() {
    state.session = null;
    state.catalogSource = "locked";
    state.library = buildLibrary([]);
    syncSelection();
    hydratePlayerForCurrentSelection();
    renderLayout();
    await Promise.all([
        refreshServiceHealth(),
        loadBroadcastStatus({ silent: true }),
        loadDemoMonkeyStatus({ silent: true })
    ]);
    renderLayout();

    const restored = await restoreSession();
    if (!restored) {
        showAuthMessage("Use a booth persona for the fastest path, or sign in manually with your Acme Broadcasting account.", false);
        hydratePlayerForCurrentSelection();
        renderLayout();
        return;
    }

    await refreshWorkspaceData();
    showAuthMessage("", false);
}

bootstrap().catch((error) => {
    console.warn("Workspace bootstrap failed.", error);
    showAuthMessage("Unable to initialize the protected workspace.", true);
    clearPlayerForSignedOutState();
    renderLayout();
});
