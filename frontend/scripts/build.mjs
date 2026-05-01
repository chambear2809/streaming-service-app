import { execFileSync } from "node:child_process";
import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { build } from "esbuild";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(scriptDir, "..");
const repoRoot = path.resolve(frontendDir, "..");
const distDir = path.join(frontendDir, "dist");
const buildEnv = {
    ...(await loadDotEnv(path.join(repoRoot, ".env"))),
    ...process.env
};
const buildVersion = buildEnv.APP_VERSION || resolveGitVersion(repoRoot) || "dev";
const runtimeOverrides = Object.fromEntries(
    Object.entries({
        clusterLabel: readEnvOverride("STREAMING_CLUSTER_LABEL"),
        namespace: readEnvOverride("STREAMING_NAMESPACE"),
        environment: readEnvOverride("STREAMING_ENVIRONMENT_LABEL"),
        regionLabel: readEnvOverride("STREAMING_REGION_LABEL"),
        controlRoomLabel: readEnvOverride("STREAMING_CONTROL_ROOM_LABEL"),
        publicBroadcastRtspUrl: readEnvOverride("STREAMING_PUBLIC_RTSP_URL")
    }).filter(([, value]) => value !== undefined)
);
const splunkRumOverrides = Object.fromEntries(
    Object.entries({
        realm: readEnvOverride("SPLUNK_REALM"),
        rumAccessToken: readEnvOverride("SPLUNK_RUM_ACCESS_TOKEN"),
        applicationName: readEnvOverride("SPLUNK_RUM_APP_NAME"),
        deploymentEnvironment: readEnvOverride("SPLUNK_DEPLOYMENT_ENVIRONMENT"),
        beaconEndpoint: readEnvOverride("SPLUNK_RUM_BEACON_ENDPOINT"),
        sessionReplayBeaconEndpoint: readEnvOverride("SPLUNK_RUM_SESSION_REPLAY_BEACON_ENDPOINT")
    }).filter(([, value]) => value !== undefined)
);
const secondarySplunkRumDestination = buildSecondarySplunkRumDestination();
if (secondarySplunkRumDestination) {
    splunkRumOverrides.additionalDestinations = [secondarySplunkRumDestination];
}

await rm(distDir, { force: true, recursive: true });
await mkdir(distDir, { recursive: true });

for (const asset of [
    "index.html",
    "broadcast.html",
    "demo-monkey.html",
    "styles.css",
    "server.js"
]) {
    await cp(path.join(frontendDir, asset), path.join(distDir, asset));
}

const configSource = await readFile(path.join(frontendDir, "config.js"), "utf8");
await writeFile(
    path.join(distDir, "config.js"),
    `${configSource}
Object.assign(window.STREAMING_CONFIG, ${JSON.stringify(runtimeOverrides, null, 4)});
window.STREAMING_CONFIG.buildVersion = ${JSON.stringify(buildVersion)};
window.STREAMING_CONFIG.splunkRum = {
    ...(window.STREAMING_CONFIG.splunkRum ?? {}),
    ...${JSON.stringify(splunkRumOverrides, null, 4)},
    version: ${JSON.stringify(buildVersion)}
};
`
);

await build({
    absWorkingDir: frontendDir,
    bundle: true,
    entryPoints: [
        "app.js",
        "broadcast.js",
        "demo-monkey.js",
        "splunk-instrumentation.js"
    ],
    format: "esm",
    minify: true,
    outdir: distDir,
    platform: "browser",
    sourcemap: true,
    target: ["es2020"]
});

await writeFile(
    path.join(distDir, "build-info.json"),
    JSON.stringify(
        {
            appName: "streaming-app-frontend",
            appVersion: buildVersion
        },
        null,
        2
    )
);

console.log(`Built frontend assets in ${distDir} with app version ${buildVersion}.`);

function resolveGitVersion(cwd) {
    try {
        return execFileSync("git", ["-C", cwd, "rev-parse", "--short", "HEAD"], {
            encoding: "utf8"
        }).trim();
    } catch {
        return "";
    }
}

function readEnvOverride(name) {
    return Object.prototype.hasOwnProperty.call(buildEnv, name)
        ? buildEnv[name]
        : undefined;
}

function readFirstEnvOverride(...names) {
    for (const name of names) {
        const value = readEnvOverride(name);
        if (value !== undefined && value !== "") {
            return value;
        }
    }

    return undefined;
}

function readEnvBoolean(name, defaultValue = true) {
    const rawValue = readEnvOverride(name);
    if (rawValue === undefined || rawValue === "") {
        return defaultValue;
    }

    const normalizedValue = String(rawValue).trim().toLowerCase();
    if (["1", "true", "yes", "on"].includes(normalizedValue)) {
        return true;
    }
    if (["0", "false", "no", "off"].includes(normalizedValue)) {
        return false;
    }

    throw new Error(`Invalid boolean value for ${name}: ${rawValue}`);
}

function buildSecondarySplunkRumDestination() {
    if (!readEnvBoolean("SPLUNK_RUM_SECONDARY_ENABLED", true)) {
        return undefined;
    }

    const rumAccessToken = readFirstEnvOverride("SPLUNK_RUM_SECONDARY_ACCESS_TOKEN");
    if (!rumAccessToken) {
        return undefined;
    }

    const destination = Object.fromEntries(
        Object.entries({
            name: readFirstEnvOverride("SPLUNK_RUM_SECONDARY_NAME") ?? "secondary",
            realm: readFirstEnvOverride("SPLUNK_RUM_SECONDARY_REALM", "SPLUNK_OTEL_SECONDARY_REALM"),
            rumAccessToken,
            beaconEndpoint: readFirstEnvOverride("SPLUNK_RUM_SECONDARY_BEACON_ENDPOINT"),
            sessionReplayBeaconEndpoint: readFirstEnvOverride("SPLUNK_RUM_SECONDARY_SESSION_REPLAY_BEACON_ENDPOINT")
        }).filter(([, value]) => value !== undefined)
    );

    if (!destination.realm && !destination.beaconEndpoint && !destination.sessionReplayBeaconEndpoint) {
        return undefined;
    }

    return destination;
}

async function loadDotEnv(filePath) {
    try {
        return parseDotEnv(await readFile(filePath, "utf8"));
    } catch (error) {
        if (error && error.code === "ENOENT") {
            return {};
        }
        throw error;
    }
}

function parseDotEnv(source) {
    const env = {};

    for (const rawLine of source.split(/\r?\n/u)) {
        const line = rawLine.trim();

        if (!line || line.startsWith("#")) {
            continue;
        }

        const normalizedLine = line.startsWith("export ")
            ? line.slice("export ".length).trim()
            : line;
        const separatorIndex = normalizedLine.indexOf("=");

        if (separatorIndex < 1) {
            continue;
        }

        const key = normalizedLine.slice(0, separatorIndex).trim();
        let value = normalizedLine.slice(separatorIndex + 1).trim();

        if (
            (value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))
        ) {
            value = value.slice(1, -1);
        }

        env[key] = value;
    }

    return env;
}
