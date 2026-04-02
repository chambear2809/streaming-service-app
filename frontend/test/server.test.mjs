import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { buildProxyHeaders } = require("../server.js");

test("buildProxyHeaders preserves ThousandEyes tracing headers across proxy hops", () => {
    const headers = buildProxyHeaders(
        {
            headers: {
                host: "demo.example.com",
                traceparent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                tracestate: "thousandeyes=test-link",
                baggage: "thousandeyes.test.id=12345",
                b3: "4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",
                "x-b3-traceid": "4bf92f3577b34da6a3ce929d0e0e4736",
                "x-b3-spanid": "00f067aa0ba902b7",
                "x-b3-sampled": "1",
                "x-demo-header": "present",
                connection: "keep-alive"
            },
            socket: {
                remoteAddress: "203.0.113.10",
                encrypted: false
            }
        },
        { includeBodyHeaders: true }
    );

    assert.equal(headers.traceparent, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    assert.equal(headers.tracestate, "thousandeyes=test-link");
    assert.equal(headers.baggage, "thousandeyes.test.id=12345");
    assert.equal(headers.b3, "4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1");
    assert.equal(headers["x-b3-traceid"], "4bf92f3577b34da6a3ce929d0e0e4736");
    assert.equal(headers["x-b3-spanid"], "00f067aa0ba902b7");
    assert.equal(headers["x-b3-sampled"], "1");
    assert.equal(headers["x-demo-header"], "present");
    assert.equal(headers["x-forwarded-for"], "203.0.113.10");
    assert.equal(headers["x-forwarded-proto"], "http");
    assert.equal(headers.connection, undefined);
}
);
