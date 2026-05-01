#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

AWS_REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="${CLUSTER_NAME:-streaming-eks-delay-demo}"
STATE_FILE="${STATE_FILE:-${AWS_REPO_ROOT}/.generated/aws/${CLUSTER_NAME}.env}"
ROUTER_DELAY_CLI="${ROUTER_DELAY_CLI:-${SCRIPT_DIR}/router-delay.sh}"
CURL_BIN="${CURL_BIN:-curl}"
ACTION="${1:-}"
DELAY_MS=""
JITTER_MS="0"
LOSS_PCT="0"
SAMPLES="${SAMPLES:-5}"
EXPECTED_MIN_INCREASE_MS=""
EXPECTED_MIN_JITTER_RANGE_INCREASE_MS=""
EXPECTED_MIN_FAILURE_RATE_PCT=""
EXPECTED_MIN_LOSS_RANGE_INCREASE_MS=""
PROBE_URL=""
CURL_CONNECT_TIMEOUT="${CURL_CONNECT_TIMEOUT:-5}"
CURL_MAX_TIME="${CURL_MAX_TIME:-20}"
SETTLE_SECONDS="${SETTLE_SECONDS:-2}"
KEEP_DELAY="${KEEP_DELAY:-false}"
TEMP_DIR=""

usage() {
  cat <<'EOF'
Usage:
  validate-router-delay.sh validate --delay-ms <n> [options]

Options:
  --delay-ms <n>                     Required fixed delay to apply during the test
  --jitter-ms <n>                    Optional jitter to apply during the test. Default: 0
  --loss-pct <n>                     Optional packet loss percentage. Default: 0
  --samples <n>                      Number of samples per phase. Default: 5
  --expected-min-increase-ms <n>     Minimum median TTFB increase required to pass.
                                     Default: delay-ms
  --expected-min-jitter-range-increase-ms <n>
                                     Minimum increase in successful-sample TTFB range
                                     required when jitter is enabled. Default: jitter-ms/2
  --expected-min-failure-rate-pct <n>
                                     Minimum impaired-phase probe failure rate required
                                     when packet loss is enabled. Optional.
  --expected-min-loss-range-increase-ms <n>
                                     Minimum increase in successful-sample TTFB range
                                     required when packet loss is enabled. Default: delay-ms
  --probe-url <url>                  Override the frontend URL. Default: http://ROUTER_PUBLIC_IP/
  --connect-timeout <seconds>        Curl connect timeout. Default: 5
  --max-time <seconds>               Curl max time. Default: 20
  --settle-seconds <seconds>         Wait after delay toggles. Default: 2
  --keep-delay                       Leave the configured delay in place after validation
  --state-file <path>                Generated state file path
  --help
EOF
}

cleanup() {
  if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
    rm -rf "${TEMP_DIR}"
  fi

  if [[ "${KEEP_DELAY}" != "true" && "${ACTION}" == "validate" ]]; then
    "${ROUTER_DELAY_CLI}" disable --state-file "${STATE_FILE}" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT

require_positive_integer() {
  local name="$1"
  local value="$2"

  [[ "${value}" =~ ^[0-9]+$ ]] || aws_fail "${name} must be a non-negative integer"
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
      --samples)
        SAMPLES="${2:?missing value for --samples}"
        shift 2
        ;;
      --expected-min-increase-ms)
        EXPECTED_MIN_INCREASE_MS="${2:?missing value for --expected-min-increase-ms}"
        shift 2
        ;;
      --expected-min-jitter-range-increase-ms)
        EXPECTED_MIN_JITTER_RANGE_INCREASE_MS="${2:?missing value for --expected-min-jitter-range-increase-ms}"
        shift 2
        ;;
      --expected-min-failure-rate-pct)
        EXPECTED_MIN_FAILURE_RATE_PCT="${2:?missing value for --expected-min-failure-rate-pct}"
        shift 2
        ;;
      --expected-min-loss-range-increase-ms)
        EXPECTED_MIN_LOSS_RANGE_INCREASE_MS="${2:?missing value for --expected-min-loss-range-increase-ms}"
        shift 2
        ;;
      --probe-url)
        PROBE_URL="${2:?missing value for --probe-url}"
        shift 2
        ;;
      --connect-timeout)
        CURL_CONNECT_TIMEOUT="${2:?missing value for --connect-timeout}"
        shift 2
        ;;
      --max-time)
        CURL_MAX_TIME="${2:?missing value for --max-time}"
        shift 2
        ;;
      --settle-seconds)
        SETTLE_SECONDS="${2:?missing value for --settle-seconds}"
        shift 2
        ;;
      --keep-delay)
        KEEP_DELAY="true"
        shift
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

