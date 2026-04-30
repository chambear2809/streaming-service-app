#!/usr/bin/env node

import { createRequire } from "node:module";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

const require = createRequire(import.meta.url);

const HELP_TEXT = `Browser RUM load generator

Usage:
  node scripts/loadgen/browser-rum-loadgen.mjs [options]

The script launches real Playwright browser contexts so the frontend JavaScript
and Splunk Browser RUM instrumentation execute in a browser runtime. CLI flags
override LOADGEN_BROWSER_* environment variables.

Options:
  --base-url <url>                 Frontend base URL.
  --paths <csv>                    Comma-separated page paths. Default: /broadcast
  --target-browsers <count>        Peak concurrent browser contexts. Default: 6
  --duration <time>                Total scenario duration. Default: 10m
  --ramp-up <time>                 Ramp-up duration. Default: 1m
  --ramp-down <time>               Ramp-down duration. Default: 1m
  --session-min <time>             Minimum browser session length. Default: 45s
  --session-max <time>             Maximum browser session length. Default: 2m
  --think-time-min <time>          Minimum delay between browser actions. Default: 2s
  --think-time-max <time>          Maximum delay between browser actions. Default: 6s
  --trace-map-ratio <ratio>        Fraction of action loops that fetch trace-map. Default: 0.20
  --navigation-ratio <ratio>       Fraction of action loops that navigate to another page. Default: 0.08
  --auth-persona <persona>         Optional demo persona to sign in before navigation.
  --browser <name>                 chromium | firefox | webkit. Default: chromium
  --headless <true|false>          Launch browsers headless. Default: true
  --viewport <WxH>                 Fixed viewport. Default: random desktop/mobile mix
  --navigation-timeout <time>      Per-navigation timeout. Default: 30s
  --action-timeout <time>          Per-action timeout. Default: 10s
  --rum-flush-wait <time>          Wait before closing a session. Default: 8s
  --egress-check-url <url>         Public IP check URL. Default: https://checkip.amazonaws.com
  --log-every <time>               Progress log interval. Default: 5s
  --dry-run                        Validate config and print scenario without launching Playwright.
  --help                           Show this help text.

Environment variable equivalents:
  LOADGEN_BROWSER_BASE_URL or LOADGEN_BASE_URL
  LOADGEN_BROWSER_PATHS
  LOADGEN_BROWSER_TARGET_BROWSERS
  LOADGEN_BROWSER_DURATION
  LOADGEN_BROWSER_RAMP_UP
  LOADGEN_BROWSER_RAMP_DOWN
  LOADGEN_BROWSER_SESSION_MIN
  LOADGEN_BROWSER_SESSION_MAX
  LOADGEN_BROWSER_THINK_TIME_MIN
  LOADGEN_BROWSER_THINK_TIME_MAX
  LOADGEN_BROWSER_TRACE_MAP_RATIO
  LOADGEN_BROWSER_NAVIGATION_RATIO
  LOADGEN_BROWSER_AUTH_PERSONA
  LOADGEN_BROWSER_BROWSER
  LOADGEN_BROWSER_HEADLESS
  LOADGEN_BROWSER_VIEWPORT
  LOADGEN_BROWSER_NAVIGATION_TIMEOUT
  LOADGEN_BROWSER_ACTION_TIMEOUT
  LOADGEN_BROWSER_RUM_FLUSH_WAIT
  LOADGEN_BROWSER_EGRESS_CHECK_URL
  LOADGEN_BROWSER_LOG_EVERY
`;

