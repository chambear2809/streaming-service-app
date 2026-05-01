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

reset_test_overrides() {
  unset \
    HELPER_EMPTY_CONTEXT \
    HELPER_HELM_VERSION_TEST \
    HELPER_INSTALLED_CHART_VERSION_TEST \
    HELPER_RELEASE_ACCESS_TOKEN_TEST \
    HELPER_RELEASE_CLUSTER_NAME_TEST \
    HELPER_RELEASE_ENVIRONMENT_TEST \
    HELPER_RELEASE_REALM_TEST \
    HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST \
    SPLUNK_ACCESS_TOKEN_TEST \
    SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST \
    SPLUNK_OTEL_CLUSTER_NAME_TEST \
    SPLUNK_OTEL_HELM_CHART_VERSION_TEST \
    SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN_TEST \
    SPLUNK_OTEL_SECONDARY_API_URL_TEST \
    SPLUNK_OTEL_SECONDARY_INGEST_URL_TEST \
    SPLUNK_OTEL_SECONDARY_REALM_TEST \
    SPLUNK_REALM_TEST || true
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
  version)
    printf 'version.BuildInfo{Version:"v%s", GitCommit:"stub", GitTreeState:"clean", GoVersion:"go1.0"}\n' "${HELPER_HELM_VERSION:-3.15.4}"
    exit 0
    ;;
  get)
    if [[ "${2-}" == "values" ]]; then
      if [[ -n "${HELPER_RELEASE_VALUES_FILE:-}" && -f "${HELPER_RELEASE_VALUES_FILE}" ]]; then
        cat "${HELPER_RELEASE_VALUES_FILE}"
        exit 0
      fi

      environment_value="${HELPER_RELEASE_ENVIRONMENT:-${SPLUNK_DEPLOYMENT_ENVIRONMENT:-streaming-app}}"
      cluster_name_value="${HELPER_RELEASE_CLUSTER_NAME:-${SPLUNK_OTEL_CLUSTER_NAME:-stub-context}}"
      realm_value="${HELPER_RELEASE_REALM:-${SPLUNK_REALM:-}}"
      access_token_value="${HELPER_RELEASE_ACCESS_TOKEN:-${SPLUNK_ACCESS_TOKEN:-}}"
      cat <<EOV
clusterName: ${cluster_name_value}
environment: ${environment_value}
splunkObservability:
  realm: ${realm_value}
  accessToken: ${access_token_value}
EOV
      exit 0
    fi
    ;;
  repo)
    if [[ "${2-}" == "list" ]]; then
      printf 'NAME\tURL\n'
      exit 0
    fi
    exit 0
    ;;
  list)
    printf 'NAME\tNAMESPACE\tREVISION\tUPDATED\tSTATUS\tCHART\tAPP VERSION\n'
    if [[ -n "${HELPER_INSTALLED_CHART_VERSION:-}" ]]; then
      printf 'splunk-otel-collector\totel-splunk\t1\t2026-01-01T00:00:00Z\tdeployed\tsplunk-otel-collector-%s\t%s\n' \
        "${HELPER_INSTALLED_CHART_VERSION}" \
        "${HELPER_INSTALLED_CHART_VERSION}"
    fi
    exit 0
    ;;
  upgrade)
    previous=""
    for arg in "$@"; do
      if [[ "${previous}" == "-f" && "${arg}" == *collector.runtime.values.yaml && -n "${HELPER_RENDERED_VALUES_LOG:-}" && -f "${arg}" ]]; then
        cat "${arg}" > "${HELPER_RENDERED_VALUES_LOG}"
      fi
      previous="${arg}"
    done
    if [[ -n "${HELPER_RELEASE_VALUES_FILE:-}" ]]; then
      cat <<EOV > "${HELPER_RELEASE_VALUES_FILE}"
clusterName: ${SPLUNK_OTEL_CLUSTER_NAME:-stub-context}
environment: ${SPLUNK_DEPLOYMENT_ENVIRONMENT:-streaming-app}
splunkObservability:
  realm: ${SPLUNK_REALM:-}
  accessToken: ${SPLUNK_ACCESS_TOKEN:-}
EOV
    fi
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
service_policy_file="${HELPER_SERVICE_POLICY_FILE:-}"
service_policy_default="${HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY:-Local}"

