#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

AWS_REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="${CLUSTER_NAME:-streaming-eks-delay-demo}"
STATE_FILE="${STATE_FILE:-${AWS_REPO_ROOT}/.generated/aws/${CLUSTER_NAME}.env}"
ACTION="${1:-}"
DELAY_MS=""
JITTER_MS="0"
LOSS_PCT="0"

usage() {
  cat <<'EOF'
Usage:
  router-delay.sh status [--state-file <path>]
  router-delay.sh enable --delay-ms <n> [--jitter-ms <n>] [--loss-pct <n>] [--state-file <path>]
  router-delay.sh disable [--state-file <path>]
EOF
}

parse_args() {
  shift || true
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --delay-ms)
        DELAY_MS="${2:?missing value for --delay-ms}"
        shift 2
        ;;
      --jitter-ms)
        JITTER_MS="${2:?missing value for --jitter-ms}"
        shift 2
        ;;
      --loss-pct)
        LOSS_PCT="${2:?missing value for --loss-pct}"
        shift 2
        ;;
      --state-file)
        STATE_FILE="${2:?missing value for --state-file}"
        shift 2
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        aws_fail "unknown option: $1"
        ;;
    esac
  done
}

build_status_script() {
  cat <<'EOF'
set -euo pipefail
public_dev="$(ip route show default | awk '/default/ {print $5; exit}')"
private_dev="$(ip -o link show | awk -F': ' '{print $2}' | grep -v '^lo$' | grep -vx "${public_dev}" | head -n1 || true)"
printf 'public=%s\n' "${public_dev}"
printf 'private=%s\n' "${private_dev}"
tc qdisc show dev "${public_dev}" || true
if [[ -n "${private_dev}" ]]; then
  tc qdisc show dev "${private_dev}" || true
fi
EOF
}

build_enable_script() {
  cat <<EOF
set -euo pipefail
public_dev="\$(ip route show default | awk '/default/ {print \$5; exit}')"
private_dev="\$(ip -o link show | awk -F': ' '{print \$2}' | grep -v '^lo\$' | grep -vx "\${public_dev}" | head -n1 || true)"
for dev in "\${public_dev}" "\${private_dev}"; do
  [[ -n "\${dev}" ]] || continue
  tc qdisc replace dev "\${dev}" root netem delay ${DELAY_MS}ms ${JITTER_MS}ms loss ${LOSS_PCT}%
done
tc qdisc show dev "\${public_dev}" || true
if [[ -n "\${private_dev}" ]]; then
  tc qdisc show dev "\${private_dev}" || true
fi
EOF
}

build_disable_script() {
  cat <<'EOF'
set -euo pipefail
public_dev="$(ip route show default | awk '/default/ {print $5; exit}')"
private_dev="$(ip -o link show | awk -F': ' '{print $2}' | grep -v '^lo$' | grep -vx "${public_dev}" | head -n1 || true)"
for dev in "${public_dev}" "${private_dev}"; do
  [[ -n "${dev}" ]] || continue
  tc qdisc del dev "${dev}" root 2>/dev/null || true
done
tc qdisc show dev "${public_dev}" || true
if [[ -n "${private_dev}" ]]; then
  tc qdisc show dev "${private_dev}" || true
fi
EOF
}

[[ -n "${ACTION}" ]] || {
  usage >&2
  exit 1
}

parse_args "$@"
load_state_file "${STATE_FILE}"
require_value "ROUTER_INSTANCE_ID" "${ROUTER_INSTANCE_ID:-}"

case "${ACTION}" in
  status)
    run_ssm_script "${ROUTER_INSTANCE_ID}" "$(build_status_script)"
    ;;
  enable)
    require_value "delay-ms" "${DELAY_MS}"
    run_ssm_script "${ROUTER_INSTANCE_ID}" "$(build_enable_script)"
    ;;
  disable)
    run_ssm_script "${ROUTER_INSTANCE_ID}" "$(build_disable_script)"
    ;;
  --help|-h|help)
    usage
    ;;
  *)
    aws_fail "unsupported action: ${ACTION}"
    ;;
esac
