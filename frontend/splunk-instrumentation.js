import SplunkOtelWeb, { SplunkZipkinExporter } from "@splunk/otel-web";
import SplunkSessionRecorder from "@splunk/otel-web-session-recorder";
import { BatchSpanProcessor } from "@opentelemetry/sdk-trace-base";
import {
    applySessionReplayFanout,
    buildRumBeaconUrl,
    buildSessionReplayDestination,
    resolveRumDestinations
} from "./splunk-rum-destinations.mjs";

const runtimeConfig = window.STREAMING_CONFIG ?? {};
const rumConfig = runtimeConfig.splunkRum ?? {};
const initFlag = "__STREAMING_SPLUNK_RUM_INITIALIZED__";
const sessionReplayFlag = "__STREAMING_SPLUNK_SESSION_REPLAY_INITIALIZED__";
const billingMaskSelector = "#billing, #billing *";
const defaultRumPrivacy = {
    maskAllText: false,
    sensitivityRules: [
        { rule: "mask", selector: billingMaskSelector }
    ]
};
const defaultSessionReplayConfig = {
    maskAllInputs: false,
    maskAllText: false,
    sensitivityRules: [
        { rule: "mask", selector: "input[type='password']" },
        { rule: "mask", selector: billingMaskSelector }
    ],
    features: {
        video: true
    }
};
const rumDestinations = resolveRumDestinations(rumConfig);

if (
    !window[initFlag] &&
    rumConfig.enabled !== false &&
    rumDestinations.length > 0
) {
    window[initFlag] = true;
    const [primaryDestination, ...additionalDestinations] = rumDestinations;

    try {
        SplunkOtelWeb.init({
            realm: primaryDestination.realm,
            rumAccessToken: primaryDestination.rumAccessToken,
            beaconEndpoint: primaryDestination.beaconEndpoint,
            applicationName: primaryDestination.applicationName,
            deploymentEnvironment: primaryDestination.deploymentEnvironment,
            version: primaryDestination.version,
            privacy: {
                ...defaultRumPrivacy,
                ...(rumConfig.privacy ?? {})
            },
            globalAttributes: {
                "app.surface": window.location.pathname,
                "k8s.namespace.name": runtimeConfig.namespace ?? "streaming-service-app"
            },
            spanProcessors: buildAdditionalSpanProcessors(additionalDestinations)
        });
    } catch (error) {
        console.warn("Unable to initialize Splunk RUM.", error);
    }

    if (!window[sessionReplayFlag] && rumConfig.sessionReplayEnabled !== false) {
        try {
            const sessionReplayConfig = rumConfig.sessionReplay ?? {};
            const primarySessionReplayDestination = buildSessionReplayDestination(primaryDestination);

            if (primarySessionReplayDestination) {
                applySessionReplayFanout(SplunkSessionRecorder, additionalDestinations);
                SplunkSessionRecorder.init({
                    realm: primarySessionReplayDestination.realm,
                    rumAccessToken: primarySessionReplayDestination.rumAccessToken,
                    beaconEndpoint: primarySessionReplayDestination.beaconEndpoint,
                    maskAllInputs: sessionReplayConfig.maskAllInputs ?? defaultSessionReplayConfig.maskAllInputs,
                    maskAllText: sessionReplayConfig.maskAllText ?? defaultSessionReplayConfig.maskAllText,
                    sensitivityRules: sessionReplayConfig.sensitivityRules ?? defaultSessionReplayConfig.sensitivityRules,
                    features: {
                        ...defaultSessionReplayConfig.features,
                        ...(sessionReplayConfig.features ?? {})
                    }
                });
                window[sessionReplayFlag] = true;
            }
        } catch (error) {
            console.warn("Unable to initialize Splunk session replay.", error);
        }
    }
}

function buildAdditionalSpanProcessors(destinations) {
    return destinations
        .map((destination) => {
            const beaconUrl = buildRumBeaconUrl(destination);
            if (!beaconUrl) {
                return null;
            }

            return new BatchSpanProcessor(
                new SplunkZipkinExporter({ url: beaconUrl }),
                {
                    maxExportBatchSize: 50,
                    scheduledDelayMillis: 4000
                }
            );
        })
        .filter(Boolean);
}