current_service_policy() {
  if [[ -n "${service_policy_file}" && -f "${service_policy_file}" ]]; then
    cat "${service_policy_file}"
    return
  fi

  printf '%s' "${service_policy_default}"
}

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

if [[ "${1-}" == "patch" && "${2-}" == "service" && "${3-}" == "splunk-otel-collector-agent" ]]; then
  if [[ -n "${service_policy_file}" ]]; then
    printf 'Cluster' > "${service_policy_file}"
  fi
  exit 0
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
      if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  exporter:
    endpoint: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  propagators:
  - baggage
  - b3
  - tracecontext
  java:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  nodejs:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "daemonset/splunk-otel-collector-agent" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "deployment/splunk-otel-collector-k8s-cluster-receiver" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "service/splunk-otel-collector-agent" && "${output}" == "jsonpath={.spec.internalTrafficPolicy}" ]]; then
        current_service_policy
        exit 0
      fi
      case "${resource}/${name}" in
        deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|service/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
          exit 0
          ;;
      esac
      ;;
    reuse-failure)
      if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  exporter:
    endpoint: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  propagators:
  - tracecontext
  - baggage
  - b3
  java:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  nodejs:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "daemonset/splunk-otel-collector-agent" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "deployment/splunk-otel-collector-k8s-cluster-receiver" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "service/splunk-otel-collector-agent" && "${output}" == "jsonpath={.spec.internalTrafficPolicy}" ]]; then
        current_service_policy
        exit 0
      fi
      case "${resource}/${name}" in
        deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|service/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
          exit 0
          ;;
      esac
      ;;
    upgrade-incompatible)
      if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == "yaml" ]]; then
        if [[ -f "${state}" ]]; then
          cat <<'EOP'
spec:
  exporter:
    endpoint: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  propagators:
  - baggage
  - b3
  - tracecontext
  java:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  nodejs:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
EOP
        else
          cat <<'EOP'
spec:
  exporter:
    endpoint: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  propagators:
  - tracecontext
  - baggage
  - b3
  java:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  nodejs:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
EOP
        fi
        exit 0
      fi
      if [[ "${resource}/${name}" == "daemonset/splunk-otel-collector-agent" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "deployment/splunk-otel-collector-k8s-cluster-receiver" && "${output}" == "yaml" ]]; then
        cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
        exit 0
      fi
      if [[ "${resource}/${name}" == "service/splunk-otel-collector-agent" && "${output}" == "jsonpath={.spec.internalTrafficPolicy}" ]]; then
        current_service_policy
        exit 0
      fi
      case "${resource}/${name}" in
        deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|service/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
          exit 0
          ;;
      esac
      ;;
    install-kubernetes|install-openshift)
      if [[ -f "${state}" ]]; then
        if [[ "${resource}/${name}" == "instrumentations.opentelemetry.io/splunk-otel-collector" && "${output}" == "yaml" ]]; then
          cat <<'EOP'
spec:
  exporter:
    endpoint: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  propagators:
  - baggage
  - b3
  - tracecontext
  java:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
  nodejs:
    env:
    - name: OTEL_EXPORTER_OTLP_ENDPOINT
      value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317
EOP
          exit 0
        fi
        if [[ "${resource}/${name}" == "daemonset/splunk-otel-collector-agent" && "${output}" == "yaml" ]]; then
          cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
          exit 0
        fi
        if [[ "${resource}/${name}" == "deployment/splunk-otel-collector-k8s-cluster-receiver" && "${output}" == "yaml" ]]; then
          cat <<'EOP'
spec:
  template:
    spec:
      nodeSelector:
        eks.amazonaws.com/nodegroup: private
      tolerations:
      - key: dedicated
        operator: Equal
        value: otel
        effect: NoSchedule
