import SplunkOtelWeb from "@splunk/otel-web";
import SplunkSessionRecorder from "@splunk/otel-web-session-recorder";

const runtimeConfig = window.STREAMING_CONFIG ?? {};
const rumConfig = runtimeConfig.splunkRum ?? {};
const initFlag = "__STREAMING_SPLUNK_RUM_INITIALIZED__";
const sessionReplayFlag = "__STREAMING_SPLUNK_SESSION_REPLAY_INITIALIZED__";

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
            SplunkSessionRecorder.init({
                realm: rumConfig.realm,
                rumAccessToken: rumConfig.rumAccessToken
            });
            window[sessionReplayFlag] = true;
        } catch (error) {
            console.warn("Unable to initialize Splunk session replay.", error);
        }
    }
}
