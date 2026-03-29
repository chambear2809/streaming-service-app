const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { pipeline } = require("node:stream");

const port = Number.parseInt(process.env.PORT ?? process.env.SERVER_PORT ?? "8080", 10);
const host = process.env.HOST ?? "0.0.0.0";
const staticRoot = process.env.STATIC_ROOT ?? __dirname;
const namespace = process.env.K8S_NAMESPACE ?? "streaming-service-app";
const upstreamRequestTimeoutMs = Number.parseInt(process.env.UPSTREAM_REQUEST_TIMEOUT_MS ?? "5000", 10);
const errorLogThrottleMs = Number.parseInt(process.env.ERROR_LOG_THROTTLE_MS ?? "30000", 10);
const throttledErrorState = new Map();

const HOP_BY_HOP_HEADERS = new Set([
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade"
]);

const TRACE_HEADERS = new Set([
    "traceparent",
    "tracestate",
    "baggage",
    "b3",
    "x-b3-traceid",
    "x-b3-spanid",
    "x-b3-parentspanid",
    "x-b3-sampled",
    "x-b3-flags"
]);

const CONTENT_TYPES = {
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".map": "application/json; charset=utf-8",
    ".txt": "text/plain; charset=utf-8"
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

const protectedProxyRules = [
    {
        exact: "/api/v1/demo/content",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("content-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/public/demo-monkey",
        methods: ["PUT"],
        capabilities: ["governance"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 },
        rewritePath: "/api/v1/demo/media/demo-monkey"
    },
    {
        exact: "/api/v1/demo/media/demo-monkey",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/media/demo-monkey",
        methods: ["PUT"],
        capabilities: ["governance"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/media/rtsp/jobs",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/media/rtsp/jobs",
        methods: ["POST"],
        capabilities: ["ingest"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/demo/media/rtsp/jobs/",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/demo/media/broadcast/jobs/",
        methods: ["POST"],
        capabilities: ["ingest"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/media/movie.mp4",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/demo/media/library/",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/stream/",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/ads/issues",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("ad-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/ads/issues",
        methods: ["PUT"],
        capabilities: ["governance"],
        upstream: { hostname: serviceHost("ad-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/ads/program-queue",
        methods: ["GET"],
        capabilities: ["operations"],
        upstream: { hostname: serviceHost("ad-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/billing/invoices",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    },
    {
        exact: "/api/v1/billing/invoices",
        methods: ["POST"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    },
    {
        prefix: "/api/v1/billing/invoices/",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    },
    {
        prefix: "/api/v1/billing/invoices/",
        methods: ["POST", "PUT"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    },
    {
        exact: "/api/v1/billing/events",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    },
    {
        exact: "/api/v1/billing/events",
        methods: ["POST"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    },
    {
        prefix: "/api/v1/billing/accounts/",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    },
    {
        exact: "/api/v1/customers",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("customer-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/customers",
        methods: ["PUT"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("customer-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/customers/",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("customer-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/payments/card-holder",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("payment-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/payments/card-holder",
        methods: ["POST", "PUT"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("payment-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/payments/address",
        methods: ["PUT"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("payment-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/payments/payment-method",
        methods: ["PUT"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("payment-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/payments/transactions",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("payment-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/subscription/all",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("subscription-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/subscription/active",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("subscription-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/subscription/cancel",
        methods: ["POST"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("subscription-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/orders",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("order-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/orders",
        methods: ["POST"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("order-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/orders/",
        methods: ["GET"],
        capabilities: ["billing"],
        upstream: { hostname: serviceHost("order-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/orders/",
        methods: ["PUT"],
        capabilities: ["billing_write"],
        upstream: { hostname: serviceHost("order-service-demo"), port: 80 }
    }
];

const publicProxyRules = [
    {
        exact: "/api/v1/demo/auth/login",
        upstream: { hostname: serviceHost("user-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/demo/auth/persona/",
        upstream: { hostname: serviceHost("user-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/auth/session",
        upstream: { hostname: serviceHost("user-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/auth/logout",
        upstream: { hostname: serviceHost("user-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/auth/health",
        upstream: { hostname: serviceHost("user-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/content/health",
        upstream: { hostname: serviceHost("content-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/media/health",
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/ads/health",
        upstream: { hostname: serviceHost("ad-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/public/demo-monkey",
        methods: ["GET"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 },
        rewritePath: "/api/v1/demo/media/demo-monkey"
    },
    {
        exact: "/api/v1/demo/public/trace-map",
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/demo/public/broadcast/",
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/billing/health",
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
    }
];

const server = http.createServer(async (req, res) => {
    try {
        const url = new URL(req.url, `http://${req.headers.host ?? "localhost"}`);
        const pathname = url.pathname;

        if (pathname === "/healthz") {
            writeJson(res, 200, { status: "ok" });
            return;
        }

        if (pathname === "/broadcast") {
            redirect(res, "/broadcast.html");
            return;
        }

        if (pathname === "/demo-monkey") {
            redirect(res, "/demo-monkey.html");
            return;
        }

        if (pathname.startsWith("/api/")) {
            await handleApi(req, res, url);
            return;
        }

        await serveStatic(res, pathname);
    } catch (error) {
        console.error("Unhandled frontend gateway error", error);
        writeJson(res, 500, {
            error: "frontend_gateway_error",
            message: "The frontend gateway could not complete the request."
        });
    }
});

server.listen(port, host, () => {
    console.log(`streaming-frontend gateway listening on ${host}:${port}`);
});

async function handleApi(req, res, url) {
    const pathname = url.pathname;

    const publicRule = matchRule(publicProxyRules, pathname, req.method);
    if (publicRule) {
        await proxyRequest(req, res, buildProxyTarget(publicRule, url));
        return;
    }

    const protectedRule = matchRule(protectedProxyRules, pathname, req.method);
    if (protectedRule) {
        const authResult = await authorize(req, protectedRule.capabilities ?? []);
        if (!authResult.authorized) {
            forwardAuthFailure(res, authResult);
            return;
        }

        await proxyRequest(req, res, buildProxyTarget(protectedRule, url));
        return;
    }

    writeJson(res, 404, {
        error: "not_found",
        message: "No frontend proxy route matched the request."
    });
}

async function authorize(req, requiredCapabilities = []) {
    return new Promise((resolve) => {
        const upstream = {
            hostname: serviceHost("user-service-demo"),
            port: 80,
            path: "/api/v1/demo/auth/session"
        };

        const authReq = http.request(
            {
                hostname: upstream.hostname,
                port: upstream.port,
                path: upstream.path,
                method: "GET",
                headers: buildProxyHeaders(req, {
                    includeBodyHeaders: false
                })
            },
            (authRes) => {
                const chunks = [];

                authRes.on("data", (chunk) => {
                    chunks.push(chunk);
                });

                authRes.on("end", () => {
                    const body = Buffer.concat(chunks);
                    const statusCode = authRes.statusCode ?? 500;

                    if (statusCode >= 400) {
                        resolve({
                            authorized: false,
                            statusCode,
                            headers: authRes.headers,
                            body
                        });
                        return;
                    }

                    try {
                        const payload = JSON.parse(body.toString("utf8"));
                        const role = normalizeRole(payload?.user?.role ?? payload?.role);
                        if (!hasRequiredCapabilities(role, requiredCapabilities)) {
                            resolve(forbiddenAuthResult(requiredCapabilities));
                            return;
                        }

                        resolve({
                            authorized: true,
                            statusCode,
                            headers: authRes.headers,
                            body
                        });
                    } catch (error) {
                        logThrottledError("Auth session parsing failed", error, `${upstream.hostname}:${upstream.port}${upstream.path}`);
                        resolve({
                            authorized: false,
                            statusCode: 502,
                            headers: { "content-type": "application/json; charset=utf-8" },
                            body: Buffer.from(
                                JSON.stringify({
                                    error: "auth_session_parse_failed",
                                    message: "Unable to read the current user session."
                                })
                            )
                        });
                    }
                });
            }
        );

        authReq.on("error", (error) => {
            logThrottledError("Auth validation failed", error, `${upstream.hostname}:${upstream.port}${upstream.path}`);
            resolve({
                authorized: false,
                statusCode: 502,
                headers: { "content-type": "application/json; charset=utf-8" },
                body: Buffer.from(
                    JSON.stringify({
                        error: "auth_validation_failed",
                        message: "Unable to validate the current user session."
                    })
                )
            });
        });

        authReq.end();
    });
}

function normalizeRole(role) {
    const normalizedRole = String(role ?? "").trim().toLowerCase();
    return normalizedRole || "staff_operator";
}

function hasRequiredCapabilities(role, requiredCapabilities) {
    if (!Array.isArray(requiredCapabilities) || requiredCapabilities.length === 0) {
        return true;
    }

    const permissions = roleCapabilities[role] ?? roleCapabilities.staff_operator;
    return requiredCapabilities.every((capability) => permissions.includes(capability));
}

function forbiddenAuthResult(requiredCapabilities) {
    const capabilityLabel = Array.isArray(requiredCapabilities) && requiredCapabilities.length
        ? requiredCapabilities.join(", ")
        : "the required capability";

    return {
        authorized: false,
        statusCode: 403,
        headers: { "content-type": "application/json; charset=utf-8" },
        body: Buffer.from(
            JSON.stringify({
                error: "insufficient_role_capability",
                message: `Your current session is not permitted to access this route. Required capability: ${capabilityLabel}.`
            })
        )
    };
}

function forwardAuthFailure(res, authResult) {
    res.statusCode = authResult.statusCode;

    for (const [headerName, headerValue] of Object.entries(authResult.headers ?? {})) {
        if (headerValue === undefined || HOP_BY_HOP_HEADERS.has(headerName.toLowerCase())) {
            continue;
        }

        res.setHeader(headerName, headerValue);
    }

    res.end(authResult.body);
}

async function proxyRequest(req, res, target) {
    return new Promise((resolve) => {
        const targetKey = `${target.hostname}:${target.port}${target.path}`;
        const upstreamReq = http.request(
            {
                hostname: target.hostname,
                port: target.port,
                path: target.path,
                method: req.method,
                headers: buildProxyHeaders(req, {
                    includeBodyHeaders: true
                })
            },
            (upstreamRes) => {
                res.statusCode = upstreamRes.statusCode ?? 502;

                for (const [headerName, headerValue] of Object.entries(upstreamRes.headers)) {
                    if (headerValue === undefined || HOP_BY_HOP_HEADERS.has(headerName.toLowerCase())) {
                        continue;
                    }

                    res.setHeader(headerName, headerValue);
                }

                pipeline(upstreamRes, res, (error) => {
                    if (error) {
                        logThrottledError("Proxy response pipeline failed", error, targetKey);
                    }
                    resolve();
                });
            }
        );

        upstreamReq.setTimeout(upstreamRequestTimeoutMs, () => {
            upstreamReq.destroy(new Error(`Upstream request timed out after ${upstreamRequestTimeoutMs}ms.`));
        });

        upstreamReq.on("error", (error) => {
            logThrottledError("Proxy request failed", error, targetKey);
            if (!res.headersSent) {
                writeJson(res, 502, {
                    error: "upstream_unavailable",
                    message: "The frontend gateway could not reach the upstream service."
                });
            } else {
                res.destroy(error);
            }
            resolve();
        });

        req.on("aborted", () => {
            upstreamReq.destroy();
            resolve();
        });

        pipeline(req, upstreamReq, (error) => {
            if (error && error.code !== "ERR_STREAM_PREMATURE_CLOSE") {
                logThrottledError("Proxy request pipeline failed", error, targetKey);
            }
        });
    });
}

function buildProxyTarget(rule, url) {
    return {
        hostname: rule.upstream.hostname,
        port: rule.upstream.port,
        path: `${rule.rewritePath ?? url.pathname}${url.search}`
    };
}

function buildProxyHeaders(req, options = {}) {
    const headers = {};

    for (const [headerName, headerValue] of Object.entries(req.headers)) {
        const normalized = headerName.toLowerCase();

        if (
            headerValue === undefined
            || HOP_BY_HOP_HEADERS.has(normalized)
            || TRACE_HEADERS.has(normalized)
        ) {
            continue;
        }

        if (!options.includeBodyHeaders && (normalized === "content-length" || normalized === "content-type")) {
            continue;
        }

        headers[headerName] = headerValue;
    }

    headers["x-forwarded-for"] = appendForwardedFor(req.headers["x-forwarded-for"], req.socket.remoteAddress);
    headers["x-forwarded-proto"] = req.headers["x-forwarded-proto"] ?? (req.socket.encrypted ? "https" : "http");

    return headers;
}

async function serveStatic(res, requestPath) {
    const relativePath = requestPath === "/" ? "/index.html" : requestPath;
    const filePath = resolveStaticPath(relativePath);
    const indexPath = resolveStaticPath("/index.html");
    const shouldFallbackToIndex = !filePath && !path.extname(relativePath);
    const targetPath = filePath ?? (shouldFallbackToIndex ? indexPath : null);

    if (!targetPath) {
        writeJson(res, 404, {
            error: "asset_not_found",
            message: "The requested frontend asset is not available."
        });
        return;
    }

    const contentType = CONTENT_TYPES[path.extname(targetPath).toLowerCase()] ?? "application/octet-stream";
    res.statusCode = 200;
    res.setHeader("content-type", contentType);

    const stream = fs.createReadStream(targetPath);
    stream.on("error", (error) => {
        console.error("Static asset read failed", error);
        if (!res.headersSent) {
            writeJson(res, 500, {
                error: "asset_read_failed",
                message: "The frontend asset could not be read."
            });
        } else {
            res.destroy(error);
        }
    });
    stream.pipe(res);
}

function resolveStaticPath(requestPath) {
    const decodedPath = decodeURIComponent(requestPath);
    const relativePath = decodedPath.replace(/^\/+/, "");
    const resolvedRoot = path.resolve(staticRoot);
    const candidate = path.resolve(resolvedRoot, relativePath);
    const relativeCandidate = path.relative(resolvedRoot, candidate);

    if (relativeCandidate.startsWith("..") || path.isAbsolute(relativeCandidate)) {
        return null;
    }

    try {
        const stats = fs.statSync(candidate);
        if (stats.isFile()) {
            return candidate;
        }
    } catch {
        return null;
    }

    return null;
}

function matchRule(rules, pathname, method) {
    return rules.find((rule) => {
        if (Array.isArray(rule.methods) && !rule.methods.includes(String(method ?? "").toUpperCase())) {
            return false;
        }

        if (rule.exact) {
            return pathname === rule.exact;
        }

        if (rule.prefix) {
            return pathname.startsWith(rule.prefix);
        }

        return false;
    });
}

function appendForwardedFor(existing, remoteAddress) {
    const forwarded = [existing, remoteAddress].filter(Boolean).join(", ");
    return forwarded || "127.0.0.1";
}

function redirect(res, location) {
    res.statusCode = 302;
    res.setHeader("location", location);
    res.end();
}

function writeJson(res, statusCode, payload) {
    if (!res.headersSent) {
        res.statusCode = statusCode;
        res.setHeader("content-type", "application/json; charset=utf-8");
    }
    res.end(JSON.stringify(payload));
}

function serviceHost(name) {
    return `${name}.${namespace}.svc.cluster.local`;
}

function logThrottledError(context, error, key) {
    const normalizedKey = `${context}:${key}`;
    const now = Date.now();
    const previous = throttledErrorState.get(normalizedKey);
    const summary = summarizeError(error);

    if (!previous || now - previous.loggedAt >= errorLogThrottleMs) {
        const suppressed = previous?.suppressed ?? 0;
        throttledErrorState.set(normalizedKey, { loggedAt: now, suppressed: 0 });

        if (suppressed > 0) {
            console.error(`${context} (${key}) repeated ${suppressed} additional time(s). Latest error: ${summary}`);
        } else {
            console.error(`${context} (${key}): ${summary}`);
        }
        return;
    }

    previous.suppressed += 1;
}

function summarizeError(error) {
    if (!error) {
        return "Unknown error.";
    }

    const parts = [];
    if (error.code) {
        parts.push(String(error.code));
    }
    if (error.syscall) {
        parts.push(String(error.syscall));
    }
    if (error.address) {
        parts.push(String(error.address));
    }
    if (error.port) {
        parts.push(String(error.port));
    }
    if (error.message) {
        parts.push(String(error.message));
    }

    return parts.join(" ") || String(error);
}
