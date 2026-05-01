#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/scripts/loadgen/browser-rum-loadgen.mjs"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

help_output="$(node "${TARGET_SCRIPT}" --help)"
assert_contains "${help_output}" 'Browser RUM load generator'
assert_contains "${help_output}" 'LOADGEN_BROWSER_BASE_URL'

dry_run_output="$(
  node "${TARGET_SCRIPT}" \
    --dry-run \
    --base-url http://example.test \
    --paths /broadcast,/demo-monkey \
    --target-browsers 2 \
    --duration 30s \
    --trace-map-ratio 0.5 \
    --navigation-ratio 0.25
)"
assert_contains "${dry_run_output}" 'Base URL: http://example.test'
assert_contains "${dry_run_output}" 'Paths: /broadcast, /demo-monkey'
assert_contains "${dry_run_output}" 'Target browser contexts: 2'
assert_contains "${dry_run_output}" 'Trace-map action ratio: 0.5'
assert_contains "${dry_run_output}" 'Navigation action ratio: 0.25'
assert_contains "${dry_run_output}" 'Dry run complete.'

set +e
invalid_output="$(node "${TARGET_SCRIPT}" --dry-run --trace-map-ratio 1.5 2>&1)"
invalid_status=$?
set -e

[[ ${invalid_status} -ne 0 ]] || fail "expected invalid ratio to fail"
assert_contains "${invalid_output}" 'Invalid ratio'

printf 'PASS: browser RUM loadgen config validation\n'
