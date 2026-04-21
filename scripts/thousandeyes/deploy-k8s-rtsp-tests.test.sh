#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WRAPPER_SCRIPT="${REPO_ROOT}/scripts/thousandeyes/deploy-k8s-rtsp-tests.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

temp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${temp_dir}"
}
trap cleanup EXIT

empty_env="${temp_dir}/empty.env"
touch "${empty_env}"

stub_dir="${temp_dir}/bin"
mkdir -p "${stub_dir}"

cat > "${stub_dir}/kubectl" <<'EOF'
#!/usr/bin/env bash

set -euo pipefail

if [[ "${1:-}" == "-n" ]]; then
  namespace="$2"
  shift 2
fi

if [[ "${1:-}" == "get" && "${2:-}" == "service" ]]; then
  service="$3"
  joined="$*"

  case "${joined}" in
    *"jsonpath={.status.loadBalancer.ingress[0].hostname}"*)
      if [[ "${service}" == "media-service-demo-rtsp" ]]; then
        printf 'rtsp.example.com'
        exit 0
      fi
      ;;
    *"jsonpath={.spec.ports[?(@.name==\"rtsp\")].port}"*)
      printf '8554'
      exit 0
      ;;
    *"jsonpath={.spec.ports[?(@.name==\"http\")].port}"*)
      printf '80'
      exit 0
      ;;
    *"jsonpath={.spec.ports[0].port}"*)
      if [[ "${service}" == "streaming-frontend" ]]; then
        printf '80'
      else
        printf '8554'
      fi
      exit 0
      ;;
  esac
fi

if [[ "${1:-}" == "create" && "${2:-}" == "secret" ]]; then
  printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: stub-secret\n'
  exit 0
fi

if [[ "${1:-}" == "create" && "${2:-}" == "configmap" ]]; then
  printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: stub-configmap\n'
  exit 0
fi

if [[ "${1:-}" == "apply" ]]; then
  cat
  exit 0
fi

if [[ "${1:-}" == "delete" || "${1:-}" == "wait" || "${1:-}" == "logs" ]]; then
  exit 0
fi

printf 'unexpected kubectl invocation: %s\n' "$*" >&2
exit 1
EOF
chmod +x "${stub_dir}/kubectl"

http_only_output="$(
  PATH="${stub_dir}:${PATH}" \
  ENV_FILE="${empty_env}" \
  K8S_DRY_RUN=true \
  THOUSANDEYES_BEARER_TOKEN=test-token \
  THOUSANDEYES_JOB_ACTION=create-demo-monkey-http \
  TE_APP_SOURCE_AGENT_IDS=111 \
  zsh "${WRAPPER_SCRIPT}"
)"

assert_contains "${http_only_output}" "- create-demo-monkey-http"
assert_contains "${http_only_output}" 'name: TE_APP_SOURCE_AGENT_IDS'
assert_contains "${http_only_output}" 'value: "111"'

full_output="$(
  PATH="${stub_dir}:${PATH}" \
  ENV_FILE="${empty_env}" \
  K8S_DRY_RUN=true \
  THOUSANDEYES_BEARER_TOKEN=test-token \
  THOUSANDEYES_JOB_ACTION=create-all \
  TE_APP_SOURCE_AGENT_IDS=111 \
  TE_MEDIA_SOURCE_AGENT_IDS=222 \
  TE_TARGET_AGENT_ID=333 \
  TE_RTSP_TCP_ENABLED=false \
  TE_UDP_MEDIA_ENABLED=false \
  TE_RTP_STREAM_ENABLED=false \
  TE_TRACE_MAP_ENABLED=false \
  TE_BROADCAST_ENABLED=false \
  TE_RTSP_TCP_TEST_ID=1001 \
  TE_UDP_MEDIA_TEST_ID=1002 \
  TE_RTP_STREAM_TEST_ID=1003 \
  TE_TRACE_MAP_TEST_ID=1004 \
  TE_BROADCAST_TEST_ID=1005 \
  zsh "${WRAPPER_SCRIPT}"
)"

assert_contains "${full_output}" $'name: TE_RTSP_TCP_ENABLED\n              value: "false"'
assert_contains "${full_output}" $'name: TE_UDP_MEDIA_ENABLED\n              value: "false"'
assert_contains "${full_output}" $'name: TE_RTP_STREAM_ENABLED\n              value: "false"'
assert_contains "${full_output}" $'name: TE_TRACE_MAP_ENABLED\n              value: "false"'
assert_contains "${full_output}" $'name: TE_BROADCAST_ENABLED\n              value: "false"'
assert_contains "${full_output}" $'name: TE_RTSP_TCP_TEST_ID\n              value: "1001"'
assert_contains "${full_output}" $'name: TE_UDP_MEDIA_TEST_ID\n              value: "1002"'
assert_contains "${full_output}" $'name: TE_RTP_STREAM_TEST_ID\n              value: "1003"'
assert_contains "${full_output}" $'name: TE_TRACE_MAP_TEST_ID\n              value: "1004"'
assert_contains "${full_output}" $'name: TE_BROADCAST_TEST_ID\n              value: "1005"'

printf 'PASS: ThousandEyes Kubernetes wrapper regression test\n'