probe_url() {
  require_command "${CURL_BIN}"
  "${CURL_BIN}" \
    -fsS \
    -o /dev/null \
    -H 'Cache-Control: no-cache' \
    -H 'Connection: close' \
    --connect-timeout "${CURL_CONNECT_TIMEOUT}" \
    --max-time "${CURL_MAX_TIME}" \
    -w '%{time_starttransfer}\n' \
    "${PROBE_URL}"
}

probe_url_ms() {
  local raw_seconds=""

  if ! raw_seconds="$(probe_url)"; then
    return 1
  fi
  awk -v seconds="${raw_seconds}" 'BEGIN { printf "%.3f\n", seconds * 1000 }'
}

numeric_values_from_results() {
  local results_file="$1"

  awk '$1 == "success" { print $2 }' "${results_file}"
}

median_from_results() {
  local results_file="$1"

  numeric_values_from_results "${results_file}" | sort -n | awk '
    { values[++count] = $1 }
    END {
      if (count == 0) {
        exit 1
      }
      if (count % 2 == 1) {
        printf "%.3f\n", values[(count + 1) / 2]
      } else {
        printf "%.3f\n", (values[count / 2] + values[(count / 2) + 1]) / 2
      }
    }
  '
}

range_from_results() {
  local results_file="$1"

  numeric_values_from_results "${results_file}" | awk '
    NR == 1 {
      min = $1
      max = $1
      next
    }
    {
      if ($1 < min) {
        min = $1
      }
      if ($1 > max) {
        max = $1
      }
    }
    END {
      if (NR == 0) {
        exit 1
      }
      printf "%.3f\n", max - min
    }
  '
}

success_count_from_results() {
  local results_file="$1"
  awk '$1 == "success" { count++ } END { print count + 0 }' "${results_file}"
}

failure_count_from_results() {
  local results_file="$1"
  awk '$1 == "failure" { count++ } END { print count + 0 }' "${results_file}"
}

failure_rate_pct_from_results() {
  local results_file="$1"
  local failure_count=""

  failure_count="$(failure_count_from_results "${results_file}")"
  awk -v failures="${failure_count}" -v samples="${SAMPLES}" '
    BEGIN {
      if (samples == 0) {
        exit 1
      }
      printf "%.3f\n", (failures * 100) / samples
    }
  '
}

collect_phase_samples() {
  local phase_name="$1"
  local results_file="$2"
  local sample_index=""
  local sample_ms=""

  : > "${results_file}"
  for sample_index in $(seq 1 "${SAMPLES}"); do
    if sample_ms="$(probe_url_ms 2>/dev/null)"; then
      printf 'success\t%s\n' "${sample_ms}" >> "${results_file}"
      aws_log "${phase_name} sample ${sample_index}/${SAMPLES}: ${sample_ms} ms"
    else
      printf 'failure\t-\n' >> "${results_file}"
      aws_log "${phase_name} sample ${sample_index}/${SAMPLES}: failure"
    fi
  done
}

router_delay_cmd() {
  bash "${ROUTER_DELAY_CLI}" "$@" --state-file "${STATE_FILE}"
}

assert_delay_present() {
  local status_output="$1"

  printf '%s\n' "${status_output}" | grep -Eq "qdisc netem .* delay ${DELAY_MS}(\\.[0-9]+)?ms" \
    || aws_fail "router qdisc output did not show the expected delay ${DELAY_MS}ms"
}

assert_jitter_present() {
  local status_output="$1"

  [[ "${JITTER_MS}" == "0" ]] && return 0

  printf '%s\n' "${status_output}" | grep -Eq "qdisc netem .* delay ${DELAY_MS}(\\.[0-9]+)?ms +${JITTER_MS}(\\.[0-9]+)?ms" \
    || aws_fail "router qdisc output did not show the expected jitter ${JITTER_MS}ms"
}

assert_loss_present() {
  local status_output="$1"

  [[ "${LOSS_PCT}" == "0" ]] && return 0

  printf '%s\n' "${status_output}" | grep -Eq "qdisc netem .* loss ${LOSS_PCT}(\\.[0-9]+)?%" \
    || aws_fail "router qdisc output did not show the expected loss ${LOSS_PCT}%"
}

assert_no_failures() {
  local phase_name="$1"
  local results_file="$2"
  local failure_count=""

  failure_count="$(failure_count_from_results "${results_file}")"
  [[ "${failure_count}" == "0" ]] || aws_fail "${phase_name} phase saw ${failure_count} failed probes when no loss was expected"
}

