#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/scripts/aws/validate-router-delay.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

write_router_delay_stub() {
  local path="$1"
  local state_path="$2"

  cat > "${path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

action="\${1-}"
shift || true

delay_ms=""
jitter_ms="0"
loss_pct="0"
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    --delay-ms)
      delay_ms="\$2"
      shift 2
      ;;
    --jitter-ms)
      jitter_ms="\$2"
      shift 2
      ;;
    --loss-pct)
      loss_pct="\$2"
      shift 2
      ;;
    --state-file)
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

mode="disabled"
if [[ -f "${state_path}" ]]; then
  mode="\$(cat "${state_path}")"
fi

case "\${action}" in
  enable)
    printf 'enabled:%s:%s:%s\n' "\${delay_ms}" "\${jitter_ms}" "\${loss_pct}" > "${state_path}"
    ;;
  disable)
    printf 'disabled\n' > "${state_path}"
    ;;
  status)
    printf 'public=eth0\n'
    printf 'private=eth1\n'
    if [[ "\${mode}" == enabled:* ]]; then
      IFS=: read -r _ configured_delay configured_jitter configured_loss <<< "\${mode}"
      printf 'qdisc netem 8001: root refcnt 2 limit 1000 delay %s.0ms %s.0ms loss %s%%\n' "\${configured_delay}" "\${configured_jitter}" "\${configured_loss}"
      printf 'qdisc netem 8002: root refcnt 2 limit 1000 delay %s.0ms %s.0ms loss %s%%\n' "\${configured_delay}" "\${configured_jitter}" "\${configured_loss}"
    else
      printf 'qdisc noqueue 0: root refcnt 2\n'
      printf 'qdisc noqueue 0: root refcnt 2\n'
    fi
    ;;
  *)
    exit 1
    ;;
esac
EOF
  chmod +x "${path}"
}

write_delay_only_curl_stub() {
  local path="$1"
  local state_path="$2"

  cat > "${path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

mode="disabled"
if [[ -f "${state_path}" ]]; then
  mode="\$(cat "${state_path}")"
fi

if [[ "\${mode}" == enabled:* ]]; then
  printf '0.340\n'
  exit 0
fi

count_file="${state_path}.disabled.count"
count=0
if [[ -f "\${count_file}" ]]; then
  count="\$(cat "\${count_file}")"
fi
count="\$((count + 1))"
printf '%s\n' "\${count}" > "\${count_file}"

case "\${count}" in
  1|4)
    printf '0.080\n'
    ;;
  2|5)
    printf '0.081\n'
    ;;
  3|6)
    printf '0.079\n'
    ;;
  *)
    printf '0.080\n'
    ;;
esac
EOF
  chmod +x "${path}"
}

write_jitter_loss_curl_stub() {
  local path="$1"
  local state_path="$2"

  cat > "${path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

mode="disabled"
if [[ -f "${state_path}" ]]; then
  mode="\$(cat "${state_path}")"
fi

case "\${mode}" in
  disabled)
    count_file="${state_path}.disabled.count"
    count=0
    if [[ -f "\${count_file}" ]]; then
      count="\$(cat "\${count_file}")"
    fi
    count="\$((count + 1))"
    printf '%s\n' "\${count}" > "\${count_file}"

    case "\${count}" in
      1|7)
        printf '0.080\n'
        ;;
      2|8)
        printf '0.081\n'
        ;;
      3|9)
        printf '0.079\n'
        ;;
      4|10)
        printf '0.082\n'
        ;;
      5|11)
        printf '0.080\n'
        ;;
      6|12)
        printf '0.081\n'
        ;;
      *)
        printf '0.080\n'
        ;;
    esac
    ;;
  enabled:120:80:30)
    count_file="${state_path}.enabled_120_80_30.count"
    count=0
    if [[ -f "\${count_file}" ]]; then
      count="\$(cat "\${count_file}")"
    fi
    count="\$((count + 1))"
    printf '%s\n' "\${count}" > "\${count_file}"

    case "\${count}" in
      1)
        printf '0.240\n'
        ;;
      2)
        printf '0.430\n'
        ;;
      3)
        exit 22
        ;;
      4)
        printf '0.300\n'
        ;;
      5)
        exit 22
        ;;
      6)
        printf '0.390\n'
        ;;
      *)
        printf '0.300\n'
        ;;
    esac
    ;;
  *)
    printf '0.080\n'
    ;;
esac
EOF
  chmod +x "${path}"
}

run_delay_only_case() {
  local temp_dir router_delay_stub curl_stub state_file stub_state output

  temp_dir="$(mktemp -d)"
  stub_state="${temp_dir}/router-mode.txt"
  printf 'disabled\n' > "${stub_state}"

  router_delay_stub="${temp_dir}/router-delay-stub.sh"
  curl_stub="${temp_dir}/curl-stub.sh"
  state_file="${temp_dir}/demo.env"
  cat > "${state_file}" <<EOF
ROUTER_PUBLIC_IP=44.208.125.119
EOF

  write_router_delay_stub "${router_delay_stub}" "${stub_state}"
  write_delay_only_curl_stub "${curl_stub}" "${stub_state}"

  output="$(
    ROUTER_DELAY_CLI="${router_delay_stub}" \
    CURL_BIN="${curl_stub}" \
    bash "${TARGET_SCRIPT}" validate \
      --state-file "${state_file}" \
      --delay-ms 120 \
      --samples 3 \
      --settle-seconds 0
  )"

  assert_contains "${output}" 'Delay validation passed.'
  assert_contains "${output}" 'Baseline median TTFB: 80.000 ms'
  assert_contains "${output}" 'Impaired median TTFB: 340.000 ms'
  assert_contains "${output}" 'Observed median increase: 260.000 ms'
  assert_contains "${output}" 'Expected minimum increase: 120 ms'
  assert_contains "${output}" 'Impaired failure rate: 0.000%'

  rm -rf "${temp_dir}"
}

run_jitter_loss_case() {
  local temp_dir router_delay_stub curl_stub state_file stub_state output

  temp_dir="$(mktemp -d)"
  stub_state="${temp_dir}/router-mode.txt"
  printf 'disabled\n' > "${stub_state}"

  router_delay_stub="${temp_dir}/router-delay-stub.sh"
  curl_stub="${temp_dir}/curl-stub.sh"
  state_file="${temp_dir}/demo.env"
  cat > "${state_file}" <<EOF
ROUTER_PUBLIC_IP=44.208.125.119
EOF

  write_router_delay_stub "${router_delay_stub}" "${stub_state}"
  write_jitter_loss_curl_stub "${curl_stub}" "${stub_state}"

  output="$(
    ROUTER_DELAY_CLI="${router_delay_stub}" \
    CURL_BIN="${curl_stub}" \
    bash "${TARGET_SCRIPT}" validate \
      --state-file "${state_file}" \
      --delay-ms 120 \
      --jitter-ms 80 \
      --loss-pct 30 \
      --samples 6 \
      --settle-seconds 0 \
      --expected-min-jitter-range-increase-ms 100 \
      --expected-min-failure-rate-pct 30
  )"

  assert_contains "${output}" 'Delay validation passed.'
  assert_contains "${output}" 'Observed TTFB range increase: 187.000 ms'
  assert_contains "${output}" 'Expected minimum jitter-range increase: 100 ms'
  assert_contains "${output}" 'Impaired failure rate: 33.333%'
  assert_contains "${output}" 'Expected minimum impaired failure rate: 30%'

  rm -rf "${temp_dir}"
}

run_delay_only_case
run_jitter_loss_case

printf 'PASS: validate-router-delay delay, jitter, and loss regression tests\n'