EOP
          exit 0
        fi
        if [[ "${resource}/${name}" == "service/splunk-otel-collector-agent" && "${output}" == "jsonpath={.spec.internalTrafficPolicy}" ]]; then
          current_service_policy
          exit 0
        fi
        case "${resource}/${name}" in
          deployment/splunk-otel-collector-opentelemetry-operator|deployment/splunk-otel-collector-operator|deployment/splunk-otel-collector-k8s-cluster-receiver|daemonset/splunk-otel-collector-agent|service/splunk-otel-collector-agent|instrumentations.opentelemetry.io/splunk-otel-collector)
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
  local inferred_cluster_name="${SPLUNK_OTEL_CLUSTER_NAME_TEST-}"

  local stub_dir="${temp_dir}/bin"
  mkdir -p "${stub_dir}"

  write_git_stub "${stub_dir}/git"
  write_helm_stub "${stub_dir}/helm"
  write_kube_stub "${stub_dir}/kubectl"
  cp "${stub_dir}/kubectl" "${stub_dir}/oc"
  : > "${temp_dir}/test.env"
  : > "${temp_dir}/helm.log"

  if [[ -z "${inferred_cluster_name}" && "${extra_args}" =~ --cluster-name[[:space:]]+([^[:space:]]+) ]]; then
    inferred_cluster_name="${BASH_REMATCH[1]}"
  fi

  set +e
  env \
    PATH="${stub_dir}:${PATH}" \
    ENV_FILE="${temp_dir}/test.env" \
    HELPER_TEST_MODE="${test_mode}" \
    HELPER_TEST_STATE="${temp_dir}/collector.installed" \
    HELPER_SERVICE_POLICY_FILE="${temp_dir}/service.policy" \
    HELPER_HELM_LOG="${temp_dir}/helm.log" \
    HELPER_RENDERED_VALUES_LOG="${temp_dir}/rendered-values.log" \
    HELPER_RELEASE_VALUES_FILE="${temp_dir}/release-values.yaml" \
    HELPER_INSTALLED_CHART_VERSION="${HELPER_INSTALLED_CHART_VERSION_TEST-}" \
    HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY="${HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST-Local}" \
    SPLUNK_REALM="${SPLUNK_REALM_TEST-}" \
    SPLUNK_ACCESS_TOKEN="${SPLUNK_ACCESS_TOKEN_TEST-}" \
    SPLUNK_DEPLOYMENT_ENVIRONMENT="${SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST-}" \
    SPLUNK_OTEL_CLUSTER_NAME="${inferred_cluster_name}" \
    SPLUNK_OTEL_HELM_CHART_VERSION="${SPLUNK_OTEL_HELM_CHART_VERSION_TEST-}" \
    SPLUNK_OTEL_SECONDARY_REALM="${SPLUNK_OTEL_SECONDARY_REALM_TEST-}" \
    SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN="${SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN_TEST-}" \
    SPLUNK_OTEL_SECONDARY_INGEST_URL="${SPLUNK_OTEL_SECONDARY_INGEST_URL_TEST-}" \
    SPLUNK_OTEL_SECONDARY_API_URL="${SPLUNK_OTEL_SECONDARY_API_URL_TEST-}" \
    HELPER_RELEASE_CLUSTER_NAME="${HELPER_RELEASE_CLUSTER_NAME_TEST-}" \
    HELPER_RELEASE_ENVIRONMENT="${HELPER_RELEASE_ENVIRONMENT_TEST-}" \
    HELPER_RELEASE_REALM="${HELPER_RELEASE_REALM_TEST-}" \
    HELPER_RELEASE_ACCESS_TOKEN="${HELPER_RELEASE_ACCESS_TOKEN_TEST-}" \
    HELPER_HELM_VERSION="${HELPER_HELM_VERSION_TEST-3.15.4}" \
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
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Cluster' \
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
  reset_test_overrides
  status="$(run_helper reuse-failure reuse kubernetes "" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" != "0" ]] || fail "reuse-failure unexpectedly succeeded"

  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'Repo-compatible collector not found'
  assert_contains "${output}" 'instrumentation.propagators=baggage b3 tracecontext'
}

test_reuse_mode_fails_when_agent_service_is_local() {
  local temp_dir output status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Local' \
  status="$(run_helper reuse-success reuse kubernetes "" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" != "0" ]] || fail "reuse-success unexpectedly tolerated local-only agent service routing"

  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'Repo-compatible collector not found'
  assert_contains "${output}" 'service/splunk-otel-collector-agent.spec.internalTrafficPolicy=Cluster'
}

test_install_mode_skips_when_collector_is_already_reconciled() {
  local temp_dir output helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Cluster' \
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper reuse-success install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "reconciled install unexpectedly failed with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${output}" 'Compatible Splunk OTel collector already present; skipping install'
  assert_not_contains "${helm_log}" 'upgrade --install splunk-otel-collector'
}

