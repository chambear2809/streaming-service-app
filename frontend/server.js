const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { pipeline } = require("node:stream");

const port = Number.parseInt(process.env.PORT ?? process.env.SERVER_PORT ?? "8080", 10);
const host = process.env.HOST ?? "0.0.0.0";
const staticRoot = process.env.STATIC_ROOT ?? __dirname;
const namespace = process.env.K8S_NAMESPACE ?? "streaming-service-app";

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

const protectedProxyRules = [
    {
        prefix: "/api/v1/demo/content",
        upstream: { hostname: serviceHost("content-service-demo"), port: 80 }
    },
    {
        exact: "/api/v1/demo/public/demo-monkey",
        methods: ["PUT"],
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 },
        rewritePath: "/api/v1/demo/media/demo-monkey"
    },
    {
        prefix: "/api/v1/demo/media/",
        upstream: { hostname: serviceHost("media-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/demo/ads/",
        upstream: { hostname: serviceHost("ad-service-demo"), port: 80 }
    },
    {
        prefix: "/api/v1/billing/",
        upstream: { hostname: serviceHost("billing-service"), port: 8070 }
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

    if (pathname.startsWith("/api/v1/media/video/rtsp/") || pathname.startsWith("/api/v1/stream/")) {
        writeJson(res, 503, { status: "unavailable" });
        return;
    }

    const publicRule = matchRule(publicProxyRules, pathname, req.method);
    if (publicRule) {
        await proxyRequest(req, res, buildProxyTarget(publicRule, url));
        return;
    }

    const protectedRule = matchRule(protectedProxyRules, pathname, req.method);
    if (protectedRule) {
        const authResult = await authorize(req);
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

async function authorize(req) {
    return new Promise((resolve) => {
        const upstream = {
            hostname: serviceHost("user-service-demo"),
            port: 80,
            path: "/api/v1/demo/auth/validate"
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
                    resolve({
                        authorized: (authRes.statusCode ?? 500) < 400,
                        statusCode: authRes.statusCode ?? 500,
                        headers: authRes.headers,
                        body: Buffer.concat(chunks)
                    });
                });
            }
        );

        authReq.on("error", (error) => {
            console.error("Auth validation failed", error);
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
                        console.error("Proxy response pipeline failed", error);
                    }
                    resolve();
                });
            }
        );

        upstreamReq.on("error", (error) => {
            console.error("Proxy request failed", error);
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
                console.error("Proxy request pipeline failed", error);
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
