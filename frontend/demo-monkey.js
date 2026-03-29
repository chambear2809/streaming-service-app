const runtimeConfig = window.STREAMING_CONFIG ?? {};

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
    "packet-loss": {
        label: "Packet loss",
        enabled: true,
        preset: "packet-loss",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        packetLossPercent: 20,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        dependencyTimeoutEnabled: false,
        dependencyTimeoutService: "",
        dependencyFailureEnabled: false,
        dependencyFailureService: "",
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "playback-outage": {
        label: "Playback outage",
        enabled: true,
        preset: "playback-outage",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: true,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "trace-map-outage": {
        label: "Trace pivot outage",
        enabled: true,
        preset: "trace-map-outage",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: true,
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "dependency-timeout": {
        label: "Dependency timeout",
        enabled: true,
        preset: "dependency-timeout",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        packetLossPercent: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        dependencyTimeoutEnabled: true,
        dependencyTimeoutService: "ad-service-demo",
        dependencyFailureEnabled: false,
        dependencyFailureService: "",
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "service-specific-failure": {
        label: "Service failure",
        enabled: true,
        preset: "service-specific-failure",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        packetLossPercent: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        dependencyTimeoutEnabled: false,
        dependencyTimeoutService: "",
        dependencyFailureEnabled: true,
        dependencyFailureService: "billing-service",
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false
    },
    "frontend-crash": {
        label: "Frontend crash",
        enabled: true,
        preset: "frontend-crash",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: true,
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
    "one-break-sponsor-miss": {
        label: "One-break sponsor miss",
        enabled: true,
        preset: "one-break-sponsor-miss",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        frontendExceptionEnabled: false,
        slowAdEnabled: true,
        adLoadFailureEnabled: true,
        nextBreakOnlyEnabled: true
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

const demoMonkeyDependencyOptions = [
    { value: "user-service-demo", label: "User service" },
    { value: "content-service-demo", label: "Content service" },
    { value: "billing-service", label: "Billing service" },
    { value: "ad-service-demo", label: "Ad service" }
];

const defaultDemoMonkeyDependencyService = "ad-service-demo";
const roleCapabilities = {
    billing_admin: ["operations", "governance", "billing", "billing_write"],
    finance_analyst: ["operations", "billing", "billing_write"],
    platform_admin: ["operations", "governance", "ingest", "billing", "billing_write"],
    programming_manager: ["operations", "governance", "ingest"],
    executive: ["operations", "governance", "billing"],
    qa_reviewer: ["operations"],
    staff_operator: ["operations"]
};

const elements = {
    summary: document.querySelector("#demo-monkey-summary"),
    status: document.querySelector("#demo-monkey-status"),
    statusCopy: document.querySelector("#demo-monkey-status-copy"),
    scopeTitle: document.querySelector("#demo-monkey-scope-title"),
    scopeCopy: document.querySelector("#demo-monkey-scope-copy"),
    profile: document.querySelector("#demo-monkey-profile"),
    updated: document.querySelector("#demo-monkey-updated"),
    api: document.querySelector("#demo-monkey-api"),
    publicLink: document.querySelector("#demo-monkey-public-link"),
    liveSource: document.querySelector("#demo-monkey-live-source"),
    liveSourceCopy: document.querySelector("#demo-monkey-live-source-copy"),
    livePod: document.querySelector("#demo-monkey-live-pod"),
    livePodCopy: document.querySelector("#demo-monkey-live-pod-copy"),
    liveBreak: document.querySelector("#demo-monkey-live-break"),
    liveBreakCopy: document.querySelector("#demo-monkey-live-break-copy"),
    duration: document.querySelector("#demo-monkey-duration"),
    durationCopy: document.querySelector("#demo-monkey-duration-copy"),
    storyHeadline: document.querySelector("#demo-monkey-story-headline"),
    storyCopy: document.querySelector("#demo-monkey-story-copy"),
    pivotTitle: document.querySelector("#demo-monkey-pivot-title"),
    pivotCopy: document.querySelector("#demo-monkey-pivot-copy"),
    revenueTitle: document.querySelector("#demo-monkey-revenue-title"),
    revenueCopy: document.querySelector("#demo-monkey-revenue-copy"),
    scriptStep1: document.querySelector("#demo-monkey-script-step1"),
    scriptStep1Copy: document.querySelector("#demo-monkey-script-step1-copy"),
    scriptStep2: document.querySelector("#demo-monkey-script-step2"),
    scriptStep2Copy: document.querySelector("#demo-monkey-script-step2-copy"),
    scriptStep3: document.querySelector("#demo-monkey-script-step3"),
    scriptStep3Copy: document.querySelector("#demo-monkey-script-step3-copy"),
    launchTitle: document.querySelector("#demo-monkey-launch-title"),
    launchCopy: document.querySelector("#demo-monkey-launch-copy"),
    launchThousandEyes: document.querySelector("#demo-monkey-launch-thousandeyes"),
    launchApm: document.querySelector("#demo-monkey-launch-apm"),
    launchRum: document.querySelector("#demo-monkey-launch-rum"),
    presentationToggle: document.querySelector("#demo-monkey-presentation-toggle"),
    form: document.querySelector("#demo-monkey-form"),
    presets: document.querySelector("#demo-monkey-presets"),
    enabled: document.querySelector("#demo-monkey-enabled"),
    nextBreakOnlyEnabled: document.querySelector("#demo-monkey-next-break-only"),
    latencyEnabled: document.querySelector("#demo-monkey-latency-enabled"),
    latencyValue: document.querySelector("#demo-monkey-latency-value"),
    throttleEnabled: document.querySelector("#demo-monkey-throttle-enabled"),
    throttleValue: document.querySelector("#demo-monkey-throttle-value"),
    disconnectEnabled: document.querySelector("#demo-monkey-disconnect-enabled"),
    disconnectValue: document.querySelector("#demo-monkey-disconnect-value"),
    packetLossEnabled: document.querySelector("#demo-monkey-packet-loss-enabled"),
    packetLossValue: document.querySelector("#demo-monkey-packet-loss-value"),
    playbackFailureEnabled: document.querySelector("#demo-monkey-playback-failure-enabled"),
    traceMapFailureEnabled: document.querySelector("#demo-monkey-trace-map-failure-enabled"),
    dependencyTimeoutEnabled: document.querySelector("#demo-monkey-dependency-timeout-enabled"),
    dependencyTimeoutService: document.querySelector("#demo-monkey-dependency-timeout-service"),
    dependencyFailureEnabled: document.querySelector("#demo-monkey-dependency-failure-enabled"),
    dependencyFailureService: document.querySelector("#demo-monkey-dependency-failure-service"),
    frontendExceptionEnabled: document.querySelector("#demo-monkey-frontend-exception-enabled"),
    slowAdEnabled: document.querySelector("#demo-monkey-slow-ad-enabled"),
    adLoadFailureEnabled: document.querySelector("#demo-monkey-ad-load-failure-enabled"),
    disable: document.querySelector("#demo-monkey-disable"),
    message: document.querySelector("#demo-monkey-message")
};

const state = {
    canWrite: false,
    current: {
        enabled: false,
        preset: "clear",
        startupDelayMs: 0,
        throttleKbps: 0,
        disconnectAfterKb: 0,
        packetLossPercent: 0,
        playbackFailureEnabled: false,
        traceMapFailureEnabled: false,
        dependencyTimeoutEnabled: false,
        dependencyTimeoutService: "",
        dependencyFailureEnabled: false,
        dependencyFailureService: "",
        frontendExceptionEnabled: false,
        slowAdEnabled: false,
        adLoadFailureEnabled: false,
        nextBreakOnlyEnabled: false,
        autoClearAt: "",
        updatedAt: "",
        summary: "Incident simulation is bypassed. Screening, sponsor insertion, and external distribution are flowing normally.",
        scope: "Applies to protected MP4 screening, the external broadcast feed, sponsor insertion between queued videos, the public trace pivot, and optional browser-error injection for incident walkthroughs.",
        affectedPaths: []
    },
    broadcast: {
        status: "",
        title: "",
        detail: "",
        publicPageUrl: "",
        adStatus: {
            state: "",
            podLabel: "",
            sponsorLabel: "",
            breakStartAt: "",
            breakEndAt: "",
            detail: ""
        }
    }
};

let lastFrontendFaultKey = "";
let presentationFallbackEnabled = false;

function hasRoleCapability(role, capability) {
    const permissions = roleCapabilities[String(role ?? "").trim().toLowerCase()] ?? roleCapabilities.staff_operator;
    return permissions.includes(capability);
}

function absoluteUrl(path) {
    try {
        return new URL(path || "/", window.location.origin).href;
    } catch (_error) {
        return path || "/";
    }
}

function formatStamp(dateLike) {
    if (!dateLike) {
        return "Awaiting operator change";
    }

    const date = new Date(dateLike);
    if (Number.isNaN(date.getTime())) {
        return String(dateLike);
    }

    return date.toLocaleString([], {
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit"
    });
}

function formatShortTime(dateLike) {
    if (!dateLike) {
        return "Awaiting timing";
    }

    const date = new Date(dateLike);
    if (Number.isNaN(date.getTime())) {
        return String(dateLike);
    }

    return date.toLocaleString([], {
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit"
    });
}

function formatLabel(value, fallback = "Unknown") {
    const normalized = String(value ?? "").trim();
    if (!normalized) {
        return fallback;
    }

    return normalized
        .split(/[-_\s]+/)
        .filter(Boolean)
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
        .join(" ");
}

function formatRelativeBreak(startAt, endAt) {
    if (!startAt) {
        return "Waiting for schedule";
    }

    const start = new Date(startAt);
    const end = endAt ? new Date(endAt) : null;
    const now = Date.now();
    if (Number.isNaN(start.getTime())) {
        return String(startAt);
    }

    if (end && !Number.isNaN(end.getTime()) && now >= start.getTime() && now < end.getTime()) {
        return `Live now until ${formatShortTime(endAt)}`;
    }

    const diffMs = start.getTime() - now;
    if (diffMs <= 0) {
        return formatShortTime(startAt);
    }

    const totalSeconds = Math.round(diffMs / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    if (minutes < 60) {
        return `In ${minutes}m ${String(seconds).padStart(2, "0")}s`;
    }

    return formatShortTime(startAt);
}

function secondsUntil(dateLike) {
    if (!dateLike) {
        return null;
    }

    const date = new Date(dateLike);
    if (Number.isNaN(date.getTime())) {
        return null;
    }

    return Math.round((date.getTime() - Date.now()) / 1000);
}

function splunkAppBaseUrl() {
    const realm = String(runtimeConfig?.splunkRum?.realm ?? "us1").trim() || "us1";
    return `https://app.${realm}.signalfx.com/`;
}

function buildLaunchTargets(config) {
    const observabilityLinks = runtimeConfig.observabilityLinks ?? {};
    const targets = {
        thousandEyes: {
            href: observabilityLinks.thousandEyesUrl || runtimeConfig.publicTraceMapUrl || "/api/v1/demo/public/trace-map",
            label: "Open ThousandEyes"
        },
        apm: {
            href: observabilityLinks.splunkApmUrl || splunkAppBaseUrl(),
            label: "Open Splunk APM"
        },
        rum: {
            href: observabilityLinks.splunkRumUrl || splunkAppBaseUrl(),
            label: "Open Browser RUM"
        }
    };

    if (config.frontendExceptionEnabled) {
        return {
            ...targets,
            primary: "rum",
            title: "Lead with the frontend evidence",
            copy: "Open Browser RUM first for the client-side failure, then pivot into APM if the browser error also exposed a backend path."
        };
    }

    if (config.slowAdEnabled || config.adLoadFailureEnabled) {
        return {
            ...targets,
            primary: "apm",
            title: "Default booth flow: Splunk 02 then 03",
            copy: "After the sponsor symptom lands on /broadcast, open the user-impact timeline and backend critical path first. Use Browser RUM next and ThousandEyes for baseline and path context."
        };
    }

    if (config.traceMapFailureEnabled || config.dependencyTimeoutEnabled || config.dependencyFailureEnabled) {
        return {
            ...targets,
            primary: "thousandEyes",
            title: "Lead with the trace pivot path",
            copy: "Start on the public trace pivot or ThousandEyes first, then use the Trace Map deep dive and the APM service map."
        };
    }

    if (config.playbackFailureEnabled) {
        return {
            ...targets,
            primary: "apm",
            title: "Lead with the failed request",
            copy: "Use the playback failure as the proof point, then move into Browser RUM and Splunk APM to isolate the failing dependency."
        };
    }

    if (
        config.packetLossPercent > 0
        || config.disconnectAfterKb > 0
        || config.throttleKbps > 0
        || config.startupDelayMs > 0
    ) {
        return {
            ...targets,
            primary: "thousandEyes",
            title: "Lead with the outside-in symptom",
            copy: "Open ThousandEyes first for playback and path context, then use Splunk 01 and 02 before you decide whether the issue is backend-driven."
        };
    }

    return {
        ...targets,
        primary: "thousandEyes",
        title: "Default booth setup: start in the app",
        copy: "Lead with /broadcast and /#operations, arm Ad break delay near the next sponsor pod, then use ThousandEyes for baseline and Splunk for root cause."
    };
}

function presetLabel(preset) {
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

function dependencyLabel(serviceName) {
    const normalized = String(serviceName ?? "").trim().toLowerCase();
    const match = demoMonkeyDependencyOptions.find((option) => option.value === normalized);
    return match?.label ?? (normalized ? presetLabel(normalized) : "Selected dependency");
}

function normalizeBroadcast(payload) {
    const adPayload = payload?.adStatus ?? {};
    return {
        status: String(payload?.status ?? ""),
        title: String(payload?.title ?? ""),
        detail: String(payload?.detail ?? ""),
        publicPageUrl: String(payload?.publicPageUrl ?? runtimeConfig.publicBroadcastPageUrl ?? "/broadcast"),
        adStatus: {
            state: String(adPayload?.state ?? ""),
            podLabel: String(adPayload?.podLabel ?? ""),
            sponsorLabel: String(adPayload?.sponsorLabel ?? ""),
            decisioningMode: String(adPayload?.decisioningMode ?? ""),
            breakStartAt: String(adPayload?.breakStartAt ?? ""),
            breakEndAt: String(adPayload?.breakEndAt ?? ""),
            detail: String(adPayload?.detail ?? "")
        }
    };
}

function buildPresenterGuide(config, broadcast) {
    const adStatus = broadcast?.adStatus ?? {};
    const podLabel = adStatus.podLabel || "Next sponsor pod";
    const sponsorLabel = adStatus.sponsorLabel || "the queued sponsor";

    if (!config.enabled) {
        return {
            storyHeadline: "Healthy operating surface",
            storyCopy: "Use a narrative preset to create a visible symptom before you pivot into ThousandEyes and Splunk.",
            pivotTitle: "Broadcast then ThousandEyes",
            pivotCopy: "Start with the viewer experience, then move into path visibility and the application trace.",
            revenueTitle: "Sponsor revenue protected",
            revenueCopy: "No sponsor faults are armed, so the next break should deliver normally."
        };
    }

    if (config.adLoadFailureEnabled) {
        return {
            storyHeadline: "Sponsor pod miss is visible",
            storyCopy: `${podLabel} reaches air, but ${sponsorLabel} does not load cleanly at the break boundary.`,
            pivotTitle: "Broadcast then Splunk APM",
            pivotCopy: "Show the empty or stalled break first, then pivot into ad decisioning and manifest assembly latency.",
            revenueTitle: "Revenue at risk",
            revenueCopy: `${podLabel} for ${sponsorLabel} is exposed to missed impression delivery while this scenario is armed.`
        };
    }

    if (config.slowAdEnabled) {
        return {
            storyHeadline: "Sponsor break is delayed",
            storyCopy: `${podLabel} is still queued, but the viewer should feel a short stall before ${sponsorLabel} starts.`,
            pivotTitle: "Control room then Splunk APM",
            pivotCopy: "Use the queued sponsor timing first, then show where ad decisioning or manifest assembly consumed the latency budget.",
            revenueTitle: "Revenue timing is slipping",
            revenueCopy: `${podLabel} is still monetizable, but the break timing is now visibly unstable for ${sponsorLabel}.`
        };
    }

    if (config.playbackFailureEnabled) {
        return {
            storyHeadline: "Playback is hard down",
            storyCopy: "Protected playback returns HTTP 503, so the failure is obvious in both the player flow and backend traces.",
            pivotTitle: "Browser RUM then Splunk APM",
            pivotCopy: "Start with the failed viewer request, then move into the trace and service map to isolate the failing dependency.",
            revenueTitle: "Audience and revenue are blocked",
            revenueCopy: "No playback means no delivered sponsor inventory while the outage is active."
        };
    }

    if (config.dependencyFailureEnabled) {
        const serviceLabel = dependencyLabel(config.dependencyFailureService);
        return {
            storyHeadline: "A selected dependency is failing",
            storyCopy: `${serviceLabel} is returning a synthetic failure inside the public trace pivot, giving you a clean service-map handoff without taking the whole viewer path down.`,
            pivotTitle: "Trace pivot then Splunk APM",
            pivotCopy: "Use the degraded dependency fanout first, then follow the failing hop through the application transaction and service map.",
            revenueTitle: "Diagnosis is focused",
            revenueCopy: "The outage is isolated to the selected upstream service, which is ideal for a dependency-specific troubleshooting drill."
        };
    }

    if (config.dependencyTimeoutEnabled) {
        const serviceLabel = dependencyLabel(config.dependencyTimeoutService);
        return {
            storyHeadline: "A selected dependency is timing out",
            storyCopy: `${serviceLabel} is timing out during the trace fanout, which makes latency and timeout handling visible without forcing a blanket 503.`,
            pivotTitle: "Synthetic trace then APM",
            pivotCopy: "Start on the slowed or degraded trace pivot, then move into the downstream timeout and retry behavior in Splunk APM.",
            revenueTitle: "Response budgets are burning down",
            revenueCopy: "Timeout pressure is building on the selected dependency, which is ideal for retry, queueing, and saturation troubleshooting."
        };
    }

    if (config.frontendExceptionEnabled) {
        return {
            storyHeadline: "Frontend regression is visible",
            storyCopy: "The browser is throwing an unhandled exception, making the client side of the incident easy to prove.",
            pivotTitle: "Browser RUM first",
            pivotCopy: "Lead with the browser exception and route impact, then connect it back to the backend request path if needed.",
            revenueTitle: "Session quality is degrading",
            revenueCopy: "Viewer trust and sponsor completion are both at risk when the player UI is unstable."
        };
    }

    if (config.packetLossPercent > 0 || config.disconnectAfterKb > 0 || config.throttleKbps > 0 || config.startupDelayMs > 0) {
        return {
            storyHeadline: "Viewer startup is degraded",
            storyCopy: config.packetLossPercent > 0
                ? "The stream path is lossy and unstable, so outside-in tools should show intermittent playback failures before the application stack looks hard down."
                : "The stream feels slow or unstable before it fully fails, which is the cleanest outside-in opening for the NAB story.",
            pivotTitle: "ThousandEyes then Splunk APM",
            pivotCopy: "Start on the external experience and path degradation, then follow the same transaction into the service map and trace waterfall.",
            revenueTitle: "Audience patience is dropping",
            revenueCopy: "Long startup and unstable playback reduce the chance that viewers stay through the sponsor breaks."
        };
    }

    if (config.traceMapFailureEnabled) {
        return {
            storyHeadline: "Trace pivot is failing",
            storyCopy: "Use this when you want to make the upstream dependency chain obvious and create a crisp service-map handoff.",
            pivotTitle: "Synthetic path then service map",
            pivotCopy: "Lead with the failing transaction and follow the propagated failure across upstream services.",
            revenueTitle: "Diagnosis is urgent",
            revenueCopy: "Sponsor revenue is not yet failing directly, but teams have lost clean visibility into the affected path."
        };
    }

    return {
        storyHeadline: "Custom incident profile",
        storyCopy: "The active fault mix is valid, but it is no longer one of the opinionated NAB narrative scenarios.",
        pivotTitle: "Follow the strongest visible symptom",
        pivotCopy: "Start wherever the issue is clearest on air, then move into ThousandEyes, APM, or Browser RUM based on the audience symptom.",
        revenueTitle: "Check the sponsor queue",
        revenueCopy: "Use the queued sponsor pod timing to decide whether the current custom fault meaningfully threatens monetization."
    };
}

function buildScriptWalkthrough(config, broadcast) {
    const adStatus = broadcast?.adStatus ?? {};
    const podLabel = adStatus.podLabel || "Next sponsor pod";
    const sponsorLabel = adStatus.sponsorLabel || "the queued sponsor";
    const secondsToBreak = secondsUntil(adStatus.breakStartAt);
    const breakWindowLabel = formatRelativeBreak(adStatus.breakStartAt, adStatus.breakEndAt);
    const breakIsNear = secondsToBreak !== null && secondsToBreak <= 75;
    const breakIsLive = secondsToBreak !== null && secondsToBreak <= 0;
    const timingCue = !adStatus.breakStartAt
        ? "Resolve the next sponsor-break timing before you arm the default booth preset."
        : breakIsLive
            ? `${podLabel} is live now. Keep /broadcast visible and let the symptom land on air.`
            : breakIsNear
                ? `${podLabel} is ${breakWindowLabel}. This is close enough to arm or keep the preset armed and return to the app.`
                : `${podLabel} is ${breakWindowLabel}. Wait until the next sponsor pod is under about 1 minute so the audience does not sit through avoidable dead time.`;

    if (!config.enabled) {
        return {
            step1Title: "Open /broadcast, then /#operations",
            step1Copy: "Lead with the public feed, then show the same sponsor timing in Master Control.",
            step2Title: "Use Ad break delay as the default booth story",
            step2Copy: timingCue,
            step3Title: "After the stall, use Splunk 02 then 03",
            step3Copy: "Keep ThousandEyes in the flow for outside-in baseline and path context. Use Browser RUM after APM."
        };
    }

    if (config.slowAdEnabled && !config.adLoadFailureEnabled) {
        return {
            step1Title: "Keep /broadcast and /#operations open",
            step1Copy: `Use ${podLabel} and ${sponsorLabel} as the shared viewer and control-room proof point for the default booth story.`,
            step2Title: breakIsNear || breakIsLive ? "Return to the app now" : "Keep the preset armed and wait for the next pod",
            step2Copy: timingCue,
            step3Title: "After the stall, use Splunk 02 then 03",
            step3Copy: "Lead with the user-impact timeline and backend critical path. Use Browser RUM after APM and ThousandEyes as supporting path context."
        };
    }

    if (config.adLoadFailureEnabled) {
        return {
            step1Title: "Keep /broadcast on the sponsor boundary",
            step1Copy: `Use ${podLabel} and ${sponsorLabel} to make the miss obvious before you pivot.`,
            step2Title: breakIsNear || breakIsLive ? "Return to the app now" : "Keep the preset armed and wait for the next pod",
            step2Copy: timingCue,
            step3Title: "After the miss, open Splunk 03 then Browser RUM",
            step3Copy: config.nextBreakOnlyEnabled
                ? "Let the one-break profile clear itself after the miss, then recover. Keep APM focused on media-service-demo and ad-service-demo."
                : "Keep APM focused on media-service-demo and ad-service-demo, then use Browser RUM to confirm viewer impact."
        };
    }

    if (config.traceMapFailureEnabled || config.dependencyTimeoutEnabled || config.dependencyFailureEnabled) {
        return {
            step1Title: "Open the public trace pivot and ThousandEyes",
            step1Copy: "This story is cleaner on the trace-map path than on the viewer player.",
            step2Title: "Use the degraded fanout as the proof point",
            step2Copy: "Keep the failing or slow dependency visible before you open the service map.",
            step3Title: "Use Splunk 04, then the APM service map",
            step3Copy: "Follow the failing dependency through media-service-demo into the selected upstream service."
        };
    }

    if (config.frontendExceptionEnabled) {
        return {
            step1Title: "Open the affected page first",
            step1Copy: "The browser-side fault is the proof point, so keep the client surface visible.",
            step2Title: "Let the browser exception fire once",
            step2Copy: "Do not keep refreshing after the first exception unless you want another Browser RUM event.",
            step3Title: "Use Browser RUM first, then APM if needed",
            step3Copy: "Only pivot into backend traces if the client failure exposed a server path too."
        };
    }

    if (config.playbackFailureEnabled) {
        return {
            step1Title: "Keep the viewer path on screen",
            step1Copy: "Make the playback failure obvious before you pivot into telemetry.",
            step2Title: "Use the failed request as the proof point",
            step2Copy: "Once the 503 lands, move immediately into Browser RUM or Splunk APM.",
            step3Title: "Use APM and Browser RUM together",
            step3Copy: "Start with the failing request, then isolate the dependency in the trace and service map."
        };
    }

    if (config.packetLossPercent > 0 || config.disconnectAfterKb > 0 || config.throttleKbps > 0 || config.startupDelayMs > 0) {
        return {
            step1Title: "Open /broadcast, then ThousandEyes",
            step1Copy: "Lead with the public feed and the playback and trace-map tests.",
            step2Title: "Use the outside-in symptom first",
            step2Copy: "Keep the unstable viewer experience visible while the path signal catches up.",
            step3Title: "Use ThousandEyes first, then Splunk 01 and 02",
            step3Copy: "Only pivot deeper into APM if the outside-in symptom points at the application path."
        };
    }

    return {
        step1Title: "Lead with the clearest app surface",
        step1Copy: "Start where the audience can see the symptom most easily.",
        step2Title: "Use the current sponsor timing as the pacing cue",
        step2Copy: timingCue,
        step3Title: "Then choose the matching telemetry pivot",
        step3Copy: "Use ThousandEyes, Splunk APM, or Browser RUM based on whether the symptom is network, backend, or frontend first."
    };
}

function buildSummary(config) {
    if (!config.enabled) {
        return "Incident simulation is bypassed. Screening, sponsor insertion, and external distribution are flowing normally.";
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
    if (config.packetLossPercent > 0) {
        effects.push(`${config.packetLossPercent}% of playback transfers drop before completion`);
    }
    if (config.playbackFailureEnabled) {
        effects.push("playback responses return HTTP 503");
    }
    if (config.traceMapFailureEnabled) {
        effects.push("trace pivot returns HTTP 503");
    }
    if (config.dependencyTimeoutEnabled && config.dependencyTimeoutService) {
        effects.push(`${dependencyLabel(config.dependencyTimeoutService)} times out in the trace pivot`);
    }
    if (config.dependencyFailureEnabled && config.dependencyFailureService) {
        effects.push(`${dependencyLabel(config.dependencyFailureService)} returns HTTP 503 in the trace pivot`);
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

function normalize(payload) {
    const enabled = Boolean(payload?.enabled);
    const normalized = {
        enabled,
        preset: String(payload?.preset ?? (enabled ? "custom" : "clear")).trim().toLowerCase() || (enabled ? "custom" : "clear"),
        startupDelayMs: Number.parseInt(payload?.startupDelayMs, 10) || 0,
        throttleKbps: Number.parseInt(payload?.throttleKbps, 10) || 0,
        disconnectAfterKb: Number.parseInt(payload?.disconnectAfterKb, 10) || 0,
        packetLossPercent: Number.parseInt(payload?.packetLossPercent, 10) || 0,
        playbackFailureEnabled: Boolean(payload?.playbackFailureEnabled),
        traceMapFailureEnabled: Boolean(payload?.traceMapFailureEnabled),
        dependencyTimeoutEnabled: Boolean(payload?.dependencyTimeoutEnabled),
        dependencyTimeoutService: String(payload?.dependencyTimeoutService ?? ""),
        dependencyFailureEnabled: Boolean(payload?.dependencyFailureEnabled),
        dependencyFailureService: String(payload?.dependencyFailureService ?? ""),
        frontendExceptionEnabled: Boolean(payload?.frontendExceptionEnabled),
        slowAdEnabled: Boolean(payload?.slowAdEnabled),
        adLoadFailureEnabled: Boolean(payload?.adLoadFailureEnabled),
        nextBreakOnlyEnabled: Boolean(payload?.nextBreakOnlyEnabled),
        autoClearAt: String(payload?.autoClearAt ?? ""),
        updatedAt: String(payload?.updatedAt ?? ""),
        summary: String(payload?.summary ?? ""),
        scope: String(payload?.scope ?? "Applies to protected MP4 screening, the external broadcast feed, sponsor insertion between queued videos, the public trace pivot, and optional browser-error injection for incident walkthroughs."),
        affectedPaths: Array.isArray(payload?.affectedPaths) ? payload.affectedPaths.map((path) => String(path)) : []
    };

    if (!normalized.summary) {
        normalized.summary = buildSummary(normalized);
    }

    return normalized;
}

function setMessage(message, isError = false) {
    elements.message.textContent = message;
    elements.message.dataset.state = isError ? "error" : "success";
}

function applyWriteAccess() {
    const writable = Boolean(state.canWrite);

    for (const input of elements.form.querySelectorAll("input, select, button[type='submit']")) {
        input.disabled = !writable;
    }

    for (const button of elements.presets.querySelectorAll("[data-preset]")) {
        button.disabled = !writable;
    }

    elements.disable.disabled = !writable || !state.current.enabled;
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
        console.warn("Unable to toggle incident-simulation presentation mode.", error);
        presentationFallbackEnabled = !presentationFallbackEnabled;
    }

    syncPresentationMode();
}

function maybeEmitFrontendException(config = state.current) {
    if (!config?.enabled || !config.frontendExceptionEnabled) {
        return;
    }

    const faultKey = [config.updatedAt, config.preset, window.location.pathname].join(":");
    if (!config.updatedAt || lastFrontendFaultKey === faultKey) {
        return;
    }

    lastFrontendFaultKey = faultKey;
    window.setTimeout(() => {
        throw new Error(`Incident simulation injected browser exception for ${presetLabel(config.preset)} on ${window.location.pathname}.`);
    }, 0);
}

function render() {
    const current = state.current;
    const broadcast = state.broadcast;
    const adStatus = broadcast?.adStatus ?? {};
    const presenterGuide = buildPresenterGuide(current, broadcast);
    const scriptWalkthrough = buildScriptWalkthrough(current, broadcast);
    const launchTargets = buildLaunchTargets(current);
    const currentPresetLabel = presetLabel(current.preset);
    const startupDelayValue = current.startupDelayMs || Number.parseInt(elements.latencyValue.value, 10) || 2500;
    const throttleValue = current.throttleKbps || Number.parseInt(elements.throttleValue.value, 10) || 768;
    const disconnectValue = current.disconnectAfterKb || Number.parseInt(elements.disconnectValue.value, 10) || 384;
    const publicIncidentPath = runtimeConfig.publicDemoMonkeyUrl ?? "/api/v1/demo/public/demo-monkey";
    const apiUrl = absoluteUrl(runtimeConfig.publicDemoMonkeyUrl ?? "/api/v1/demo/public/demo-monkey");
    const affectedCount = current.affectedPaths.length;
    const liveSourceLabel = broadcast?.status === "ON_AIR"
        ? "External contribution"
        : broadcast?.status === "DEMO_LOOP"
            ? "House loop"
            : broadcast?.title || "Awaiting broadcast status";

    elements.summary.textContent = current.summary;
    elements.status.textContent = current.enabled ? "Active" : "Bypassed";
    elements.statusCopy.textContent = current.enabled
        ? `${currentPresetLabel} is active on the screening, sponsor, and distribution surfaces.`
        : "No playback, sponsor, trace, or browser faults are being injected into the operating surfaces.";
    elements.scopeTitle.textContent = "Operational impact surface";
    elements.scopeCopy.textContent = affectedCount
        ? `Covers ${affectedCount} surfaces across screening, sponsor insertion, trace pivot, and browser faults.`
        : current.scope;
    elements.profile.textContent = current.enabled ? currentPresetLabel : "Simulation bypassed";
    elements.updated.textContent = formatStamp(current.updatedAt);
    elements.duration.textContent = current.enabled
        ? current.nextBreakOnlyEnabled
            ? "Next sponsor pod only"
            : "Persistent until cleared"
        : "Bypassed";
    elements.durationCopy.textContent = current.enabled && current.nextBreakOnlyEnabled && current.autoClearAt
        ? `This profile auto-clears after the sponsor pod that ends at ${formatShortTime(current.autoClearAt)}.`
        : current.enabled
            ? "The active incident profile remains armed until an operator clears it."
            : "No incident profile is currently armed.";
    elements.api.textContent = publicIncidentPath;
    elements.api.title = apiUrl;
    elements.publicLink.href = broadcast.publicPageUrl || runtimeConfig.publicBroadcastPageUrl || "/broadcast";
    elements.liveSource.textContent = liveSourceLabel;
    elements.liveSourceCopy.textContent = broadcast?.detail
        || "The public channel status has not been loaded yet.";
    elements.livePod.textContent = adStatus.podLabel
        ? `${adStatus.podLabel} · ${formatLabel(adStatus.state, "Queued")}`
        : "Awaiting sponsor queue";
    elements.livePodCopy.textContent = adStatus.sponsorLabel
        ? `${adStatus.sponsorLabel}. ${adStatus.detail || "Sponsor metadata is loaded for the next break."}`
        : "Sponsor metadata will appear once the public broadcast status is available.";
    elements.liveBreak.textContent = formatRelativeBreak(adStatus.breakStartAt, adStatus.breakEndAt);
    elements.liveBreakCopy.textContent = adStatus.breakStartAt
        ? `${formatShortTime(adStatus.breakStartAt)} to ${formatShortTime(adStatus.breakEndAt)}`
        : "No sponsor break timing is available yet.";
    elements.storyHeadline.textContent = presenterGuide.storyHeadline;
    elements.storyCopy.textContent = presenterGuide.storyCopy;
    elements.pivotTitle.textContent = presenterGuide.pivotTitle;
    elements.pivotCopy.textContent = presenterGuide.pivotCopy;
    elements.revenueTitle.textContent = presenterGuide.revenueTitle;
    elements.revenueCopy.textContent = presenterGuide.revenueCopy;
    elements.scriptStep1.textContent = scriptWalkthrough.step1Title;
    elements.scriptStep1Copy.textContent = scriptWalkthrough.step1Copy;
    elements.scriptStep2.textContent = scriptWalkthrough.step2Title;
    elements.scriptStep2Copy.textContent = scriptWalkthrough.step2Copy;
    elements.scriptStep3.textContent = scriptWalkthrough.step3Title;
    elements.scriptStep3Copy.textContent = scriptWalkthrough.step3Copy;
    elements.launchTitle.textContent = launchTargets.title;
    elements.launchCopy.textContent = launchTargets.copy;
    elements.launchThousandEyes.href = absoluteUrl(launchTargets.thousandEyes.href);
    elements.launchApm.href = absoluteUrl(launchTargets.apm.href);
    elements.launchRum.href = absoluteUrl(launchTargets.rum.href);

    for (const [key, button] of [
        ["thousandEyes", elements.launchThousandEyes],
        ["apm", elements.launchApm],
        ["rum", elements.launchRum]
    ]) {
        button.classList.toggle("button-solid", launchTargets.primary === key);
        button.classList.toggle("button-ghost", launchTargets.primary !== key);
    }

    elements.enabled.checked = current.enabled;
    elements.nextBreakOnlyEnabled.checked = current.nextBreakOnlyEnabled;
    elements.latencyEnabled.checked = current.startupDelayMs > 0;
    elements.latencyValue.value = String(startupDelayValue);
    elements.throttleEnabled.checked = current.throttleKbps > 0;
    elements.throttleValue.value = String(throttleValue);
    elements.disconnectEnabled.checked = current.disconnectAfterKb > 0;
    elements.disconnectValue.value = String(disconnectValue);
    elements.packetLossEnabled.checked = current.packetLossPercent > 0;
    elements.packetLossValue.value = String(current.packetLossPercent || Number.parseInt(elements.packetLossValue.value, 10) || 20);
    elements.playbackFailureEnabled.checked = current.playbackFailureEnabled;
    elements.traceMapFailureEnabled.checked = current.traceMapFailureEnabled;
    elements.dependencyTimeoutEnabled.checked = current.dependencyTimeoutEnabled;
    elements.dependencyTimeoutService.value = current.dependencyTimeoutService || elements.dependencyTimeoutService.value || defaultDemoMonkeyDependencyService;
    elements.dependencyFailureEnabled.checked = current.dependencyFailureEnabled;
    elements.dependencyFailureService.value = current.dependencyFailureService || elements.dependencyFailureService.value || defaultDemoMonkeyDependencyService;
    elements.frontendExceptionEnabled.checked = current.frontendExceptionEnabled;
    elements.slowAdEnabled.checked = current.slowAdEnabled;
    elements.adLoadFailureEnabled.checked = current.adLoadFailureEnabled;
    applyWriteAccess();

    for (const button of elements.presets.querySelectorAll("[data-preset]")) {
        button.classList.toggle("is-active", button.dataset.preset === current.preset);
    }

    syncPresentationMode();
    maybeEmitFrontendException(current);
}

function buildRequest({ preset = "custom" } = {}) {
    const enabled = Boolean(elements.enabled.checked);
    return {
        enabled,
        preset,
        startupDelayMs: enabled && elements.latencyEnabled.checked ? Number.parseInt(elements.latencyValue.value, 10) || 0 : 0,
        throttleKbps: enabled && elements.throttleEnabled.checked ? Number.parseInt(elements.throttleValue.value, 10) || 0 : 0,
        disconnectAfterKb: enabled && elements.disconnectEnabled.checked ? Number.parseInt(elements.disconnectValue.value, 10) || 0 : 0,
        packetLossPercent: enabled && elements.packetLossEnabled.checked ? Number.parseInt(elements.packetLossValue.value, 10) || 0 : 0,
        playbackFailureEnabled: enabled && elements.playbackFailureEnabled.checked,
        traceMapFailureEnabled: enabled && elements.traceMapFailureEnabled.checked,
        dependencyTimeoutEnabled: enabled && elements.dependencyTimeoutEnabled.checked,
        dependencyTimeoutService: enabled && elements.dependencyTimeoutEnabled.checked
            ? elements.dependencyTimeoutService.value || defaultDemoMonkeyDependencyService
            : "",
        dependencyFailureEnabled: enabled && elements.dependencyFailureEnabled.checked,
        dependencyFailureService: enabled && elements.dependencyFailureEnabled.checked
            ? elements.dependencyFailureService.value || defaultDemoMonkeyDependencyService
            : "",
        frontendExceptionEnabled: enabled && elements.frontendExceptionEnabled.checked,
        slowAdEnabled: enabled && elements.slowAdEnabled.checked,
        adLoadFailureEnabled: enabled && elements.adLoadFailureEnabled.checked,
        nextBreakOnlyEnabled: enabled && elements.nextBreakOnlyEnabled.checked
    };
}

async function loadStatus(silent = false) {
    try {
        const response = await fetch(runtimeConfig.publicDemoMonkeyUrl ?? "/api/v1/demo/public/demo-monkey", {
            cache: "no-store"
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        state.current = normalize(await response.json());
        render();
    } catch (error) {
        console.warn("Unable to load incident simulation status.", error);
        if (!silent) {
            setMessage("Unable to load incident simulation state right now.", true);
        }
    }
}

async function loadBroadcastStatus(silent = false) {
    try {
        const response = await fetch(runtimeConfig.publicBroadcastStatusUrl ?? "/api/v1/demo/public/broadcast/current", {
            cache: "no-store"
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        state.broadcast = normalizeBroadcast(await response.json());
        render();
    } catch (error) {
        console.warn("Unable to load public broadcast status.", error);
        if (!silent) {
            setMessage("Unable to load the live sponsor-break timing right now.", true);
        }
    }
}

async function loadWriteAccess(silent = false) {
    try {
        const response = await fetch(runtimeConfig.authSessionUrl ?? "/api/v1/demo/auth/session", {
            cache: "no-store",
            credentials: "same-origin"
        });

        const payload = response.ok ? await response.json().catch(() => ({})) : null;
        state.canWrite = response.ok && hasRoleCapability(payload?.user?.role ?? payload?.role, "governance");
        render();

        if (!state.canWrite && !silent) {
            setMessage("Open master control with a governance-capable role to change incident simulation.", true);
        }
    } catch (error) {
        console.warn("Unable to resolve incident-simulation write access.", error);
        state.canWrite = false;
        render();
        if (!silent) {
            setMessage("Unable to verify governance access for incident simulation.", true);
        }
    }
}

async function updateStatus(request, successMessage) {
    if (!state.canWrite) {
        setMessage("Open master control with a governance-capable role to change incident simulation.", true);
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
            state.canWrite = false;
            render();
            setMessage("Open master control with a governance-capable role to change incident simulation.", true);
            return;
        }
        if (!response.ok) {
            throw new Error(payload.message ?? `HTTP ${response.status}`);
        }

        state.canWrite = true;
        state.current = normalize(payload);
        render();
        setMessage(successMessage, false);
    } catch (error) {
        console.warn("Unable to update incident simulation state.", error);
        setMessage(error.message || "Unable to update incident simulation state.", true);
    }
}

async function handlePresetClick(event) {
    if (!state.canWrite) {
        setMessage("Open master control with a governance-capable role to change incident simulation.", true);
        return;
    }

    const trigger = event.target.closest("[data-preset]");
    if (!trigger?.dataset.preset) {
        return;
    }

    const preset = demoMonkeyPresets[trigger.dataset.preset];
    if (!preset) {
        return;
    }

    setMessage(`${preset.label} preset selected.`, false);
    await updateStatus(
        preset,
        preset.enabled ? `${preset.label} is active across the incident surfaces.` : "Incident simulation bypassed."
    );
}

async function handleSubmit(event) {
    event.preventDefault();

    if (!state.canWrite) {
        setMessage("Open master control with a governance-capable role to change incident simulation.", true);
        return;
    }

    const request = buildRequest({ preset: "custom" });
    if (
        request.enabled
        && request.startupDelayMs <= 0
        && request.throttleKbps <= 0
        && request.disconnectAfterKb <= 0
        && request.packetLossPercent <= 0
        && !request.playbackFailureEnabled
        && !request.traceMapFailureEnabled
        && !request.dependencyTimeoutEnabled
        && !request.dependencyFailureEnabled
        && !request.frontendExceptionEnabled
        && !request.slowAdEnabled
        && !request.adLoadFailureEnabled
    ) {
        setMessage("Enable at least one network, playback, dependency, sponsor, trace, or browser fault or disable simulation.", true);
        return;
    }

    setMessage(request.enabled ? "Applying network, dependency, playback, sponsor, and browser incident conditions..." : "Disabling incident simulation...", false);
    await updateStatus(
        request,
        request.enabled ? "Incident simulation changes applied across the operating surfaces." : "Incident simulation bypassed."
    );
}

async function disableMonkey() {
    if (!state.canWrite) {
        setMessage("Open master control with a governance-capable role to change incident simulation.", true);
        return;
    }

    setMessage("Disabling incident simulation...", false);
    await updateStatus(demoMonkeyPresets.clear, "Incident simulation bypassed.");
}

async function bootstrap() {
    render();
    await loadStatus();
    await loadBroadcastStatus(true);
    await loadWriteAccess(true);
    window.setInterval(() => {
        loadStatus(true).catch((error) => {
            console.warn("Unable to refresh incident simulation state.", error);
        });
        loadBroadcastStatus(true).catch((error) => {
            console.warn("Unable to refresh public broadcast status.", error);
        });
        loadWriteAccess(true).catch((error) => {
            console.warn("Unable to refresh incident-simulation write access.", error);
        });
    }, 15000);
}

elements.form.addEventListener("submit", handleSubmit);
elements.disable.addEventListener("click", disableMonkey);
elements.presets.addEventListener("click", handlePresetClick);
elements.presentationToggle.addEventListener("click", () => {
    togglePresentationMode();
});
document.addEventListener("fullscreenchange", () => {
    if (!document.fullscreenElement) {
        presentationFallbackEnabled = false;
    }
    syncPresentationMode();
});

bootstrap().catch((error) => {
    console.warn("Incident simulation bootstrap failed.", error);
    setMessage("Unable to initialize the incident-simulation console.", true);
});
