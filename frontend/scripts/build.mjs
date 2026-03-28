import { execFileSync } from "node:child_process";
import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { build } from "esbuild";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(scriptDir, "..");
const repoRoot = path.resolve(frontendDir, "..");
const distDir = path.join(frontendDir, "dist");
const buildVersion = process.env.APP_VERSION || resolveGitVersion(repoRoot) || "dev";
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
    return Object.prototype.hasOwnProperty.call(process.env, name)
        ? process.env[name]
        : undefined;
}
