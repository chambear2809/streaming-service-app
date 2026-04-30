const defaultSessionReplayFanoutFlag = "__STREAMING_SPLUNK_SESSION_REPLAY_FANOUT__";

export function resolveRumDestinations(config, fallbackRuntimeConfig = {}) {
    const primaryDestination = normalizeRumDestination({
        name: "primary",
        enabled: config.enabled,
        realm: config.realm,
        rumAccessToken: config.rumAccessToken,
        beaconEndpoint: config.beaconEndpoint,
        sessionReplayBeaconEndpoint: config.sessionReplayBeaconEndpoint,
        applicationName: config.applicationName,
        deploymentEnvironment: config.deploymentEnvironment,
        version: config.version
    }, fallbackRuntimeConfig);
    const additionalConfig = Array.isArray(config.additionalDestinations)
        ? config.additionalDestinations
        : [];
    const legacyDestination = config.secondary ? [config.secondary] : [];
    const additionalDestinations = [...additionalConfig, ...legacyDestination]
        .map((destination, index) => normalizeRumDestination({
            applicationName: config.applicationName,
            deploymentEnvironment: config.deploymentEnvironment,
            version: config.version,
            name: `destination-${index + 1}`,
            ...destination
        }, fallbackRuntimeConfig))
        .filter(Boolean);

    return [primaryDestination, ...additionalDestinations].filter(Boolean);
}

export function normalizeRumDestination(destination, fallbackRuntimeConfig = {}) {
    if (destination.enabled === false) {
        return null;
    }

    const normalizedDestination = {
        name: normalizeString(destination.name),
        realm: normalizeString(destination.realm),
        rumAccessToken: normalizeString(destination.rumAccessToken),
        beaconEndpoint: normalizeString(destination.beaconEndpoint),
        sessionReplayBeaconEndpoint: normalizeString(destination.sessionReplayBeaconEndpoint),
        applicationName: normalizeString(destination.applicationName) ?? "streaming-app-frontend",
        deploymentEnvironment: normalizeString(destination.deploymentEnvironment)
            ?? fallbackRuntimeConfig.environment
            ?? "streaming-app",
        version: normalizeString(destination.version) ?? fallbackRuntimeConfig.buildVersion
    };

    if (
        !normalizedDestination.rumAccessToken ||
        (!normalizedDestination.realm && !normalizedDestination.beaconEndpoint)
    ) {
        return null;
    }

    return normalizedDestination;
}

export function buildRumBeaconUrl(destination) {
    const beaconEndpoint = destination.beaconEndpoint
        ?? (destination.realm ? `https://rum-ingest.${destination.realm}.signalfx.com/v1/rum` : undefined);

    return appendAuthQuery(beaconEndpoint, destination.rumAccessToken);
}

export function buildSessionReplayDestination(destination) {
    const beaconEndpoint = buildSessionReplayEndpoint(destination);
    if (!beaconEndpoint && !destination.realm) {
        return null;
    }

    return {
        realm: destination.realm,
        rumAccessToken: destination.rumAccessToken,
        beaconEndpoint
    };
}

export function buildSessionReplayBeaconUrl(destination) {
    return appendAuthQuery(buildSessionReplayEndpoint(destination), destination.rumAccessToken);
}

export function buildSessionReplayEndpoint(destination) {
    return destination.sessionReplayBeaconEndpoint
        ?? deriveSessionReplayEndpoint(destination.beaconEndpoint)
        ?? (destination.realm ? `https://rum-ingest.${destination.realm}.signalfx.com/v1/rumreplay` : undefined);
}

export function deriveSessionReplayEndpoint(beaconEndpoint) {
    if (!beaconEndpoint) {
        return undefined;
    }

    return beaconEndpoint.replace(/\/v1\/rum(?=($|[?#]))/u, "/v1/rumreplay");
}

export function appendAuthQuery(endpoint, rumAccessToken) {
    if (!endpoint || !rumAccessToken || /[?&]auth=/u.test(endpoint)) {
        return endpoint;
    }

    const separator = endpoint.includes("?") ? "&" : "?";
    return `${endpoint}${separator}auth=${encodeURIComponent(rumAccessToken)}`;
}

export function applySessionReplayFanout(
    recorder,
    destinations,
    fanoutFlag = defaultSessionReplayFanoutFlag
) {
    const replayUrls = destinations
        .map(buildSessionReplayBeaconUrl)
        .filter(Boolean);

    if (
        replayUrls.length === 0 ||
        typeof recorder._getProcessorForSession !== "function" ||
        recorder[fanoutFlag]
    ) {
        return;
    }

    const originalGetProcessorForSession =
        recorder._getProcessorForSession.bind(recorder);

    recorder._getProcessorForSession = (args) => {
        const processors = [
            originalGetProcessorForSession(args),
            ...replayUrls.map((exportUrl) => originalGetProcessorForSession({
                ...args,
                exportQueuedLogs: false,
                exportUrl,
                persistFailedReplayData: false
            }))
        ];

        return {
            forceFlush() {
                return Promise.allSettled(
                    processors.map((processor) => processor.forceFlush?.() ?? Promise.resolve())
                ).then(() => undefined);
            },
            onEmit(log) {
                for (const processor of processors) {
                    processor.onEmit(log);
                }
            }
        };
    };

    recorder[fanoutFlag] = true;
}

function normalizeString(value) {
    if (typeof value !== "string") {
        return undefined;
    }

    const trimmedValue = value.trim();
    return trimmedValue === "" ? undefined : trimmedValue;
}
