#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" != *"${needle}"* ]] || fail "did not expect output to contain: ${needle}"
}

write_git_stub() {
  local path="$1"

  cat > "${path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [[ "\${1-}" == "-C" && "\${3-}" == "rev-parse" && "\${4-}" == "--show-toplevel" ]]; then
  printf '%s\n' "${REPO_ROOT}"
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

write_helm_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${HELPER_HELM_LOG}"

case "${1-}" in
  repo)
    if [[ "${2-}" == "list" ]]; then
      printf 'NAME\tURL\n'
      exit 0
    fi
    exit 0
    ;;
  upgrade)
    touch "${HELPER_TEST_STATE}"
    exit 0
    ;;
esac

exit 0
EOF
  chmod +x "${path}"
}

write_kube_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

mode="${HELPER_TEST_MODE:-}"
state="${HELPER_TEST_STATE:-}"

if [[ "${1-}" == "config" && "${2-}" == "current-context" ]]; then
  if [[ -n "${HELPER_EMPTY_CONTEXT:-}" ]]; then
    exit 0
  fi
  printf 'stub-context\n'
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "namespace" && "${3-}" == "otel-splunk" ]]; then
  case "${mode}" in
    reuse-success|reuse-failure|upgrade-incompatible)
      exit 0
      ;;
    install-kubernetes|install-openshift)
      [[ -f "${state}" ]] && exit 0
      exit 1
      ;;
  esac
fi

if [[ "${1-}" == "-n" ]]; then
  shift 2
fi

if [[ "${1-}" == "get" ]]; then
  resource="${2-}"
  name="${3-}"
  output=""

  shift 3
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -o)
        output="$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done

  case "${mode}" in
    reuse-success)
      if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == jsonpath=* ]]; then
        printf 'baggage\nb3\ntracecontext\n'
        exit 0
      fi
      case "${resource}/${name}" in
        deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
          exit 0
          ;;
      esac
      ;;
    reuse-failure)
      if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == jsonpath=* ]]; then
        printf 'tracecontext\nbaggage\nb3\n'
        exit 0
      fi
      case "${resource}/${name}" in
        deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
          exit 0
          ;;
      esac
      ;;
    upgrade-incompatible)
      if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == jsonpath=* ]]; then
        if [[ -f "${state}" ]]; then
          printf 'baggage\nb3\ntracecontext\n'
        else
          printf 'tracecontext\nbaggage\nb3\n'
        fi
        exit 0
      fi
      case "${resource}/${name}" in
        deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
          exit 0
          ;;
      esac
      ;;
    install-kubernetes|install-openshift)
      if [[ -f "${state}" ]]; then
        if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == jsonpath=* ]]; then
          printf 'baggage\nb3\ntracecontext\n'
          exit 0
        fi
        case "${resource}/${name}" in
          deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
            exit 0
            ;;
        esac
      fi
      ;;
  esac

  exit 1
fi

if [[ "${1-}" == "rollout" && "${2-}" == "status" ]]; then
  printf '%s successfully rolled out\n' "${3-}"
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

run_helper() {
  local test_mode="$1"
  local script_mode="$2"
  local platform="$3"
  local extra_args="$4"
  local output_file="$5"
  local temp_dir="$6"

  local stub_dir="${temp_dir}/bin"
  mkdir -p "${stub_dir}"

  write_git_stub "${stub_dir}/git"
  write_helm_stub "${stub_dir}/helm"
  write_kube_stub "${stub_dir}/kubectl"
  cp "${stub_dir}/kubectl" "${stub_dir}/oc"
  : > "${temp_dir}/test.env"
  : > "${temp_dir}/helm.log"

  set +e
  env \
    PATH="${stub_dir}:${PATH}" \
    ENV_FILE="${temp_dir}/test.env" \
    HELPER_TEST_MODE="${test_mode}" \
    HELPER_TEST_STATE="${temp_dir}/collector.installed" \
    HELPER_HELM_LOG="${temp_dir}/helm.log" \
    SPLUNK_REALM="${SPLUNK_REALM_TEST-}" \
    SPLUNK_ACCESS_TOKEN="${SPLUNK_ACCESS_TOKEN_TEST-}" \
    SPLUNK_DEPLOYMENT_ENVIRONMENT="${SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST-}" \
    HELPER_EMPTY_CONTEXT="${HELPER_EMPTY_CONTEXT-}" \
    bash "${TARGET_SCRIPT}" --mode "${script_mode}" --platform "${platform}" ${extra_args} \
    >"${output_file}" 2>&1
  local status=$?
  set -e

  printf '%s' "${status}"
}