validate_impairment() {
  local baseline_file="${TEMP_DIR}/baseline.txt"
  local impaired_file="${TEMP_DIR}/impaired.txt"
  local restored_file="${TEMP_DIR}/restored.txt"
  local baseline_median=""
  local impaired_median=""
  local restored_median=""
  local increase_ms=""
  local baseline_range=""
  local impaired_range=""
  local restored_range=""
  local range_increase_ms=""
  local baseline_failure_rate=""
  local impaired_failure_rate=""
  local restored_failure_rate=""
  local status_output=""

  aws_log "Resetting router delay to build a clean baseline"
  router_delay_cmd disable >/dev/null
  sleep "${SETTLE_SECONDS}"
  collect_phase_samples baseline "${baseline_file}"
  assert_no_failures baseline "${baseline_file}"
  baseline_median="$(median_from_results "${baseline_file}")"
  baseline_range="$(range_from_results "${baseline_file}")"

  aws_log "Applying router delay: ${DELAY_MS}ms delay, ${JITTER_MS}ms jitter, ${LOSS_PCT}% loss"
  router_delay_cmd enable --delay-ms "${DELAY_MS}" --jitter-ms "${JITTER_MS}" --loss-pct "${LOSS_PCT}" >/dev/null
  sleep "${SETTLE_SECONDS}"
  status_output="$(router_delay_cmd status)"
  assert_delay_present "${status_output}"
  assert_jitter_present "${status_output}"
  assert_loss_present "${status_output}"
  collect_phase_samples impaired "${impaired_file}"
  impaired_median="$(median_from_results "${impaired_file}")"
  impaired_range="$(range_from_results "${impaired_file}")"

  aws_log "Disabling router delay to verify the path returns to baseline"
  router_delay_cmd disable >/dev/null
  sleep "${SETTLE_SECONDS}"
  collect_phase_samples restored "${restored_file}"
  assert_no_failures restored "${restored_file}"
  restored_median="$(median_from_results "${restored_file}")"
  restored_range="$(range_from_results "${restored_file}")"

  increase_ms="$(awk -v impaired="${impaired_median}" -v baseline="${baseline_median}" 'BEGIN { printf "%.3f\n", impaired - baseline }')"
  range_increase_ms="$(awk -v impaired="${impaired_range}" -v baseline="${baseline_range}" 'BEGIN { printf "%.3f\n", impaired - baseline }')"
  baseline_failure_rate="$(failure_rate_pct_from_results "${baseline_file}")"
  impaired_failure_rate="$(failure_rate_pct_from_results "${impaired_file}")"
  restored_failure_rate="$(failure_rate_pct_from_results "${restored_file}")"

  awk -v increase="${increase_ms}" -v expected="${EXPECTED_MIN_INCREASE_MS}" '
    BEGIN {
      if (increase + 0 < expected + 0) {
        exit 1
      }
    }
  ' || aws_fail "delay validation failed: observed median increase ${increase_ms} ms, expected at least ${EXPECTED_MIN_INCREASE_MS} ms"

  if [[ "${JITTER_MS}" != "0" ]]; then
    awk -v increase="${range_increase_ms}" -v expected="${EXPECTED_MIN_JITTER_RANGE_INCREASE_MS}" '
      BEGIN {
        if (increase + 0 < expected + 0) {
          exit 1
        }
      }
    ' || aws_fail "jitter validation failed: observed TTFB range increase ${range_increase_ms} ms, expected at least ${EXPECTED_MIN_JITTER_RANGE_INCREASE_MS} ms"
  fi

  if [[ "${LOSS_PCT}" != "0" ]]; then
    if [[ -n "${EXPECTED_MIN_FAILURE_RATE_PCT}" ]]; then
      awk -v failure_rate="${impaired_failure_rate}" -v expected="${EXPECTED_MIN_FAILURE_RATE_PCT}" '
        BEGIN {
          if (failure_rate + 0 < expected + 0) {
            exit 1
          }
        }
      ' || aws_fail "loss validation failed: observed impaired failure rate ${impaired_failure_rate}%, expected at least ${EXPECTED_MIN_FAILURE_RATE_PCT}%"
    fi

    if [[ -n "${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS}" ]]; then
      awk -v increase="${range_increase_ms}" -v expected="${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS}" '
        BEGIN {
          if (increase + 0 < expected + 0) {
            exit 1
          }
        }
      ' || aws_fail "loss validation failed: observed TTFB range increase ${range_increase_ms} ms, expected at least ${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS} ms"
    fi
  fi

  cat <<EOF
[eks-delay-demo] Delay validation passed.
[eks-delay-demo] Probe URL: ${PROBE_URL}
[eks-delay-demo] Baseline median TTFB: ${baseline_median} ms
[eks-delay-demo] Impaired median TTFB: ${impaired_median} ms
[eks-delay-demo] Restored median TTFB: ${restored_median} ms
[eks-delay-demo] Observed median increase: ${increase_ms} ms
[eks-delay-demo] Expected minimum increase: ${EXPECTED_MIN_INCREASE_MS} ms
[eks-delay-demo] Baseline TTFB range: ${baseline_range} ms
[eks-delay-demo] Impaired TTFB range: ${impaired_range} ms
[eks-delay-demo] Restored TTFB range: ${restored_range} ms
[eks-delay-demo] Baseline failure rate: ${baseline_failure_rate}%
[eks-delay-demo] Impaired failure rate: ${impaired_failure_rate}%
[eks-delay-demo] Restored failure rate: ${restored_failure_rate}%
EOF

  if [[ "${JITTER_MS}" != "0" || "${LOSS_PCT}" != "0" ]]; then
    cat <<EOF
[eks-delay-demo] Observed TTFB range increase: ${range_increase_ms} ms
EOF
  fi

  if [[ "${JITTER_MS}" != "0" ]]; then
    cat <<EOF
[eks-delay-demo] Expected minimum jitter-range increase: ${EXPECTED_MIN_JITTER_RANGE_INCREASE_MS} ms
EOF
  fi

  if [[ "${LOSS_PCT}" != "0" ]]; then
    if [[ -n "${EXPECTED_MIN_FAILURE_RATE_PCT}" ]]; then
      cat <<EOF
[eks-delay-demo] Expected minimum impaired failure rate: ${EXPECTED_MIN_FAILURE_RATE_PCT}%
EOF
    fi

    if [[ -n "${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS}" ]]; then
      cat <<EOF
[eks-delay-demo] Expected minimum loss-range increase: ${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS} ms
EOF
    fi
  fi
}

