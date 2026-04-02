#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/scripts/frontend/upload-sourcemaps.sh"
TEMP_DIR="$(mktemp -d)"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

cleanup() {
  rm -rf "${TEMP_DIR}"
}

trap cleanup EXIT

make_frontend_fixture() {
  local name="$1"
  local frontend_dir="${TEMP_DIR}/${name}"

  mkdir -p "${frontend_dir}/dist" "${frontend_dir}/node_modules/.bin"

  cat > "${frontend_dir}/dist/build-info.json" <<'EOF'
{
  "appName": "retry-demo-frontend",
  "appVersion": "retry-demo-build"
}
EOF

  cat > "${frontend_dir}/node_modules/.bin/splunk-rum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

count_file="${SPLUNK_STUB_COUNT_FILE:?}"
args_file="${SPLUNK_STUB_ARGS_FILE:?}"
fail_until="${SPLUNK_STUB_FAIL_UNTIL:-0}"
attempt=0

if [[ -f "${count_file}" ]]; then
  attempt="$(<"${count_file}")"
fi

attempt=$((attempt + 1))
printf '%s' "${attempt}" > "${count_file}"
printf '%s\n' "$@" > "${args_file}"

if (( attempt <= fail_until )); then
  printf '503 Service Unavailable\n' >&2
  exit 1
fi

printf 'upload complete on attempt %s\n' "${attempt}"
EOF
  chmod +x "${frontend_dir}/node_modules/.bin/splunk-rum"

  printf '%s\n' "${frontend_dir}"
}

run_success_case() {
  local frontend_dir
  local output_file="${TEMP_DIR}/success.out"
  local output=""
  local args=""

  frontend_dir="$(make_frontend_fixture success)"

  LOG_PREFIX="[sourcemap-test]" \
  ENV_FILE="${frontend_dir}/missing.env" \
  FRONTEND_DIR="${frontend_dir}" \
  SPLUNK_REALM="us1" \
  SPLUNK_SOURCEMAP_UPLOAD_TOKEN="demo-token" \
  SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS=4 \
  SPLUNK_SOURCEMAP_UPLOAD_INITIAL_DELAY_SECONDS=0 \
  SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS=0 \
  SPLUNK_STUB_COUNT_FILE="${frontend_dir}/count.txt" \
  SPLUNK_STUB_ARGS_FILE="${frontend_dir}/args.txt" \
  SPLUNK_STUB_FAIL_UNTIL=2 \
  bash "${TARGET_SCRIPT}" > "${output_file}" 2>&1

  [[ "$(<"${frontend_dir}/count.txt")" == "3" ]] || fail "expected three upload attempts before success"

  output="$(<"${output_file}")"
  args="$(tr '\n' ' ' < "${frontend_dir}/args.txt")"

  assert_contains "${output}" "attempt 1/4"
  assert_contains "${output}" "failed with exit code 1."
  assert_contains "${output}" "Retrying frontend sourcemap upload in 0s."
  assert_contains "${output}" "Splunk sourcemap upload succeeded on attempt 3/4."
  assert_contains "${args}" "--app-name retry-demo-frontend"
  assert_contains "${args}" "--app-version retry-demo-build"
  assert_contains "${args}" "--path ${frontend_dir}/dist"
}

run_failure_case() {
  local frontend_dir
  local output_file="${TEMP_DIR}/failure.out"
  local output=""
  local status=0

  frontend_dir="$(make_frontend_fixture failure)"

  set +e
  LOG_PREFIX="[sourcemap-test]" \
  ENV_FILE="${frontend_dir}/missing.env" \
  FRONTEND_DIR="${frontend_dir}" \
  SPLUNK_REALM="us1" \
  SPLUNK_SOURCEMAP_UPLOAD_TOKEN="demo-token" \
  SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS=3 \
  SPLUNK_SOURCEMAP_UPLOAD_INITIAL_DELAY_SECONDS=0 \
  SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS=0 \
  SPLUNK_STUB_COUNT_FILE="${frontend_dir}/count.txt" \
  SPLUNK_STUB_ARGS_FILE="${frontend_dir}/args.txt" \
  SPLUNK_STUB_FAIL_UNTIL=9 \
  bash "${TARGET_SCRIPT}" > "${output_file}" 2>&1
  status=$?
  set -e

  [[ ${status} -ne 0 ]] || fail "expected upload helper to fail after exhausting retries"
  [[ "$(<"${frontend_dir}/count.txt")" == "3" ]] || fail "expected upload helper to stop after max attempts"

  output="$(<"${output_file}")"
  assert_contains "${output}" "failed with exit code 1."
  assert_contains "${output}" "Splunk sourcemap upload failed after 3 attempts."
  assert_contains "${output}" "Retry later by rerunning scripts/frontend/upload-sourcemaps.sh"
}

run_env_token_precedence_case() {
  local frontend_dir
  local output_file="${TEMP_DIR}/env-token.out"
  local args=""

  frontend_dir="$(make_frontend_fixture env-token)"

  cat > "${frontend_dir}/upload.env" <<'EOF'
SPLUNK_REALM=us1
SPLUNK_ACCESS_TOKEN=observability-upload-token
SPLUNK_RUM_ACCESS_TOKEN=browser-rum-token
EOF

  LOG_PREFIX="[sourcemap-test]" \
  ENV_FILE="${frontend_dir}/upload.env" \
  FRONTEND_DIR="${frontend_dir}" \
  SPLUNK_STUB_COUNT_FILE="${frontend_dir}/count.txt" \
  SPLUNK_STUB_ARGS_FILE="${frontend_dir}/args.txt" \
  SPLUNK_STUB_FAIL_UNTIL=0 \
  bash "${TARGET_SCRIPT}" > "${output_file}" 2>&1

  [[ "$(<"${frontend_dir}/count.txt")" == "1" ]] || fail "expected helper to succeed on first attempt with env token defaults"

  args="$(tr '\n' ' ' < "${frontend_dir}/args.txt")"
  assert_contains "${args}" "--token observability-upload-token"
}

run_browser_token_only_hint_case() {
  local frontend_dir
  local output_file="${TEMP_DIR}/browser-token-only.out"
  local output=""
  local status=0

  frontend_dir="$(make_frontend_fixture browser-token-only)"

  cat > "${frontend_dir}/upload.env" <<'EOF'
SPLUNK_REALM=us1
SPLUNK_RUM_ACCESS_TOKEN=browser-rum-token
EOF

  set +e
  LOG_PREFIX="[sourcemap-test]" \
  ENV_FILE="${frontend_dir}/upload.env" \
  FRONTEND_DIR="${frontend_dir}" \
  bash "${TARGET_SCRIPT}" > "${output_file}" 2>&1
  status=$?
  set -e

  [[ ${status} -ne 0 ]] || fail "expected helper to fail when only the browser RUM token is present"

  output="$(<"${output_file}")"
  assert_contains "${output}" "SPLUNK_RUM_ACCESS_TOKEN is for Browser RUM only."
  assert_contains "${output}" "Set SPLUNK_ACCESS_TOKEN or SPLUNK_SOURCEMAP_UPLOAD_TOKEN for sourcemap upload."
}

run_success_case
run_failure_case
run_env_token_precedence_case
run_browser_token_only_hint_case
