#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${1:-${REPO_ROOT}/.env}"

if ! command -v node >/dev/null 2>&1; then
  printf 'node is required to generate local JWT key material\n' >&2
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  cp "${REPO_ROOT}/example.env" "${ENV_FILE}"
fi

node - "${ENV_FILE}" <<'EOF'
const fs = require("fs");
const { generateKeyPairSync, randomBytes } = require("crypto");

const envPath = process.argv[2];
const source = fs.readFileSync(envPath, "utf8");
const lines = source.split(/\r?\n/);

function quoteForShell(value) {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

function setVar(name, value) {
  const rendered = `${name}=${value}`;
  const index = lines.findIndex((line) => line.startsWith(`${name}=`));
  if (index >= 0) {
    lines[index] = rendered;
    return;
  }
  lines.push(rendered);
}

const { privateKey, publicKey } = generateKeyPairSync("rsa", {
  modulusLength: 2048,
  publicExponent: 0x10001,
});

setVar("CONFIG_SERVER_JWT_SECRET_KEY", randomBytes(64).toString("base64url"));
setVar(
  "USER_SERVICE_JWT_PRIVATE_KEY",
  quoteForShell(JSON.stringify(privateKey.export({ format: "jwk" }))),
);
setVar(
  "USER_SERVICE_JWT_PUBLIC_KEY",
  quoteForShell(JSON.stringify(publicKey.export({ format: "jwk" }))),
);

let output = lines.join("\n");
if (!output.endsWith("\n")) {
  output += "\n";
}
fs.writeFileSync(envPath, output);
EOF

printf 'Wrote local auth key material to %s\n' "${ENV_FILE}"
printf 'Run: set -a; source %q; set +a\n' "${ENV_FILE}"