test_reuse_mode_succeeds_without_helm() {
  local temp_dir output status helm_log

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  status="$(run_helper reuse-success reuse kubernetes "" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "reuse-success exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${output}" 'Compatible Splunk OTel collector already present'
  [[ -z "${helm_log}" ]] || fail "reuse mode unexpectedly invoked helm"
}

test_reuse_mode_fails_on_incompatible_install() {
  local temp_dir output status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  status="$(run_helper reuse-failure reuse kubernetes "" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" != "0" ]] || fail "reuse-failure unexpectedly succeeded"

  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'Repo-compatible collector not found'
  assert_contains "${output}" 'instrumentation.propagators=baggage b3 tracecontext'
}

test_install_mode_upgrades_incompatible_existing_install() {
  local temp_dir output helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper upgrade-incompatible install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "upgrade-incompatible exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${helm_log}" 'upgrade --install splunk-otel-collector splunk-otel-collector-chart/splunk-otel-collector'
  assert_contains "${output}" 'Installing Splunk OTel collector release splunk-otel-collector in namespace otel-splunk'
  assert_contains "${output}" 'Splunk OTel collector is ready for repo-managed Java and Node.js auto-instrumentation'
}

test_install_mode_invokes_helm_for_kubernetes() {
  local temp_dir output helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "install-kubernetes exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${helm_log}" 'repo add splunk-otel-collector-chart https://signalfx.github.io/splunk-otel-collector-chart'
  assert_contains "${helm_log}" 'repo update splunk-otel-collector-chart'
  assert_contains "${helm_log}" 'upgrade --install splunk-otel-collector splunk-otel-collector-chart/splunk-otel-collector'
  assert_contains "${helm_log}" '--version 0.148.0'
  assert_contains "${helm_log}" 'k8s/otel-splunk/collector.values.yaml'
  assert_not_contains "${helm_log}" 'collector.openshift.values.yaml'
  assert_contains "${output}" 'Splunk OTel collector is ready for repo-managed Java and Node.js auto-instrumentation'
}

test_install_mode_layers_openshift_values() {
  local temp_dir helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper install-openshift install-if-missing openshift "--cli oc --cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "install-openshift exited with status ${status}"

  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${helm_log}" 'k8s/otel-splunk/collector.openshift.values.yaml'
}

test_install_mode_requires_inputs() {
  local temp_dir output status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN

  status="$(
    SPLUNK_REALM_TEST='' \
    SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
    run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}"
  )"
  [[ "${status}" != "0" ]] || fail "missing realm unexpectedly succeeded"
  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'SPLUNK_REALM must be set'

  status="$(
    SPLUNK_REALM_TEST='us1' \
    SPLUNK_ACCESS_TOKEN_TEST='' \
    run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}"
  )"
  [[ "${status}" != "0" ]] || fail "missing access token unexpectedly succeeded"
  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'SPLUNK_ACCESS_TOKEN must be set'

  status="$(
    SPLUNK_REALM_TEST='us1' \
    SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
    HELPER_EMPTY_CONTEXT=1 \
    run_helper install-kubernetes install-if-missing kubernetes "" "${temp_dir}/output.log" "${temp_dir}"
  )"
  [[ "${status}" != "0" ]] || fail "missing cluster name unexpectedly succeeded"
  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'collector cluster name must be set'
}

test_reuse_mode_succeeds_without_helm
test_reuse_mode_fails_on_incompatible_install
test_install_mode_invokes_helm_for_kubernetes
test_install_mode_upgrades_incompatible_existing_install
test_install_mode_layers_openshift_values
test_install_mode_requires_inputs

printf 'PASS: ensure-splunk-otel-collector helper\n'