test_install_mode_reconciles_primary_runtime_value_drift() {
  local temp_dir output helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Cluster' \
  HELPER_RELEASE_REALM_TEST='us2' \
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper reuse-success install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "runtime drift reconciliation exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${output}" 'Collector shape matches repo requirements, but live runtime values differ; reconciling release'
  assert_contains "${helm_log}" 'upgrade --install splunk-otel-collector splunk-otel-collector-chart/splunk-otel-collector'
  assert_contains "${helm_log}" '--version 0.149.0'
}

test_install_mode_upgrades_incompatible_existing_install() {
  local temp_dir output helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Local' \
  HELPER_INSTALLED_CHART_VERSION_TEST='0.149.0' \
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper upgrade-incompatible install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "upgrade-incompatible exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${helm_log}" 'upgrade --install splunk-otel-collector splunk-otel-collector-chart/splunk-otel-collector'
  assert_contains "${helm_log}" '--version 0.149.0'
  assert_contains "${helm_log}" '--post-renderer'
  assert_contains "${output}" 'Installing Splunk OTel collector release splunk-otel-collector in namespace otel-splunk'
  assert_contains "${output}" 'Patching Service splunk-otel-collector-agent to internalTrafficPolicy=Cluster'
  assert_contains "${output}" 'Splunk OTel collector is ready for repo-managed Java and Node.js auto-instrumentation'
}

test_install_mode_converges_to_repo_default_chart_when_existing_chart_differs() {
  local temp_dir helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Local' \
  HELPER_INSTALLED_CHART_VERSION_TEST='0.148.0' \
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper upgrade-incompatible install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "default-chart reconciliation exited with status ${status}"

  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${helm_log}" '--version 0.149.0'
}

test_install_mode_honors_explicit_chart_pin_over_repo_default() {
  local temp_dir helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Local' \
  HELPER_INSTALLED_CHART_VERSION_TEST='0.148.0' \
  SPLUNK_OTEL_HELM_CHART_VERSION_TEST='0.150.0' \
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper upgrade-incompatible install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "explicit chart pin exited with status ${status}"

  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${helm_log}" '--version 0.150.0'
}

test_install_mode_invokes_helm_for_kubernetes() {
  local temp_dir output helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Local' \
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
  assert_contains "${helm_log}" '--version 0.149.0'
  assert_contains "${helm_log}" '--post-renderer'
  assert_contains "${helm_log}" 'k8s/otel-splunk/collector.values.yaml'
  assert_not_contains "${helm_log}" 'collector.openshift.values.yaml'
  assert_contains "${output}" 'Patching Service splunk-otel-collector-agent to internalTrafficPolicy=Cluster'
  assert_contains "${output}" 'Splunk OTel collector is ready for repo-managed Java and Node.js auto-instrumentation'
}

test_install_mode_skips_path_post_renderer_for_helm4() {
  local temp_dir output helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  HELPER_SERVICE_INTERNAL_TRAFFIC_POLICY_TEST='Local' \
  HELPER_HELM_VERSION_TEST='4.1.4' \
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "install-kubernetes with Helm 4 exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_not_contains "${helm_log}" '--post-renderer'
  assert_contains "${output}" 'does not support path-based post-renderers; relying on the post-upgrade Service patch'
  assert_contains "${output}" 'Patching Service splunk-otel-collector-agent to internalTrafficPolicy=Cluster'
}

test_install_mode_layers_openshift_values() {
  local temp_dir helm_log status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  status="$(run_helper install-openshift install-if-missing openshift "--cli oc --cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "install-openshift exited with status ${status}"

  helm_log="$(cat "${temp_dir}/helm.log")"
  assert_contains "${helm_log}" 'k8s/otel-splunk/collector.openshift.values.yaml'
}

test_install_mode_layers_secondary_o11y_values() {
  local temp_dir output helm_log rendered_values status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  SPLUNK_OTEL_SECONDARY_REALM_TEST='us2' \
  SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN_TEST='secondary-token' \
  status="$(run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "secondary install exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  helm_log="$(cat "${temp_dir}/helm.log")"
  rendered_values="$(cat "${temp_dir}/rendered-values.log")"
  assert_contains "${helm_log}" 'k8s/otel-splunk/collector.secondary-o11y.values.yaml'
  assert_contains "${output}" 'Enabling secondary Splunk Observability export to realm us2'
  assert_contains "${rendered_values}" 'name: SPLUNK_OTEL_SECONDARY_REALM'
  assert_contains "${rendered_values}" "value: 'us2'"
  assert_contains "${rendered_values}" 'name: SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN'
  assert_contains "${rendered_values}" "value: 'secondary-token'"
  assert_contains "${rendered_values}" 'name: SPLUNK_OTEL_SECONDARY_INGEST_URL'
  assert_contains "${rendered_values}" "value: 'https://ingest.us2.signalfx.com'"
  assert_contains "${rendered_values}" 'name: SPLUNK_OTEL_SECONDARY_API_URL'
  assert_contains "${rendered_values}" "value: 'https://api.us2.signalfx.com'"
}

