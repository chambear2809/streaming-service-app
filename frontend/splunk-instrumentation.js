import SplunkOtelWeb from "@splunk/otel-web";
import SplunkSessionRecorder from "@splunk/otel-web-session-recorder";

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

if (
    !window[initFlag] &&
    rumConfig.enabled !== false &&
    rumConfig.realm &&
    rumConfig.rumAccessToken &&
    rumConfig.applicationName
) {
    window[initFlag] = true;

    try {
        SplunkOtelWeb.init({
            realm: rumConfig.realm,
            rumAccessToken: rumConfig.rumAccessToken,
            applicationName: rumConfig.applicationName,
            deploymentEnvironment: rumConfig.deploymentEnvironment ?? runtimeConfig.environment ?? "streaming-app",
            version: rumConfig.version ?? runtimeConfig.buildVersion,
            privacy: {
                ...defaultRumPrivacy,
                ...(rumConfig.privacy ?? {})
            },
            globalAttributes: {
                "app.surface": window.location.pathname,
                "k8s.namespace.name": runtimeConfig.namespace ?? "streaming-service-app"
            }
        });
    } catch (error) {
        console.warn("Unable to initialize Splunk RUM.", error);
    }

    if (!window[sessionReplayFlag] && rumConfig.sessionReplayEnabled !== false) {
        try {
            const sessionReplayConfig = rumConfig.sessionReplay ?? {};

            SplunkSessionRecorder.init({
                realm: rumConfig.realm,
                rumAccessToken: rumConfig.rumAccessToken,
                maskAllInputs: sessionReplayConfig.maskAllInputs ?? defaultSessionReplayConfig.maskAllInputs,
                maskAllText: sessionReplayConfig.maskAllText ?? defaultSessionReplayConfig.maskAllText,
                sensitivityRules: sessionReplayConfig.sensitivityRules ?? defaultSessionReplayConfig.sensitivityRules,
                features: {
                    ...defaultSessionReplayConfig.features,
                    ...(sessionReplayConfig.features ?? {})
                }
            });
            window[sessionReplayFlag] = true;
        } catch (error) {
            console.warn("Unable to initialize Splunk session replay.", error);
        }
    }
}
