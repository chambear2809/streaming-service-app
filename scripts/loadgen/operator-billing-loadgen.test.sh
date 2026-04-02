#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/scripts/loadgen/operator-billing-loadgen.mjs"
TEMP_DIR="$(mktemp -d)"
PORT="$((20000 + RANDOM % 20000))"
SERVER_SCRIPT="${TEMP_DIR}/stub-server.mjs"
COUNTS_FILE="${TEMP_DIR}/counts.json"
OUTPUT_FILE="${TEMP_DIR}/output.txt"
SUMMARY_FILE="${TEMP_DIR}/summary.json"
SERVER_PID=""

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
  rm -rf "${TEMP_DIR}"
}

trap cleanup EXIT

cat > "${SERVER_SCRIPT}" <<'EOF'
import fs from "node:fs";
import http from "node:http";

const [portArg, countsPath] = process.argv.slice(2);
const port = Number.parseInt(portArg, 10);
const counts = {};

function record(pathname) {
  counts[pathname] = (counts[pathname] ?? 0) + 1;
}

function writeCounts() {
  fs.writeFileSync(countsPath, JSON.stringify(counts, null, 2));
}

const invoices = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    userId: "8f61f6c0-29dc-4f6d-9a31-1fd4a4ad5001",
    invoiceNumber: "INV-DEMO-1",
    status: "OPEN",
    balanceDue: 960,
    totalAmount: 960,
    billingCycle: "ONE_TIME",
    currency: "USD",
    issuedDate: "2026-03-30",
    dueDate: "2026-04-07",
    servicePeriodStart: "2026-03-01",
    servicePeriodEnd: "2026-03-31"
  }
];

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`);
  record(url.pathname);

  if (req.method === "POST" && url.pathname === "/api/v1/demo/auth/persona/operator") {
    res.writeHead(200, {
      "content-type": "application/json; charset=utf-8",
      "set-cookie": "acme_demo_session=test-session; Path=/; HttpOnly"
    });
    res.end(JSON.stringify({ authenticated: true }));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/demo/auth/session") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({
      authenticated: true,
      user: {
        email: "ops@acmebroadcasting.com",
        role: "platform_admin",
        roleLabel: "Platform Admin"
      }
    }));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/demo/content") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify([
      {
        id: "22222222-2222-2222-2222-222222222222",
        title: "Demo Asset",
        type: "MOVIE"
      }
    ]));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/demo/media/rtsp/jobs") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify([]));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/billing/invoices") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify(invoices));
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/v1/billing/events") {
    res.writeHead(201, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ status: "accepted" }));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/demo/public/broadcast/current") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({
      status: "DEMO_LOOP",
      sourceType: "DEMO_LIBRARY",
      publicPlaybackUrl: "/api/v1/demo/public/broadcast/live/index.m3u8"
    }));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/demo/public/trace-map") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ status: "OK" }));
    return;
  }

  if (
    url.pathname === "/api/v1/customers"
    || url.pathname === "/api/v1/payments/card-holder"
    || url.pathname === "/api/v1/payments/transactions"
    || url.pathname === "/api/v1/subscription/all"
    || url.pathname === "/api/v1/subscription/active"
    || url.pathname === "/api/v1/orders"
  ) {
    res.writeHead(502, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({
      error: "upstream_unavailable",
      message: "The frontend gateway could not reach the upstream service."
    }));
    return;
  }

  res.writeHead(404, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify({ error: "not_found", path: url.pathname }));
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    writeCounts();
    server.close(() => process.exit(0));
  });
}

process.on("exit", writeCounts);

server.listen(port, "127.0.0.1");
EOF

node "${SERVER_SCRIPT}" "${PORT}" "${COUNTS_FILE}" &
SERVER_PID="$!"
sleep 1

set +e
node "${TARGET_SCRIPT}" \
  --base-url "http://127.0.0.1:${PORT}" \
  --duration 4s \
  --concurrency 1 \
  --pause 1s \
  --billing-event-ratio 1 \
  --payment-ratio 0 \
  --payment-read-ratio 1 \
  --commerce-read-ratio 1 \
  --order-create-ratio 1 \
  --order-settle-ratio 1 \
  --order-complete-ratio 1 \
  --rtsp-job-ratio 0 \
  --take-live-ratio 0 \
  --restore-house-loop false \
  > "${OUTPUT_FILE}" 2>&1
status=$?
set -e

[[ ${status} -eq 0 ]] || {
  cat "${OUTPUT_FILE}" >&2
  fail "operator-billing-loadgen exited with status ${status}"
}

kill "${SERVER_PID}" 2>/dev/null || true
wait "${SERVER_PID}" 2>/dev/null || true
SERVER_PID=""

node -e '
const fs = require("fs");
const output = fs.readFileSync(process.argv[1], "utf8");
const start = output.lastIndexOf("\n{");
const jsonText = (start >= 0 ? output.slice(start + 1) : output).trim();
JSON.parse(jsonText);
fs.writeFileSync(process.argv[2], jsonText);
' "${OUTPUT_FILE}" "${SUMMARY_FILE}"

node -e '
const fs = require("fs");
const summary = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const counts = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));

if (summary.optionalServiceStatus.accounts !== "unavailable") {
  throw new Error("expected accounts workspace to be unavailable");
}
if (summary.optionalServiceStatus.payments !== "unavailable") {
  throw new Error("expected payments workspace to be unavailable");
}
if (summary.optionalServiceStatus.commerce !== "unavailable") {
  throw new Error("expected commerce workspace to be unavailable");
}
if (summary.billingEvents < 1) {
  throw new Error("expected at least one billing event");
}
if ((counts["/api/v1/customers"] ?? 0) !== 1) {
  throw new Error("expected exactly one customer-directory attempt");
}
if ((counts["/api/v1/payments/card-holder"] ?? 0) !== 1) {
  throw new Error("expected exactly one payment workspace attempt");
}
if ((counts["/api/v1/subscription/all"] ?? 0) !== 1) {
  throw new Error("expected exactly one commerce workspace attempt");
}
' "${SUMMARY_FILE}" "${COUNTS_FILE}" || {
  cat "${OUTPUT_FILE}" >&2
  fail "operator-billing-loadgen did not degrade cleanly when optional services were absent"
}

printf 'PASS: operator-billing-loadgen optional service fallback\n'
