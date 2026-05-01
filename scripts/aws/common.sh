#!/usr/bin/env bash

set -euo pipefail

AWS_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_REPO_ROOT="$(cd "${AWS_SCRIPT_DIR}/../.." && pwd)"

aws_log() {
  printf '[eks-delay-demo] %s\n' "$*"
}

aws_warn() {
  printf '[eks-delay-demo] WARN: %s\n' "$*" >&2
}

aws_fail() {
  printf '[eks-delay-demo] ERROR: %s\n' "$*" >&2
  exit 1
}

load_repo_env_file() {
  local env_file="$1"
  local line normalized key value

  [[ -f "${env_file}" ]] || return 0

  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"

    [[ -z "${line}" || "${line}" == \#* ]] && continue

    normalized="${line}"
    [[ "${normalized}" == export\ * ]] && normalized="${normalized#export }"
    [[ "${normalized}" == *=* ]] || continue

    key="${normalized%%=*}"
    value="${normalized#*=}"

    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"

    if [[ "${key}" != [A-Za-z_][A-Za-z0-9_]* ]]; then
      continue
    fi

    if [[ -n "${!key+x}" ]]; then
      continue
    fi

    if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi

    export "${key}=${value}"
  done < "${env_file}"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || aws_fail "required command '$1' is not installed"
}

require_value() {
  local name="$1"
  local value="${2:-}"
  [[ -n "${value}" ]] || aws_fail "${name} must be set"
}

aws_cli() {
  if [[ -n "${AWS_PROFILE:-}" ]]; then
    aws --profile "${AWS_PROFILE}" "$@"
  else
    aws "$@"
  fi
}

aws_region_cli() {
  if [[ -n "${AWS_PROFILE:-}" ]]; then
    aws --profile "${AWS_PROFILE}" --region "${AWS_REGION}" "$@"
  else
    aws --region "${AWS_REGION}" "$@"
  fi
}

ensure_generated_dir() {
  mkdir -p "${AWS_REPO_ROOT}/.generated/aws"
}

clear_state_file() {
  local state_file="$1"
  ensure_generated_dir
  : > "${state_file}"
}

write_state_value() {
  local state_file="$1"
  local key="$2"
  local value="${3-}"

  printf '%s=%q\n' "${key}" "${value}" >> "${state_file}"
}

load_state_file() {
  local state_file="$1"
  [[ -f "${state_file}" ]] || aws_fail "state file not found: ${state_file}"
  # shellcheck disable=SC1090
  source "${state_file}"
}

ssm_wait_for_instance() {
  local instance_id="$1"
  local timeout_seconds="${2:-600}"
  local elapsed=0
  local ping_status=""
  local info_count=""

  while (( elapsed < timeout_seconds )); do
    info_count="$(
      aws_region_cli ssm describe-instance-information \
        --filters "Key=InstanceIds,Values=${instance_id}" \
        --query 'length(InstanceInformationList)' \
        --output text 2>/dev/null || true
    )"

    if [[ "${info_count}" == "1" ]]; then
      ping_status="$(
        aws_region_cli ssm describe-instance-information \
          --filters "Key=InstanceIds,Values=${instance_id}" \
          --query 'InstanceInformationList[0].PingStatus' \
          --output text 2>/dev/null || true
      )"
      if [[ "${ping_status}" == "Online" ]]; then
        return 0
      fi
    fi

    sleep 10
    elapsed=$((elapsed + 10))
  done

  aws_fail "timed out waiting for router instance ${instance_id} to become available in SSM"
}

run_ssm_script() {
  local instance_id="$1"
  local commands="$2"
  local command_id=""
  local status=""
  local output=""
  local elapsed=0
  local timeout_seconds="${3:-600}"
  local parameters_json=""

  parameters_json="$(
    python3 - "${commands}" <<'PY'
import json
import sys

raw = sys.argv[1]
lines = [line for line in raw.splitlines() if line.strip()]
print(json.dumps({"commands": lines}))
PY
  )"

  command_id="$(
    aws_region_cli ssm send-command \
      --instance-ids "${instance_id}" \
      --document-name AWS-RunShellScript \
      --comment "streaming-service-app eks delay demo" \
      --parameters "${parameters_json}" \
      --query 'Command.CommandId' \
      --output text
  )"

  while (( elapsed < timeout_seconds )); do
    status="$(
      aws_region_cli ssm list-command-invocations \
        --command-id "${command_id}" \
        --details \
        --query 'CommandInvocations[0].Status' \
        --output text 2>/dev/null || true
    )"

    case "${status}" in
      Success)
        aws_region_cli ssm list-command-invocations \
          --command-id "${command_id}" \
          --details \
          --query 'CommandInvocations[0].CommandPlugins[0].Output' \
          --output text
        return 0
        ;;
      Failed|Cancelled|Cancelling|TimedOut)
        output="$(
          aws_region_cli ssm list-command-invocations \
            --command-id "${command_id}" \
            --details \
            --query 'CommandInvocations[0].CommandPlugins[0].Output' \
            --output text 2>/dev/null || true
        )"
        aws_fail "SSM command failed with status ${status}: ${output}"
        ;;
    esac

    sleep 5
    elapsed=$((elapsed + 5))
  done

  aws_fail "timed out waiting for SSM command ${command_id}"
}
