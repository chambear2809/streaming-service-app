#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/post-render-splunk-otel-manifests.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" != *"${needle}"* ]] || fail "did not expect output to contain: ${needle}"
}

input="$(cat <<'EOF'
apiVersion: v1
kind: Service
metadata:
  name: splunk-otel-collector-agent
spec:
  clusterIP: None
  internalTrafficPolicy: Local
---
apiVersion: v1
kind: Service
metadata:
  name: untouched-service
spec:
  internalTrafficPolicy: Local
EOF
)"

output="$(printf '%s\n' "${input}" | bash "${TARGET_SCRIPT}")"

assert_contains "${output}" "name: splunk-otel-collector-agent"
assert_contains "${output}" "internalTrafficPolicy: Cluster"
assert_contains "${output}" "name: untouched-service"
assert_contains "${output}" $'name: untouched-service\nspec:\n  internalTrafficPolicy: Local'
assert_not_contains "${output}" $'name: splunk-otel-collector-agent\nspec:\n  clusterIP: None\n  internalTrafficPolicy: Local'

printf 'PASS: post-render Splunk OTel manifests\n'
