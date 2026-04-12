"use strict";

/**
 * Unit tests for the gateway matchRule function and proxy rule tables.
 *
 * server.js does not export matchRule or the rule arrays, so they are
 * replicated here verbatim from server.js.  Any change to the originals
 * should be reflected here.
 *
 * Run with:  node server.test.js
 */

const assert = require("node:assert");

// ---------------------------------------------------------------------------
// Inline copy of matchRule from server.js
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Inline copy of publicProxyRules from server.js (upstream objects omitted —
// only the routing fields are needed for matching tests)
// ---------------------------------------------------------------------------

const publicProxyRules = [
    { exact: "/api/v1/demo/auth/login" },
    { prefix: "/api/v1/demo/auth/persona/" },
    { exact: "/api/v1/demo/auth/session" },
    { exact: "/api/v1/demo/auth/logout" },
    { exact: "/api/v1/demo/auth/health" },
    { exact: "/api/v1/demo/content/health" },
    { exact: "/api/v1/demo/media/health" },
    { exact: "/api/v1/demo/ads/health" },
    { exact: "/api/v1/demo/public/demo-monkey", methods: ["GET"] },
    { exact: "/api/v1/demo/public/trace-map" },
    { prefix: "/api/v1/demo/public/broadcast/" },
    { exact: "/api/v1/billing/health" }
];

// ---------------------------------------------------------------------------
// Inline copy of protectedProxyRules from server.js (upstream objects omitted)
// ---------------------------------------------------------------------------

const protectedProxyRules = [
    { exact: "/api/v1/demo/content",                   methods: ["GET"],          capabilities: ["operations"] },
    { exact: "/api/v1/demo/public/demo-monkey",        methods: ["PUT"],          capabilities: ["governance"] },
    { exact: "/api/v1/demo/media/demo-monkey",         methods: ["GET"],          capabilities: ["operations"] },
    { exact: "/api/v1/demo/media/demo-monkey",         methods: ["PUT"],          capabilities: ["governance"] },
    { exact: "/api/v1/demo/media/rtsp/jobs",           methods: ["GET"],          capabilities: ["operations"] },
    { exact: "/api/v1/demo/media/rtsp/jobs",           methods: ["POST"],         capabilities: ["ingest"] },
    { prefix: "/api/v1/demo/media/rtsp/jobs/",         methods: ["GET"],          capabilities: ["operations"] },
    { prefix: "/api/v1/demo/media/broadcast/jobs/",    methods: ["POST"],         capabilities: ["ingest"] },
    { exact: "/api/v1/demo/media/broadcast/reset",     methods: ["POST"],         capabilities: ["ingest"] },
    { exact: "/api/v1/demo/media/movie.mp4",           methods: ["GET"],          capabilities: ["operations"] },
    { prefix: "/api/v1/demo/media/library/",           methods: ["GET"],          capabilities: ["operations"] },
    { prefix: "/api/v1/stream/",                       methods: ["GET"],          capabilities: ["operations"] },
    { exact: "/api/v1/demo/ads/issues",                methods: ["GET"],          capabilities: ["operations"] },
    { exact: "/api/v1/demo/ads/issues",                methods: ["PUT"],          capabilities: ["governance"] },
    { exact: "/api/v1/demo/ads/program-queue",         methods: ["GET"],          capabilities: ["operations"] },
    { exact: "/api/v1/billing/invoices",               methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/billing/invoices",               methods: ["POST"],         capabilities: ["billing_write"] },
    { prefix: "/api/v1/billing/invoices/",             methods: ["GET"],          capabilities: ["billing"] },
    { prefix: "/api/v1/billing/invoices/",             methods: ["POST", "PUT"],  capabilities: ["billing_write"] },
    { exact: "/api/v1/billing/events",                 methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/billing/events",                 methods: ["POST"],         capabilities: ["billing_write"] },
    { prefix: "/api/v1/billing/accounts/",             methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/customers",                      methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/customers",                      methods: ["PUT"],          capabilities: ["billing_write"] },
    { prefix: "/api/v1/customers/",                    methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/payments/card-holder",           methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/payments/card-holder",           methods: ["POST", "PUT"],  capabilities: ["billing_write"] },
    { exact: "/api/v1/payments/address",               methods: ["PUT"],          capabilities: ["billing_write"] },
    { exact: "/api/v1/payments/payment-method",        methods: ["PUT"],          capabilities: ["billing_write"] },
    { exact: "/api/v1/payments/transactions",          methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/subscription/all",               methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/subscription/active",            methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/subscription/cancel",            methods: ["POST"],         capabilities: ["billing_write"] },
    { exact: "/api/v1/orders",                         methods: ["GET"],          capabilities: ["billing"] },
    { exact: "/api/v1/orders",                         methods: ["POST"],         capabilities: ["billing_write"] },
    { prefix: "/api/v1/orders/",                       methods: ["GET"],          capabilities: ["billing"] },
    { prefix: "/api/v1/orders/",                       methods: ["PUT"],          capabilities: ["billing_write"] }
];

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function run(name, fn) {
    try {
        fn();
        console.log(`  PASS  ${name}`);
        passed += 1;
    } catch (err) {
        console.error(`  FAIL  ${name}`);
        console.error(`        ${err.message}`);
        failed += 1;
    }
}

run("GET /api/v1/demo/auth/login matches a public rule", () => {
    const rule = matchRule(publicProxyRules, "/api/v1/demo/auth/login", "GET");
    assert.ok(rule, "expected a matching public rule");
    assert.strictEqual(rule.exact, "/api/v1/demo/auth/login");
});

run("GET /api/v1/billing/invoices matches a protected rule requiring the billing capability", () => {
    const rule = matchRule(protectedProxyRules, "/api/v1/billing/invoices", "GET");
    assert.ok(rule, "expected a matching protected rule");
    assert.ok(
        Array.isArray(rule.capabilities) && rule.capabilities.includes("billing"),
        "expected the billing capability to be required"
    );
});

run("GET /api/v1/nonexistent matches no rule", () => {
    const publicRule  = matchRule(publicProxyRules,    "/api/v1/nonexistent", "GET");
    const protectedRule = matchRule(protectedProxyRules, "/api/v1/nonexistent", "GET");
    assert.strictEqual(publicRule,    undefined, "expected no public rule");
    assert.strictEqual(protectedRule, undefined, "expected no protected rule");
});

run("Path traversal /api/v1/../etc/passwd matches no rule", () => {
    // The pathname arriving at matchRule is the already-parsed URL pathname;
    // Node's URL parser normalises /api/v1/../etc/passwd to /etc/passwd, which
    // has no registered route.  We test the raw traversal string as well to
    // confirm matchRule itself does not accidentally treat it as a valid prefix.
    const rawTraversal    = "/api/v1/../etc/passwd";
    const normTraversal   = "/etc/passwd";

    for (const pathname of [rawTraversal, normTraversal]) {
        const pub  = matchRule(publicProxyRules,    pathname, "GET");
        const prot = matchRule(protectedProxyRules, pathname, "GET");
        assert.strictEqual(pub,  undefined, `expected no public rule for "${pathname}"`);
        assert.strictEqual(prot, undefined, `expected no protected rule for "${pathname}"`);
    }
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log(`\n${passed} passed, ${failed} failed`);
if (failed > 0) {
    process.exitCode = 1;
}