test_install_mode_derives_secondary_api_from_external_ingest() {
  local temp_dir output rendered_values status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  reset_test_overrides
  SPLUNK_REALM_TEST='us1' \
  SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
  SPLUNK_DEPLOYMENT_ENVIRONMENT_TEST='streaming-app' \
  SPLUNK_OTEL_SECONDARY_REALM_TEST='rc0' \
  SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN_TEST='secondary-token' \
  SPLUNK_OTEL_SECONDARY_INGEST_URL_TEST='https://external-ingest.rc0.signalfx.com' \
  status="$(run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}")"
  [[ "${status}" == "0" ]] || fail "external secondary install exited with status ${status}"

  output="$(cat "${temp_dir}/output.log")"
  rendered_values="$(cat "${temp_dir}/rendered-values.log")"
  assert_contains "${output}" 'Splunk OTel secondary ingest URL: https://external-ingest.rc0.signalfx.com'
  assert_contains "${output}" 'Splunk OTel secondary API URL: https://external-api.rc0.signalfx.com'
  assert_contains "${rendered_values}" "value: 'https://external-ingest.rc0.signalfx.com'"
  assert_contains "${rendered_values}" "value: 'https://external-api.rc0.signalfx.com'"
}

test_install_mode_requires_inputs() {
  local temp_dir output status

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN

  reset_test_overrides
  status="$(
    SPLUNK_REALM_TEST='' \
    SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
    run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}"
  )"
  [[ "${status}" != "0" ]] || fail "missing realm unexpectedly succeeded"
  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'SPLUNK_REALM must be set'

  reset_test_overrides
  status="$(
    SPLUNK_REALM_TEST='us1' \
    SPLUNK_ACCESS_TOKEN_TEST='' \
    run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}"
  )"
  [[ "${status}" != "0" ]] || fail "missing access token unexpectedly succeeded"
  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'SPLUNK_ACCESS_TOKEN must be set'

  reset_test_overrides
  status="$(
    SPLUNK_REALM_TEST='us1' \
    SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
    SPLUNK_OTEL_SECONDARY_REALM_TEST='us2' \
    SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN_TEST='' \
    run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}"
  )"
  [[ "${status}" != "0" ]] || fail "partial secondary config unexpectedly succeeded"
  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'SPLUNK_OTEL_SECONDARY_REALM and SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN must both be set'

  reset_test_overrides
  status="$(
    SPLUNK_REALM_TEST='us1' \
    SPLUNK_ACCESS_TOKEN_TEST='collector-token' \
    SPLUNK_OTEL_SECONDARY_INGEST_URL_TEST='https://external-ingest.rc0.signalfx.com' \
    run_helper install-kubernetes install-if-missing kubernetes "--cluster-name demo-cluster" "${temp_dir}/output.log" "${temp_dir}"
  )"
  [[ "${status}" != "0" ]] || fail "secondary url-only config unexpectedly succeeded"
  output="$(cat "${temp_dir}/output.log")"
  assert_contains "${output}" 'SPLUNK_OTEL_SECONDARY_REALM and SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN must both be set'

  reset_test_overrides
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
test_reuse_mode_fails_when_agent_service_is_local
test_install_mode_skips_when_collector_is_already_reconciled
test_install_mode_reconciles_primary_runtime_value_drift
test_install_mode_invokes_helm_for_kubernetes
test_install_mode_upgrades_incompatible_existing_install
test_install_mode_converges_to_repo_default_chart_when_existing_chart_differs
test_install_mode_honors_explicit_chart_pin_over_repo_default
test_install_mode_layers_openshift_values
test_install_mode_layers_secondary_o11y_values
test_install_mode_derives_secondary_api_from_external_ingest
test_install_mode_skips_path_post_renderer_for_helm4
test_install_mode_requires_inputs

printf 'PASS: ensure-splunk-otel-collector helper\n'