const RUM_REQUEST_PATTERNS = [
    /\/v1\/rum(?:$|[/?#])/u,
    /\/v1\/rumreplay(?:$|[/?#])/u,
    /\/v1\/rumotlp(?:$|[/?#])/u,
    /rum-ingest\./u,
    /external-ingest\./u
];

async function main() {
    const config = parseConfig(process.argv.slice(2), process.env);
    if (config.helpRequested) {
        console.log(HELP_TEXT);
        return;
    }

    validateConfig(config);
    printScenario(config);

    if (config.dryRun) {
        console.log("Dry run complete. Playwright was not launched.");
        return;
    }

    const metrics = createMetrics();
    metrics.egressIp = await resolveEgressIp(config);

    if (metrics.egressIp) {
        console.log(`Egress IP: ${metrics.egressIp}`);
    }

    const playwright = loadPlaywright();
    const browserType = playwright[config.browserName];
    if (!browserType) {
        throw new Error(`Playwright browser is unavailable: ${config.browserName}`);
    }

    const browser = await browserType.launch({
        headless: config.headless,
        args: config.browserName === "chromium"
            ? ["--no-sandbox", "--disable-dev-shm-usage"]
            : []
    });

    try {
        const runner = new ScenarioRunner(browser, config, metrics);
        await runner.run();
    } finally {
        await browser.close();
    }

    printSummary(metrics);
}

class ScenarioRunner {
    constructor(browser, config, metrics) {
        this.browser = browser;
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
                const target = computeTargetBrowserCount(this.config, startedAt, endsAt, Date.now());
                while (this.activeSessions.size < target) {
                    this.launchSession(endsAt);
                }
                await sleep(250);
            }

            const drainDeadline = Date.now() + Math.min(60_000, this.config.sessionMaxMs + this.config.rumFlushWaitMs);
            while (this.activeSessions.size > 0 && Date.now() < drainDeadline) {
                await sleep(250);
            }
        } finally {
            clearInterval(logTimer);
        }
    }

    launchSession(scenarioEndsAt) {
        const sessionId = this.nextSessionId++;
        const promise = runBrowserSession(sessionId, this.browser, this.config, this.metrics, scenarioEndsAt)
            .finally(() => {
                this.activeSessions.delete(sessionId);
            });
        this.activeSessions.set(sessionId, promise);
    }
}

async function runBrowserSession(sessionId, browser, config, metrics, scenarioEndsAt) {
    const startedAt = Date.now();
    const sessionDurationMs = randomBetween(config.sessionMinMs, config.sessionMaxMs);
    const endsAt = Math.min(startedAt + sessionDurationMs, scenarioEndsAt);
    const viewport = chooseViewport(config);
    const context = await browser.newContext({
        viewport,
        userAgent: `streaming-service-app-browser-rum-loadgen/1.0 session/${sessionId}`
    });
    context.setDefaultTimeout(config.actionTimeoutMs);
    context.setDefaultNavigationTimeout(config.navigationTimeoutMs);

    const page = await context.newPage();
    attachPageInstrumentation(page, metrics);
    metrics.sessionsStarted += 1;

    try {
        if (config.authPersona) {
            await signInPersona(context, config);
        }

        const path = choose(config.paths);
        const url = resolveUrl(config.baseUrl, path);
        await page.goto(url, { waitUntil: "domcontentloaded" });
        metrics.pageViews += 1;
        await waitForRumInitialization(page, config);

        while (Date.now() < endsAt) {
            await runActionLoop(page, config, metrics);
            await sleep(randomBetween(config.thinkTimeMinMs, config.thinkTimeMaxMs));
        }

        await sleep(config.rumFlushWaitMs);
        metrics.sessionsCompleted += 1;
    } catch (error) {
        metrics.sessionsFailed += 1;
        metrics.failures.push({
            sessionId,
            message: error.message
        });
        console.warn(`[session ${sessionId}] ${error.message}`);
    } finally {
        await context.close();
    }
}

async function signInPersona(context, config) {
    const response = await context.request.post(
        resolveUrl(config.baseUrl, `/api/v1/demo/auth/persona/${encodeURIComponent(config.authPersona)}`),
        {
            timeout: config.actionTimeoutMs
        }
    );
    if (!response.ok()) {
        throw new Error(`persona sign-in failed with HTTP ${response.status()}`);
    }
}

async function waitForRumInitialization(page, config) {
    try {
        await page.waitForFunction(
            () => window.__STREAMING_SPLUNK_RUM_INITIALIZED__ === true,
            undefined,
            { timeout: Math.min(config.actionTimeoutMs, 5000) }
        );
    } catch {
        // RUM can be intentionally disabled when no token is configured. The loadgen still
        // exercises the browser path so the missing beacon count is obvious in the summary.
    }
}

async function runActionLoop(page, config, metrics) {
    if (Math.random() < config.navigationRatio) {
        await page.goto(resolveUrl(config.baseUrl, choose(config.paths)), { waitUntil: "domcontentloaded" });
        metrics.pageViews += 1;
        await waitForRumInitialization(page, config);
    }

    const viewport = page.viewportSize() ?? { width: 1280, height: 720 };
    const x = Math.floor(randomBetween(80, Math.max(81, viewport.width - 80)));
    const y = Math.floor(randomBetween(80, Math.max(81, viewport.height - 80)));

    await page.mouse.move(x, y, { steps: 8 });
    await page.mouse.click(x, y, { delay: Math.floor(randomBetween(20, 80)) });
    metrics.interactions += 1;

    await page.evaluate(async () => {
        await fetch("/api/v1/demo/public/broadcast/current", {
            cache: "no-store",
            credentials: "same-origin"
        }).catch(() => undefined);
    });
    metrics.statusFetches += 1;

    if (Math.random() < config.traceMapRatio) {
        await page.evaluate(async () => {
            await fetch("/api/v1/demo/public/trace-map", {
                cache: "no-store",
                credentials: "same-origin"
            }).catch(() => undefined);
        });
        metrics.traceMapFetches += 1;
    }

    if (Math.random() < 0.25) {
        await page.mouse.wheel(0, Math.floor(randomBetween(-500, 700)));
    }
}

function attachPageInstrumentation(page, metrics) {
    page.on("request", (request) => {
        if (isRumRequest(request.url())) {
            metrics.rumRequests += 1;
        }
    });
    page.on("response", (response) => {
        const url = response.url();
        if (isRumRequest(url)) {
            metrics.rumResponses += 1;
            const status = String(response.status());
            metrics.rumResponseStatuses[status] = (metrics.rumResponseStatuses[status] ?? 0) + 1;
        }
    });
    page.on("requestfailed", (request) => {
        if (isRumRequest(request.url())) {
            metrics.rumFailures += 1;
        }
        metrics.requestFailures += 1;
    });
    page.on("pageerror", (error) => {
        metrics.pageErrors += 1;
        if (metrics.pageErrorSamples.length < 5) {
            metrics.pageErrorSamples.push(error.message);
        }
    });
    page.on("console", (message) => {
        if (message.type() === "error") {
            metrics.consoleErrors += 1;
            if (metrics.consoleErrorSamples.length < 5) {
                metrics.consoleErrorSamples.push(message.text());
            }
        }
    });
}

function createMetrics() {
    return {
        egressIp: "",
        sessionsStarted: 0,
        sessionsCompleted: 0,
        sessionsFailed: 0,
        pageViews: 0,
        interactions: 0,
        statusFetches: 0,
        traceMapFetches: 0,
        rumRequests: 0,
        rumResponses: 0,
        rumFailures: 0,
        rumResponseStatuses: {},
        requestFailures: 0,
        pageErrors: 0,
        consoleErrors: 0,
        pageErrorSamples: [],
        consoleErrorSamples: [],
        failures: []
    };
}

async function resolveEgressIp(config) {
    if (!config.egressCheckUrl) {
        return "";
    }

    try {
        const response = await fetch(config.egressCheckUrl, {
            signal: AbortSignal.timeout(Math.min(config.actionTimeoutMs, 5000))
        });
        if (!response.ok) {
            return "";
        }
        return (await response.text()).trim();
    } catch {
        return "";
    }
}

function loadPlaywright() {
    try {
        return require("playwright");
    } catch (error) {
        throw new Error(
            "Playwright is required for browser RUM loadgen. Run `npm install --prefix scripts/loadgen playwright@1.56.1`, or use the Kubernetes wrapper which installs the matching package in the Playwright container. "
            + error.message
        );
    }
}

function printScenario(config) {
    console.log("Browser RUM loadgen scenario:");
    console.log(`  Base URL: ${config.baseUrl}`);
    console.log(`  Paths: ${config.paths.join(", ")}`);
    console.log(`  Browser: ${config.browserName}`);
    console.log(`  Headless: ${config.headless}`);
    console.log(`  Target browser contexts: ${config.targetBrowsers}`);
    console.log(`  Duration: ${formatDuration(config.durationMs)}`);
    console.log(`  Ramp up/down: ${formatDuration(config.rampUpMs)} / ${formatDuration(config.rampDownMs)}`);
    console.log(`  Session min/max: ${formatDuration(config.sessionMinMs)} / ${formatDuration(config.sessionMaxMs)}`);
    console.log(`  Trace-map action ratio: ${config.traceMapRatio}`);
    console.log(`  Navigation action ratio: ${config.navigationRatio}`);
    if (config.authPersona) {
        console.log(`  Auth persona: ${config.authPersona}`);
    }
}

function printProgress(config, metrics, startedAt, endsAt, activeSessions) {
    const elapsedMs = Date.now() - startedAt;
    const remainingMs = Math.max(0, endsAt - Date.now());
    console.log(
        `[browser-rum-loadgen] elapsed=${formatDuration(elapsedMs)} remaining=${formatDuration(remainingMs)} `
        + `active=${activeSessions}/${config.targetBrowsers} sessions=${metrics.sessionsStarted} `
        + `completed=${metrics.sessionsCompleted} failed=${metrics.sessionsFailed} rumRequests=${metrics.rumRequests}`
    );
}

function printSummary(metrics) {
    console.log(JSON.stringify(metrics, null, 2));
}

function isRumRequest(url) {
    return RUM_REQUEST_PATTERNS.some((pattern) => pattern.test(url));
}

function chooseViewport(config) {
    if (config.viewport) {
        return config.viewport;
    }

    return choose([
        { width: 1440, height: 900 },
        { width: 1366, height: 768 },
        { width: 1280, height: 720 },
        { width: 390, height: 844 },
        { width: 430, height: 932 }
    ]);
}

function computeTargetBrowserCount(config, startedAt, endsAt, now) {
    if (now < startedAt + config.rampUpMs) {
        const progress = config.rampUpMs === 0 ? 1 : (now - startedAt) / config.rampUpMs;
        return Math.max(1, Math.ceil(config.targetBrowsers * progress));
    }

    if (now > endsAt - config.rampDownMs) {
        const remaining = endsAt - now;
        const progress = config.rampDownMs === 0 ? 0 : remaining / config.rampDownMs;
        return Math.max(0, Math.ceil(config.targetBrowsers * progress));
    }

    return config.targetBrowsers;
}

function parseConfig(argv, env) {
    const options = {
        helpRequested: false,
        dryRun: false,
        baseUrl: env.LOADGEN_BROWSER_BASE_URL ?? env.LOADGEN_BASE_URL ?? "http://127.0.0.1:8080",
        paths: parseCsv(env.LOADGEN_BROWSER_PATHS ?? "/broadcast"),
        targetBrowsers: parseInteger(env.LOADGEN_BROWSER_TARGET_BROWSERS ?? "6", "LOADGEN_BROWSER_TARGET_BROWSERS"),
        durationMs: parseDuration(env.LOADGEN_BROWSER_DURATION ?? "10m", "LOADGEN_BROWSER_DURATION"),
        rampUpMs: parseDuration(env.LOADGEN_BROWSER_RAMP_UP ?? "1m", "LOADGEN_BROWSER_RAMP_UP"),
        rampDownMs: parseDuration(env.LOADGEN_BROWSER_RAMP_DOWN ?? "1m", "LOADGEN_BROWSER_RAMP_DOWN"),
        sessionMinMs: parseDuration(env.LOADGEN_BROWSER_SESSION_MIN ?? "45s", "LOADGEN_BROWSER_SESSION_MIN"),
        sessionMaxMs: parseDuration(env.LOADGEN_BROWSER_SESSION_MAX ?? "2m", "LOADGEN_BROWSER_SESSION_MAX"),
        thinkTimeMinMs: parseDuration(env.LOADGEN_BROWSER_THINK_TIME_MIN ?? "2s", "LOADGEN_BROWSER_THINK_TIME_MIN"),
        thinkTimeMaxMs: parseDuration(env.LOADGEN_BROWSER_THINK_TIME_MAX ?? "6s", "LOADGEN_BROWSER_THINK_TIME_MAX"),
        traceMapRatio: parseRatio(env.LOADGEN_BROWSER_TRACE_MAP_RATIO ?? "0.20", "LOADGEN_BROWSER_TRACE_MAP_RATIO"),
        navigationRatio: parseRatio(env.LOADGEN_BROWSER_NAVIGATION_RATIO ?? "0.08", "LOADGEN_BROWSER_NAVIGATION_RATIO"),
        authPersona: normalizeString(env.LOADGEN_BROWSER_AUTH_PERSONA),
        browserName: normalizeString(env.LOADGEN_BROWSER_BROWSER) ?? "chromium",
        headless: parseBoolean(env.LOADGEN_BROWSER_HEADLESS ?? "true", "LOADGEN_BROWSER_HEADLESS"),
        viewport: parseViewport(env.LOADGEN_BROWSER_VIEWPORT ?? ""),
        navigationTimeoutMs: parseDuration(env.LOADGEN_BROWSER_NAVIGATION_TIMEOUT ?? "30s", "LOADGEN_BROWSER_NAVIGATION_TIMEOUT"),
        actionTimeoutMs: parseDuration(env.LOADGEN_BROWSER_ACTION_TIMEOUT ?? "10s", "LOADGEN_BROWSER_ACTION_TIMEOUT"),
        rumFlushWaitMs: parseDuration(env.LOADGEN_BROWSER_RUM_FLUSH_WAIT ?? "8s", "LOADGEN_BROWSER_RUM_FLUSH_WAIT"),
        egressCheckUrl: normalizeString(env.LOADGEN_BROWSER_EGRESS_CHECK_URL) ?? "https://checkip.amazonaws.com",
        logEveryMs: parseDuration(env.LOADGEN_BROWSER_LOG_EVERY ?? "5s", "LOADGEN_BROWSER_LOG_EVERY")
    };

    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (argument === "--help" || argument === "-h") {
            options.helpRequested = true;
            continue;
        }
        if (argument === "--dry-run") {
            options.dryRun = true;
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
            case "--paths":
                options.paths = parseCsv(nextValue);
                break;
            case "--target-browsers":
                options.targetBrowsers = parseInteger(nextValue, argument);
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
            case "--think-time-min":
                options.thinkTimeMinMs = parseDuration(nextValue, argument);
                break;
            case "--think-time-max":
                options.thinkTimeMaxMs = parseDuration(nextValue, argument);
                break;
            case "--trace-map-ratio":
                options.traceMapRatio = parseRatio(nextValue, argument);
                break;
            case "--navigation-ratio":
                options.navigationRatio = parseRatio(nextValue, argument);
                break;
            case "--auth-persona":
                options.authPersona = normalizeString(nextValue);
                break;
            case "--browser":
                options.browserName = nextValue;
                break;
            case "--headless":
                options.headless = parseBoolean(nextValue, argument);
                break;
            case "--viewport":
                options.viewport = parseViewport(nextValue);
                break;
            case "--navigation-timeout":
                options.navigationTimeoutMs = parseDuration(nextValue, argument);
                break;
            case "--action-timeout":
                options.actionTimeoutMs = parseDuration(nextValue, argument);
                break;
            case "--rum-flush-wait":
                options.rumFlushWaitMs = parseDuration(nextValue, argument);
                break;
            case "--egress-check-url":
                options.egressCheckUrl = normalizeString(nextValue);
                break;
            case "--log-every":
                options.logEveryMs = parseDuration(nextValue, argument);
                break;
            default:
                throw new Error(`Unsupported option: ${argument}`);
        }

        index += 1;
    }

    return options;
}

function validateConfig(config) {
    try {
        new URL(config.baseUrl);
    } catch {
        throw new Error(`Invalid base URL: ${config.baseUrl}`);
    }
    if (config.paths.length === 0) {
        throw new Error("At least one path is required.");
    }
    if (config.targetBrowsers < 1) {
        throw new Error("Target browser count must be at least 1.");
    }
    if (config.durationMs <= 0) {
        throw new Error("Duration must be positive.");
    }
    if (config.sessionMinMs > config.sessionMaxMs) {
        throw new Error("Session min cannot be greater than session max.");
    }
    if (config.thinkTimeMinMs > config.thinkTimeMaxMs) {
        throw new Error("Think-time min cannot be greater than think-time max.");
    }
    if (!["chromium", "firefox", "webkit"].includes(config.browserName)) {
        throw new Error("Browser must be chromium, firefox, or webkit.");
    }
}

function parseCsv(value) {
    return String(value)
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean);
}

function parseDuration(value, name) {
    if (typeof value !== "string" || value.trim() === "") {
        throw new Error(`Invalid duration for ${name}: ${value}`);
    }

    const match = value.trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)?$/u);
    if (!match) {
        throw new Error(`Invalid duration for ${name}: ${value}`);
    }

    const amount = Number.parseFloat(match[1]);
    const unit = match[2] ?? "ms";
    const multiplier = {
        ms: 1,
        s: 1000,
        m: 60_000,
        h: 3_600_000
    }[unit];

    return Math.round(amount * multiplier);
}

