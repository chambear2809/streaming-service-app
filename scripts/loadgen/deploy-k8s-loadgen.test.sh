#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OPERATOR_SCRIPT="${REPO_ROOT}/scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh"
BROADCAST_SCRIPT="${REPO_ROOT}/scripts/loadgen/deploy-k8s-broadcast-loadgen.sh"
TEMP_DIR="$(mktemp -d)"
STUB_DIR="${TEMP_DIR}/bin"
APPLY_LOG="${TEMP_DIR}/apply.log"
OUTPUT_FILE="${TEMP_DIR}/output.txt"
EMPTY_ENV_FILE="${TEMP_DIR}/empty.env"

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

mkdir -p "${STUB_DIR}"

cat > "${STUB_DIR}/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1-}" == "-n" ]]; then
  namespace="$2"
  shift 2
else
  namespace=""
fi

if [[ "${1-}" == "get" && "${2-}" == "service" ]]; then
  printf '80'
  exit 0
fi

if [[ "${1-}" == "create" && "${2-}" == "configmap" ]]; then
  cat <<YAML
apiVersion: v1
kind: ConfigMap
metadata:
  name: ${3}
YAML
  exit 0
fi

if [[ "${1-}" == "apply" ]]; then
  cat >> "${KUBECTL_APPLY_LOG}"
  exit 0
fi

if [[ "${1-}" == "delete" ]]; then
  exit 0
fi

if [[ "${1-}" == "wait" ]]; then
  if [[ "${KUBECTL_WAIT_FAIL:-false}" == "true" ]]; then
    printf 'job wait failed\n' >&2
    exit 1
  fi
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "job" ]]; then
  printf 'NAME   COMPLETIONS   DURATION\nstub-job   0/1   0s\n'
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "pod" ]]; then
  printf 'NAME      READY   STATUS\nstub-pod   0/1   Error\n'
  exit 0
fi

if [[ "${1-}" == "logs" ]]; then
  printf 'stub logs for %s\n' "$*"
  exit 0
fi

exit 0
EOF

chmod +x "${STUB_DIR}/kubectl"

: > "${APPLY_LOG}"
: > "${EMPTY_ENV_FILE}"
env \
  PATH="${STUB_DIR}:${PATH}" \
  ENV_FILE="${EMPTY_ENV_FILE}" \
  KUBECTL_APPLY_LOG="${APPLY_LOG}" \
  K8S_DRY_RUN=true \
  LOADGEN_OPERATOR_PROFILE=booth \
  zsh "${OPERATOR_SCRIPT}" \
  > "${OUTPUT_FILE}" 2>&1

apply_payload="$(cat "${APPLY_LOG}")"
assert_contains "${apply_payload}" 'name: LOADGEN_OPERATOR_TAKE_LIVE_RATIO'
assert_contains "${apply_payload}" 'value: "0.00"'

: > "${APPLY_LOG}"
: > "${OUTPUT_FILE}"
env \
  PATH="${STUB_DIR}:${PATH}" \
  ENV_FILE="${EMPTY_ENV_FILE}" \
  KUBECTL_APPLY_LOG="${APPLY_LOG}" \
  K8S_DRY_RUN=true \
  LOADGEN_K8S_MODE=cronjob \
  LOADGEN_PROFILE=booth \
  zsh "${BROADCAST_SCRIPT}" \
  > "${OUTPUT_FILE}" 2>&1

apply_payload="$(cat "${APPLY_LOG}")"
assert_contains "${apply_payload}" 'schedule: "*/10 * * * *"'
assert_contains "${apply_payload}" 'concurrencyPolicy: Allow'

: > "${APPLY_LOG}"
: > "${OUTPUT_FILE}"
env \
  PATH="${STUB_DIR}:${PATH}" \
  ENV_FILE="${EMPTY_ENV_FILE}" \
  KUBECTL_APPLY_LOG="${APPLY_LOG}" \
  K8S_DRY_RUN=true \
  LOADGEN_OPERATOR_K8S_MODE=cronjob \
  LOADGEN_OPERATOR_PROFILE=booth \
  zsh "${OPERATOR_SCRIPT}" \
  > "${OUTPUT_FILE}" 2>&1

apply_payload="$(cat "${APPLY_LOG}")"
assert_contains "${apply_payload}" 'schedule: "*/8 * * * *"'
assert_contains "${apply_payload}" 'concurrencyPolicy: Allow'

: > "${OUTPUT_FILE}"
set +e
env \
  PATH="${STUB_DIR}:${PATH}" \
  KUBECTL_APPLY_LOG="${APPLY_LOG}" \
  KUBECTL_WAIT_FAIL=true \
  zsh "${BROADCAST_SCRIPT}" \
  > "${OUTPUT_FILE}" 2>&1
status=$?
set -e

[[ ${status} -ne 0 ]] || fail "expected broadcast loadgen wrapper to fail when kubectl wait fails"

wrapper_output="$(cat "${OUTPUT_FILE}")"
assert_contains "${wrapper_output}" 'NAME   COMPLETIONS   DURATION'
assert_contains "${wrapper_output}" 'NAME      READY   STATUS'
assert_contains "${wrapper_output}" 'stub logs for logs job/broadcast-loadgen --all-containers=true'

printf 'PASS: loadgen wrapper defaults and failure logging\n'
