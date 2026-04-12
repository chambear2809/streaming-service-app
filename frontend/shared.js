export const roleCapabilities = {
    billing_admin: ["operations", "governance", "billing", "billing_write"],
    finance_analyst: ["operations", "billing", "billing_write"],
    platform_admin: ["operations", "governance", "ingest", "billing", "billing_write"],
    programming_manager: ["operations", "governance", "ingest"],
    executive: ["operations", "governance", "billing"],
    qa_reviewer: ["operations"],
    staff_operator: ["operations"]
};

export const demoMonkeyPresets = {
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

export const demoMonkeyDependencyOptions = [
    { value: "user-service-demo", label: "User service" },
    { value: "content-service-demo", label: "Content service" },
    { value: "billing-service", label: "Billing service" },
    { value: "ad-service-demo", label: "Ad service" }
];

export const defaultDemoMonkeyDependencyService = "ad-service-demo";

export function absoluteUrl(path) {
    try {
        return new URL(path || "/", window.location.origin).href;
    } catch (_error) {
        return path || "/";
    }
}

export function hasRoleCapability(role, capability) {
    const normalizedRole = String(role ?? "").trim().toLowerCase();
    const permissions = roleCapabilities[normalizedRole] ?? roleCapabilities.staff_operator;
    return permissions.includes(capability);
}