function parseInteger(value, name) {
    const parsed = Number.parseInt(value, 10);
    if (!Number.isInteger(parsed)) {
        throw new Error(`Invalid integer for ${name}: ${value}`);
    }
    return parsed;
}

function parseRatio(value, name) {
    const parsed = Number.parseFloat(value);
    if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
        throw new Error(`Invalid ratio for ${name}: ${value}`);
    }
    return parsed;
}

function parseBoolean(value, name) {
    const normalized = String(value).trim().toLowerCase();
    if (["1", "true", "yes", "on"].includes(normalized)) {
        return true;
    }
    if (["0", "false", "no", "off"].includes(normalized)) {
        return false;
    }
    throw new Error(`Invalid boolean for ${name}: ${value}`);
}

function parseViewport(value) {
    const normalized = normalizeString(value);
    if (!normalized) {
        return null;
    }

    const match = normalized.match(/^(\d+)x(\d+)$/u);
    if (!match) {
        throw new Error(`Invalid viewport: ${value}. Use WIDTHxHEIGHT, for example 1440x900.`);
    }

    return {
        width: Number.parseInt(match[1], 10),
        height: Number.parseInt(match[2], 10)
    };
}

function normalizeString(value) {
    if (typeof value !== "string") {
        return undefined;
    }

    const trimmed = value.trim();
    return trimmed === "" ? undefined : trimmed;
}

function resolveUrl(baseUrl, path) {
    return new URL(path, ensureTrailingSlash(baseUrl)).toString();
}

function ensureTrailingSlash(value) {
    return value.endsWith("/") ? value : `${value}/`;
}

function randomBetween(min, max) {
    return min + Math.random() * (max - min);
}

function choose(values) {
    return values[Math.floor(Math.random() * values.length)];
}

function formatDuration(ms) {
    if (ms < 1000) {
        return `${ms}ms`;
    }
    if (ms < 60_000) {
        return `${(ms / 1000).toFixed(ms % 1000 === 0 ? 0 : 1)}s`;
    }
    return `${(ms / 60_000).toFixed(ms % 60_000 === 0 ? 0 : 1)}m`;
}

main().catch((error) => {
    console.error(error.stack ?? error.message);
    process.exitCode = 1;
});
