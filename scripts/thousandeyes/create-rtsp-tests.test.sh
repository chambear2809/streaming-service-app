#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DIRECT_SCRIPT="${REPO_ROOT}/scripts/thousandeyes/create-rtsp-tests.sh"
IN_CLUSTER_SCRIPT="${REPO_ROOT}/scripts/thousandeyes/create-rtsp-tests-in-cluster.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

assert_equals() {
  local actual="$1"
  local expected="$2"
  local message="$3"

  [[ "${actual}" == "${expected}" ]] || fail "${message}: expected ${expected}, got ${actual}"
}

count_fixed_occurrences() {
  local haystack="$1"
  local needle="$2"
  local matches

  matches="$(printf '%s' "${haystack}" | grep -oF -- "${needle}" || true)"
  if [[ -z "${matches}" ]]; then
    printf '0'
    return 0
  fi

  printf '%s\n' "${matches}" | wc -l | tr -d ' '
}

temp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${temp_dir}"
}
trap cleanup EXIT

empty_env="${temp_dir}/empty.env"
touch "${empty_env}"

direct_output="$(
  ENV_FILE="${empty_env}" \
  THOUSANDEYES_DRY_RUN=true \
  THOUSANDEYES_BEARER_TOKEN=test-token \
  THOUSANDEYES_ACCOUNT_GROUP_ID=1234 \
  TE_SOURCE_AGENT_IDS=111 \
  TE_TARGET_AGENT_ID=222 \
  TE_UDP_TARGET_AGENT_ID=333 \
  TE_RTSP_SERVER=rtsp.example.com \
  TE_RTSP_PORT=8554 \
  TE_TRACE_MAP_TEST_URL=https://demo.example.com/api/v1/demo/public/trace-map \
  TE_BROADCAST_TEST_URL=https://demo.example.com/api/v1/demo/public/broadcast/live/index.m3u8 \
  bash "${DIRECT_SCRIPT}" create-all
)"

assert_contains "${direct_output}" "\"testName\":\"RTSP-TCP-8554\""
assert_contains "${direct_output}" "\"testName\":\"UDP-Media-Path\""
assert_contains "${direct_output}" "\"testName\":\"RTP-Stream-Proxy\""
assert_contains "${direct_output}" "\"testName\":\"aleccham-broadcast-trace-map\""
assert_contains "${direct_output}" "\"testName\":\"aleccham-broadcast-playback\""
assert_equals \
  "$(count_fixed_occurrences "${direct_output}" "\"alertsEnabled\":true")" \
  "5" \
  "direct create-all should enable alerts for all five tests by default"

override_output="$(
  ENV_FILE="${empty_env}" \
  THOUSANDEYES_DRY_RUN=true \
  THOUSANDEYES_BEARER_TOKEN=test-token \
  TE_ALERTS_ENABLED=false \
  TE_TRACE_MAP_ALERTS_ENABLED=true \
  TE_SOURCE_AGENT_IDS=111 \
  TE_TARGET_AGENT_ID=222 \
  TE_TRACE_MAP_TEST_URL=https://demo.example.com/api/v1/demo/public/trace-map \
  TE_BROADCAST_TEST_URL=https://demo.example.com/api/v1/demo/public/broadcast/live/index.m3u8 \
  bash "${DIRECT_SCRIPT}" create-demo-monkey-http
)"

assert_equals \
  "$(count_fixed_occurrences "${override_output}" "\"alertsEnabled\":true")" \
  "1" \
  "per-test alert override should keep only trace-map enabled"
assert_equals \
  "$(count_fixed_occurrences "${override_output}" "\"alertsEnabled\":false")" \
  "1" \
  "shared alert default should be overridable for the remaining HTTP test"

stub_dir="${temp_dir}/bin"
capture_dir="${temp_dir}/captures"
mkdir -p "${stub_dir}" "${capture_dir}"

cat > "${stub_dir}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

response_file=""
payload=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -o)
      response_file="$2"
      shift 2
      ;;
    --data)
      payload="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

counter_file="${THOUSANDEYES_TEST_CAPTURE_DIR}/counter"
counter=0
if [[ -f "${counter_file}" ]]; then
  counter="$(cat "${counter_file}")"
fi
counter=$((counter + 1))
printf '%s' "${counter}" > "${counter_file}"
printf '%s' "${payload}" > "${THOUSANDEYES_TEST_CAPTURE_DIR}/payload-${counter}.json"
printf '{"testId":"%s"}\n' "${counter}" > "${response_file}"
printf '201'
EOF
chmod +x "${stub_dir}/curl"

PATH="${stub_dir}:${PATH}" \
THOUSANDEYES_TEST_CAPTURE_DIR="${capture_dir}" \
THOUSANDEYES_BEARER_TOKEN=test-token \
THOUSANDEYES_ACCOUNT_GROUP_ID=1234 \
TE_SOURCE_AGENT_IDS=111 \
TE_TARGET_AGENT_ID=222 \
TE_UDP_TARGET_AGENT_ID=333 \
TE_RTSP_SERVER=rtsp.example.com \
TE_RTSP_PORT=8554 \
TE_TRACE_MAP_TEST_URL=https://demo.example.com/api/v1/demo/public/trace-map \
TE_BROADCAST_TEST_URL=https://demo.example.com/api/v1/demo/public/broadcast/live/index.m3u8 \
sh "${IN_CLUSTER_SCRIPT}" create-all >/dev/null

payload_count="$(find "${capture_dir}" -name 'payload-*.json' | wc -l | tr -d ' ')"
assert_equals "${payload_count}" "5" "in-cluster create-all should submit all five ThousandEyes tests"

combined_payloads="$(cat "${capture_dir}"/payload-*.json)"
assert_contains "${combined_payloads}" "\"testName\":\"aleccham-broadcast-trace-map\""
assert_contains "${combined_payloads}" "\"testName\":\"aleccham-broadcast-playback\""
assert_equals \
  "$(count_fixed_occurrences "${combined_payloads}" "\"alertsEnabled\":true")" \
  "5" \
  "in-cluster payloads should enable alerts for all five tests by default"

printf 'PASS: ThousandEyes alert defaults and create-all coverage regression test\n'