[[ -n "${ACTION}" ]] || {
  usage >&2
  exit 1
}

parse_args "$@"
load_state_file "${STATE_FILE}"
require_value "ROUTER_PUBLIC_IP" "${ROUTER_PUBLIC_IP:-}"

case "${ACTION}" in
  validate) ;;
  --help|-h|help)
    usage
    exit 0
    ;;
  *)
    aws_fail "unsupported action: ${ACTION}"
    ;;
esac

require_positive_integer "delay-ms" "${DELAY_MS}"
require_positive_integer "jitter-ms" "${JITTER_MS}"
require_positive_integer "loss-pct" "${LOSS_PCT}"
require_positive_integer "samples" "${SAMPLES}"
require_positive_integer "connect-timeout" "${CURL_CONNECT_TIMEOUT}"
require_positive_integer "max-time" "${CURL_MAX_TIME}"
require_positive_integer "settle-seconds" "${SETTLE_SECONDS}"

if [[ -z "${EXPECTED_MIN_INCREASE_MS}" ]]; then
  EXPECTED_MIN_INCREASE_MS="${DELAY_MS}"
fi
require_positive_integer "expected-min-increase-ms" "${EXPECTED_MIN_INCREASE_MS}"

if [[ -z "${EXPECTED_MIN_JITTER_RANGE_INCREASE_MS}" && "${JITTER_MS}" != "0" ]]; then
  EXPECTED_MIN_JITTER_RANGE_INCREASE_MS="$(( JITTER_MS / 2 ))"
  if (( EXPECTED_MIN_JITTER_RANGE_INCREASE_MS < 5 )); then
    EXPECTED_MIN_JITTER_RANGE_INCREASE_MS="5"
  fi
fi

if [[ -n "${EXPECTED_MIN_JITTER_RANGE_INCREASE_MS}" ]]; then
  require_positive_integer "expected-min-jitter-range-increase-ms" "${EXPECTED_MIN_JITTER_RANGE_INCREASE_MS}"
fi

if [[ -n "${EXPECTED_MIN_FAILURE_RATE_PCT}" ]]; then
  require_positive_integer "expected-min-failure-rate-pct" "${EXPECTED_MIN_FAILURE_RATE_PCT}"
fi

if [[ -z "${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS}" && "${LOSS_PCT}" != "0" ]]; then
  EXPECTED_MIN_LOSS_RANGE_INCREASE_MS="${DELAY_MS}"
fi

if [[ -n "${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS}" ]]; then
  require_positive_integer "expected-min-loss-range-increase-ms" "${EXPECTED_MIN_LOSS_RANGE_INCREASE_MS}"
fi

if [[ -z "${PROBE_URL}" ]]; then
  PROBE_URL="http://${ROUTER_PUBLIC_IP}/"
fi

TEMP_DIR="$(mktemp -d)"
validate_impairment
