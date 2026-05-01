#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/scripts/thousandeyes/deploy-enterprise-agent.sh"

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

rendered="$(
  ENV_FILE="${empty_env}" \
  bash "${TARGET_SCRIPT}" \
    --namespace te-demo \
    --name thousandeyes-private \
    --hostname te-agent-streaming-private
)"

assert_contains "${rendered}" 'name: thousandeyes-private'
assert_contains "${rendered}" 'hostname: te-agent-streaming-private'
assert_contains "${rendered}" 'name: TEAGENT_ACCOUNT_TOKEN'
assert_contains "${rendered}" 'name: TEAGENT_INET'
assert_contains "${rendered}" 'value: "4"'

stub_dir="${temp_dir}/bin"
mkdir -p "${stub_dir}"

cat > "${stub_dir}/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${KUBECTL_LOG_FILE:-}" ]]; then
  printf '%s\n' "$*" >> "${KUBECTL_LOG_FILE}"
fi

if [[ "${1-}" == "create" && "${2-}" == "namespace" ]]; then
  cat <<YAML
apiVersion: v1
kind: Namespace
metadata:
  name: ${3}
YAML
  exit 0
fi

if [[ "${1-}" == "-n" ]]; then
  shift 2
fi

if [[ "${1-}" == "create" && "${2-}" == "secret" ]]; then
  cat <<YAML
apiVersion: v1
kind: Secret
metadata:
  name: ${3}
YAML
  exit 0
fi

if [[ "${1-}" == "apply" ]]; then
  cat >/dev/null
  exit 0
fi

if [[ "${1-}" == "rollout" && "${2-}" == "status" ]]; then
  printf 'deployment/%s successfully rolled out\n' "${3#deployment/}"
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "secret" ]]; then
  exit 0
fi

exit 0
EOF
chmod +x "${stub_dir}/kubectl"

cat > "${stub_dir}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cat <<JSON
{
  "agents": [
    {
      "hostname": "te-agent-streaming-private",
      "agentId": 777
    }
  ]
}
JSON
EOF
chmod +x "${stub_dir}/curl"

env \
  PATH="${stub_dir}:${PATH}" \
  ENV_FILE="${empty_env}" \
  TEAGENT_ACCOUNT_TOKEN='raw-agent-token' \
  bash "${TARGET_SCRIPT}" \
    --apply \
    --wait \
    >/dev/null 2>&1 || fail "deploy-enterprise-agent apply flow failed"

resolved_output="$(
  env \
    PATH="${stub_dir}:${PATH}" \
    ENV_FILE="${empty_env}" \
    TEAGENT_ACCOUNT_TOKEN='raw-agent-token' \
    THOUSANDEYES_BEARER_TOKEN='demo-token' \
    bash "${TARGET_SCRIPT}" \
      --apply \
      --wait \
      --resolve-agent-id
)"

[[ "${resolved_output}" == 'TE_MEDIA_SOURCE_AGENT_IDS=777' ]] || \
  fail "expected clean TE_MEDIA_SOURCE_AGENT_IDS output, got: ${resolved_output}"

default_env="${temp_dir}/default.env"
override_env="${temp_dir}/override.env"
printf 'TEAGENT_ACCOUNT_TOKEN=default-token\n' > "${default_env}"
printf 'TEAGENT_ACCOUNT_TOKEN=override-token\n' > "${override_env}"
: > "${temp_dir}/kubectl.log"

env \
  PATH="${stub_dir}:${PATH}" \
  ENV_FILE="${default_env}" \
  KUBECTL_LOG_FILE="${temp_dir}/kubectl.log" \
  bash "${TARGET_SCRIPT}" \
    --env-file "${override_env}" \
    --apply \
    >/dev/null 2>&1 || fail "deploy-enterprise-agent env-file override flow failed"

grep -Fq 'TEAGENT_ACCOUNT_TOKEN=override-token' "${temp_dir}/kubectl.log" || \
  fail "expected --env-file to override the default env file token"

printf 'PASS: ThousandEyes Enterprise Agent deploy helper regression test\n'
