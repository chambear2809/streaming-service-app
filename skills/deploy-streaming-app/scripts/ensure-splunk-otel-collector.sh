#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel 2>/dev/null || true)"
ENV_FILE="${ENV_FILE-}"
MODE="${SPLUNK_OTEL_MODE-}"
PLATFORM="${PLATFORM-}"
KUBECLI="${KUBECLI-}"
CLUSTER_NAME="${SPLUNK_OTEL_CLUSTER_NAME-}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT-}"
INSTRUMENTATION_WAIT_SECONDS="${SPLUNK_OTEL_INSTRUMENTATION_WAIT_SECONDS:-120}"
SECONDARY_REALM="${SPLUNK_OTEL_SECONDARY_REALM-}"
SECONDARY_ACCESS_TOKEN="${SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN-}"
SECONDARY_INGEST_URL="${SPLUNK_OTEL_SECONDARY_INGEST_URL-}"
SECONDARY_API_URL="${SPLUNK_OTEL_SECONDARY_API_URL-}"
COLLECTOR_NAMESPACE="otel-splunk"
COLLECTOR_RELEASE="splunk-otel-collector"
AGENT_SERVICE="${COLLECTOR_RELEASE}-agent"
AGENT_CONFIGMAP="${COLLECTOR_RELEASE}-otel-agent"
CLUSTER_RECEIVER_CONFIGMAP="${COLLECTOR_RELEASE}-otel-k8s-cluster-receiver"
POST_RENDERER_SCRIPT="${SKILL_DIR}/scripts/post-render-splunk-otel-manifests.sh"
HELM_REPO_NAME="splunk-otel-collector-chart"
HELM_REPO_URL="https://signalfx.github.io/splunk-otel-collector-chart"
CHART_NAME="${HELM_REPO_NAME}/splunk-otel-collector"
DEFAULT_CHART_VERSION="0.149.0"
CHART_VERSION="${SPLUNK_OTEL_HELM_CHART_VERSION:-}"
REQUIRED_PROPAGATORS=("baggage" "b3" "tracecontext")
REQUIRED_OTLP_GRPC_ENDPOINT="http://${AGENT_SERVICE}.${COLLECTOR_NAMESPACE}.svc.cluster.local:4317"
REQUIRED_AGENT_SERVICE_TRAFFIC_POLICY="Cluster"
TEMP_DIR=""

usage() {
  cat <<'EOF'
Usage: ensure-splunk-otel-collector.sh [options]

Ensure the repo-compatible Splunk OTel Collector exists in the cluster.

Options:
  --mode <reuse|install-if-missing>
  --platform <auto|kubernetes|openshift>
  --cli <kubectl|oc>
  --cluster-name <name>
  --timeout <duration>
  --env-file <path>
  --help

Environment variables:
  ENV_FILE                          Optional path to a repo-style env file
  SPLUNK_REALM                      Splunk Observability realm
  SPLUNK_ACCESS_TOKEN               Splunk Observability access token
  SPLUNK_DEPLOYMENT_ENVIRONMENT     Deployment environment label
  SPLUNK_OTEL_CLUSTER_NAME          Cluster name used for collector telemetry
  SPLUNK_OTEL_SECONDARY_REALM       Optional secondary Splunk Observability realm
  SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN
                                    Optional secondary Splunk Observability access token
  SPLUNK_OTEL_SECONDARY_INGEST_URL  Optional secondary Splunk Observability ingest URL
  SPLUNK_OTEL_SECONDARY_API_URL     Optional secondary Splunk Observability API URL
  SPLUNK_OTEL_HELM_CHART_VERSION    Helm chart version to install
EOF
}

log() {
  printf '[deploy-streaming-app] %s\n' "$*"
}

fail() {
  printf '[deploy-streaming-app] ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
    rm -rf "${TEMP_DIR}"
  fi
}

trap cleanup EXIT

if [[ -z "${REPO_ROOT}" ]]; then
  fail "Could not determine the repository root. Run this script from within the streaming-service-app git checkout."
fi

load_env_file() {
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

    if [[ -n "${!key:-}" ]]; then
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

refresh_env_overrides() {
  MODE="${MODE:-${SPLUNK_OTEL_MODE-}}"
  CLUSTER_NAME="${CLUSTER_NAME:-${SPLUNK_OTEL_CLUSTER_NAME-}}"
  SECONDARY_REALM="${SECONDARY_REALM:-${SPLUNK_OTEL_SECONDARY_REALM-}}"
  SECONDARY_ACCESS_TOKEN="${SECONDARY_ACCESS_TOKEN:-${SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN-}}"
  SECONDARY_INGEST_URL="${SECONDARY_INGEST_URL:-${SPLUNK_OTEL_SECONDARY_INGEST_URL-}}"
  SECONDARY_API_URL="${SECONDARY_API_URL:-${SPLUNK_OTEL_SECONDARY_API_URL-}}"
  CHART_VERSION="${CHART_VERSION:-${SPLUNK_OTEL_HELM_CHART_VERSION:-}}"
}

ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"
load_env_file "${ENV_FILE}"
refresh_env_overrides

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      MODE="${2:?missing value for --mode}"
      shift 2
      ;;
    --platform)
      PLATFORM="${2:?missing value for --platform}"
      shift 2
      ;;
    --cli)
      KUBECLI="${2:?missing value for --cli}"
      shift 2
      ;;
    --cluster-name)
      CLUSTER_NAME="${2:?missing value for --cluster-name}"
      shift 2
      ;;
    --timeout)
      ROLLOUT_TIMEOUT="${2:?missing value for --timeout}"
      shift 2
      ;;
    --env-file)
      ENV_FILE="${2:?missing value for --env-file}"
      load_env_file "${ENV_FILE}"
      refresh_env_overrides
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

require_command() {
  command_exists "$1" || fail "required command '$1' is not installed"
}

require_file() {
  [[ -f "$1" ]] || fail "required file not found: $1"
}

require_value() {
  local value="$1"
  local label="$2"

  [[ -n "${value}" ]] || fail "${label} must be set"
}

helm_major_version() {
  local version_output

  version_output="$(helm version 2>/dev/null || true)"
  if [[ "${version_output}" =~ Version:\"v([0-9]+)\. ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return
  fi

  printf '0\n'
}

helm_supports_path_post_renderer() {
  local major_version

  major_version="$(helm_major_version)"
  [[ "${major_version}" =~ ^[0-9]+$ ]] || return 0
  (( major_version < 4 ))
}

detect_platform() {
  if command_exists oc && oc api-resources --api-group=route.openshift.io >/dev/null 2>&1; then
    printf 'openshift\n'
  else
    printf 'kubernetes\n'
  fi
}

verify_repo_layout() {
  require_file "${REPO_ROOT}/k8s/otel-splunk/collector.values.yaml"
  require_file "${REPO_ROOT}/k8s/otel-splunk/collector.openshift.values.yaml"
  require_file "${REPO_ROOT}/k8s/otel-splunk/collector.secondary-o11y.values.yaml"
  require_file "${POST_RENDERER_SCRIPT}"
}

verify_tooling() {
  require_command "${KUBECLI}"

  if [[ "${MODE}" == "install-if-missing" ]]; then
    require_command helm
  fi
}

secondary_o11y_requested() {
  [[ -n "${SECONDARY_REALM:-}" || -n "${SECONDARY_ACCESS_TOKEN:-}" || -n "${SECONDARY_INGEST_URL:-}" || -n "${SECONDARY_API_URL:-}" ]]
}

validate_secondary_o11y_inputs() {
  if ! secondary_o11y_requested; then
    return
  fi

  if [[ -z "${SECONDARY_REALM:-}" || -z "${SECONDARY_ACCESS_TOKEN:-}" ]]; then
    fail "SPLUNK_OTEL_SECONDARY_REALM and SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN must both be set"
  fi
}

default_secondary_ingest_url() {
  printf 'https://ingest.%s.signalfx.com\n' "${SECONDARY_REALM}"
}

default_secondary_api_url() {
  printf 'https://api.%s.signalfx.com\n' "${SECONDARY_REALM}"
}

derive_api_url_from_ingest_url() {
  local ingest_url="$1"

  if [[ "${ingest_url}" == *"://external-ingest."* ]]; then
    printf '%s\n' "${ingest_url/external-ingest./external-api.}"
    return
  fi

  if [[ "${ingest_url}" == *"://ingest."* ]]; then
    printf '%s\n' "${ingest_url/ingest./api.}"
    return
  fi

  printf '%s\n' "$(default_secondary_api_url)"
}

normalize_secondary_endpoints() {
  if ! secondary_o11y_requested; then
    return
  fi

  if [[ -z "${SECONDARY_INGEST_URL:-}" ]]; then
    SECONDARY_INGEST_URL="$(default_secondary_ingest_url)"
  fi

  if [[ -z "${SECONDARY_API_URL:-}" ]]; then
    SECONDARY_API_URL="$(derive_api_url_from_ingest_url "${SECONDARY_INGEST_URL}")"
  fi
}

yaml_quote() {
  local value="$1"
  value="${value//\'/\'\'}"
  printf "'%s'" "${value}"
}

yaml_unquote() {
  local value="$1"

  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"

  if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
    value="${value:1:${#value}-2}"
  fi

  printf '%s\n' "${value}"
}

yaml_top_level_scalar() {
  local text="$1"
  local key="$2"

  awk -v key="${key}" '
    $1 == key ":" {
      sub(/^[^:]+:[[:space:]]*/, "", $0)
      print
      exit
    }
  ' <<< "${text}"
}

yaml_nested_scalar() {
  local text="$1"
  local section="$2"
  local key="$3"

  awk -v section="${section}" -v key="${key}" '
    $1 == section ":" {
      in_section = 1
      next
    }

    in_section && /^[^[:space:]]/ {
      exit
    }

    in_section && $1 == key ":" {
      sub(/^[^:]+:[[:space:]]*/, "", $0)
      print
      exit
    }
  ' <<< "${text}"
}

has_collector_namespace() {
  "${KUBECLI}" get namespace "${COLLECTOR_NAMESPACE}" >/dev/null 2>&1
}

has_collector_resource() {
  local resource="$1"
  local name="$2"

  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" get "${resource}" "${name}" >/dev/null 2>&1
}

has_collector_operator_deployment() {
  has_collector_resource deployment "${COLLECTOR_RELEASE}-operator" \
    || has_collector_resource deployment "${COLLECTOR_RELEASE}-opentelemetry-operator"
}

instrumentation_propagators() {
  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" get instrumentations.opentelemetry.io "${COLLECTOR_RELEASE}" \
    -o yaml 2>/dev/null | awk '
      /^spec:/ { in_spec=1; next }
      in_spec && /^status:/ { exit }
      in_spec && /^  propagators:/ { in_props=1; next }
      in_props && /^  - / { sub(/^  - /, ""); print; next }
      in_props && /^  [A-Za-z0-9_.-]+:/ { exit }
      in_props && /^  [^ -]/ { exit }
    ' || true
}

collector_configmap_relay() {
  local name="$1"

  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" get configmap "${name}" \
    -o jsonpath='{.data.relay}' 2>/dev/null || true
}

collector_resource_yaml() {
  local resource="$1"
  local name="$2"

  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" get "${resource}" "${name}" -o yaml 2>/dev/null || true
}

collector_resource_field() {
  local resource="$1"
  local name="$2"
  local path="$3"

  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" get "${resource}" "${name}" -o "jsonpath=${path}" 2>/dev/null || true
}

collector_release_values() {
  helm get values "${COLLECTOR_RELEASE}" -n "${COLLECTOR_NAMESPACE}" --all 2>/dev/null || true
}

operator_deployment_name() {
  if has_collector_resource deployment "${COLLECTOR_RELEASE}-opentelemetry-operator"; then
    printf '%s\n' "${COLLECTOR_RELEASE}-opentelemetry-operator"
    return
  fi

  if has_collector_resource deployment "${COLLECTOR_RELEASE}-operator"; then
    printf '%s\n' "${COLLECTOR_RELEASE}-operator"
    return
  fi

  return 1
}

has_required_propagators() {
  local -a actual
  local line
  local index

  actual=()
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    actual+=("${line}")
  done < <(instrumentation_propagators)

  [[ "${#actual[@]}" -eq "${#REQUIRED_PROPAGATORS[@]}" ]] || return 1

  for index in "${!REQUIRED_PROPAGATORS[@]}"; do
    [[ "${actual[index]}" == "${REQUIRED_PROPAGATORS[index]}" ]] || return 1
  done
}

has_required_otlp_endpoint() {
  local instrumentation_spec

  instrumentation_spec="$(collector_resource_yaml instrumentations.opentelemetry.io "${COLLECTOR_RELEASE}")"
  [[ "${instrumentation_spec}" == *"endpoint: ${REQUIRED_OTLP_GRPC_ENDPOINT}"* ]] || return 1
  [[ "${instrumentation_spec}" == *"name: OTEL_EXPORTER_OTLP_ENDPOINT"* ]] || return 1
  [[ "${instrumentation_spec}" == *"value: ${REQUIRED_OTLP_GRPC_ENDPOINT}"* ]] || return 1
}

has_private_collector_placement() {
  local agent_spec cluster_receiver_spec

  agent_spec="$(collector_resource_yaml daemonset "${COLLECTOR_RELEASE}-agent")"
  cluster_receiver_spec="$(collector_resource_yaml deployment "${COLLECTOR_RELEASE}-k8s-cluster-receiver")"

  [[ "${agent_spec}" == *"eks.amazonaws.com/nodegroup: private"* ]] || return 1
  [[ "${agent_spec}" == *"key: dedicated"* ]] || return 1
  [[ "${agent_spec}" == *"value: otel"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"eks.amazonaws.com/nodegroup: private"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"key: dedicated"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"value: otel"* ]] || return 1
}

agent_service_internal_traffic_policy() {
  collector_resource_field service "${AGENT_SERVICE}" '{.spec.internalTrafficPolicy}'
}

has_agent_service_cluster_routing() {
  [[ "$(agent_service_internal_traffic_policy)" == "${REQUIRED_AGENT_SERVICE_TRAFFIC_POLICY}" ]]
}

secondary_export_configured() {
  local agent_relay cluster_relay agent_spec cluster_receiver_spec

  if ! secondary_o11y_requested; then
    return 0
  fi

  agent_relay="$(collector_configmap_relay "${AGENT_CONFIGMAP}")"
  cluster_relay="$(collector_configmap_relay "${CLUSTER_RECEIVER_CONFIGMAP}")"
  agent_spec="$(collector_resource_yaml daemonset "${COLLECTOR_RELEASE}-agent")"
  cluster_receiver_spec="$(collector_resource_yaml deployment "${COLLECTOR_RELEASE}-k8s-cluster-receiver")"

  [[ "${agent_relay}" == *"signalfx/secondary"* ]] || return 1
  [[ "${agent_relay}" == *"signalfx/histograms_secondary"* ]] || return 1
  [[ "${agent_relay}" == *"otlp_http/secondary"* ]] || return 1
  [[ "${agent_relay}" == *"otlp_http/entities_secondary"* ]] || return 1
  [[ "${cluster_relay}" == *"signalfx/secondary"* ]] || return 1
  [[ "${agent_spec}" == *"name: SPLUNK_OTEL_SECONDARY_REALM"* ]] || return 1
  [[ "${agent_spec}" == *"value: ${SECONDARY_REALM}"* ]] || return 1
  [[ "${agent_spec}" == *"name: SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN"* ]] || return 1
  [[ "${agent_spec}" == *"name: SPLUNK_OTEL_SECONDARY_INGEST_URL"* ]] || return 1
  [[ "${agent_spec}" == *"value: ${SECONDARY_INGEST_URL}"* ]] || return 1
  [[ "${agent_spec}" == *"name: SPLUNK_OTEL_SECONDARY_API_URL"* ]] || return 1
  [[ "${agent_spec}" == *"value: ${SECONDARY_API_URL}"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"name: SPLUNK_OTEL_SECONDARY_REALM"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"value: ${SECONDARY_REALM}"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"name: SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"name: SPLUNK_OTEL_SECONDARY_INGEST_URL"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"value: ${SECONDARY_INGEST_URL}"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"name: SPLUNK_OTEL_SECONDARY_API_URL"* ]] || return 1
  [[ "${cluster_receiver_spec}" == *"value: ${SECONDARY_API_URL}"* ]] || return 1
}

collector_is_compatible() {
  local operator_deployment

  operator_deployment="$(operator_deployment_name || true)"

  has_collector_namespace \
    && [[ -n "${operator_deployment}" ]] \
    && has_collector_resource deployment "${COLLECTOR_RELEASE}-k8s-cluster-receiver" \
    && has_collector_resource daemonset "${COLLECTOR_RELEASE}-agent" \
    && has_collector_resource service "${AGENT_SERVICE}" \
    && has_collector_resource instrumentations.opentelemetry.io "${COLLECTOR_RELEASE}" \
    && has_required_propagators \
    && has_required_otlp_endpoint \
    && has_private_collector_placement \
    && has_agent_service_cluster_routing \
    && secondary_export_configured
}

require_compatible_collector() {
  local operator_deployment
  local -a missing

  missing=()
  operator_deployment="$(operator_deployment_name || true)"

  has_collector_namespace || missing+=("namespace/${COLLECTOR_NAMESPACE}")
  [[ -n "${operator_deployment}" ]] || missing+=("deployment/${COLLECTOR_RELEASE}-operator")
  has_collector_resource deployment "${COLLECTOR_RELEASE}-k8s-cluster-receiver" || missing+=("deployment/${COLLECTOR_RELEASE}-k8s-cluster-receiver")
  has_collector_resource daemonset "${COLLECTOR_RELEASE}-agent" || missing+=("daemonset/${COLLECTOR_RELEASE}-agent")
  has_collector_resource service "${AGENT_SERVICE}" || missing+=("service/${AGENT_SERVICE}")
  has_collector_resource instrumentations.opentelemetry.io "${COLLECTOR_RELEASE}" || missing+=("instrumentation/${COLLECTOR_RELEASE}")
  has_required_propagators || missing+=("instrumentation.propagators=${REQUIRED_PROPAGATORS[*]}")
  has_required_otlp_endpoint || missing+=("instrumentation.exporter.endpoint=${REQUIRED_OTLP_GRPC_ENDPOINT}")
  has_private_collector_placement || missing+=("collector.private-egress-path=nodegroup/private+dedicated=otel")
  has_agent_service_cluster_routing || missing+=("service/${AGENT_SERVICE}.spec.internalTrafficPolicy=${REQUIRED_AGENT_SERVICE_TRAFFIC_POLICY}")
  if secondary_o11y_requested && ! secondary_export_configured; then
    missing+=("secondary-o11y-export")
  fi

  if (( ${#missing[@]} > 0 )); then
    fail "Repo-compatible collector not found. The app manifests target '${COLLECTOR_NAMESPACE}/${COLLECTOR_RELEASE}'. Missing: ${missing[*]}"
  fi
}

ensure_helm_repo() {
  if ! helm repo list 2>/dev/null | awk 'NR > 1 { print $1 }' | grep -Fxq "${HELM_REPO_NAME}"; then
    log "Adding Helm repo ${HELM_REPO_NAME}"
    helm repo add "${HELM_REPO_NAME}" "${HELM_REPO_URL}" >/dev/null
  fi

  log "Updating Helm repo ${HELM_REPO_NAME}"
  helm repo update "${HELM_REPO_NAME}" >/dev/null
}

resolve_chart_version() {
  if [[ -n "${CHART_VERSION:-}" ]]; then
    printf '%s\n' "${CHART_VERSION}"
    return
  fi

  printf '%s\n' "${DEFAULT_CHART_VERSION}"
}

desired_environment_label() {
  printf '%s\n' "${SPLUNK_DEPLOYMENT_ENVIRONMENT:-streaming-app}"
}

primary_runtime_values_match_requested() {
  local release_values actual_cluster_name actual_environment actual_realm actual_access_token

  release_values="$(collector_release_values)"
  [[ -n "${release_values}" ]] || return 1

  actual_cluster_name="$(yaml_unquote "$(yaml_top_level_scalar "${release_values}" "clusterName")")"
  actual_environment="$(yaml_unquote "$(yaml_top_level_scalar "${release_values}" "environment")")"
  actual_realm="$(yaml_unquote "$(yaml_nested_scalar "${release_values}" "splunkObservability" "realm")")"
  actual_access_token="$(yaml_unquote "$(yaml_nested_scalar "${release_values}" "splunkObservability" "accessToken")")"

  [[ "${actual_cluster_name}" == "${CLUSTER_NAME}" ]] || return 1
  [[ "${actual_environment}" == "$(desired_environment_label)" ]] || return 1
  [[ "${actual_realm}" == "${SPLUNK_REALM}" ]] || return 1
  [[ "${actual_access_token}" == "${SPLUNK_ACCESS_TOKEN}" ]] || return 1
}

collector_is_reconciled() {
  collector_is_compatible && primary_runtime_values_match_requested
}

write_runtime_values() {
  local path="$1"
  local environment_label="${SPLUNK_DEPLOYMENT_ENVIRONMENT:-streaming-app}"

  cat > "${path}" <<EOF
clusterName: $(yaml_quote "${CLUSTER_NAME}")
environment: $(yaml_quote "${environment_label}")
splunkObservability:
  realm: $(yaml_quote "${SPLUNK_REALM}")
  accessToken: $(yaml_quote "${SPLUNK_ACCESS_TOKEN}")
EOF

  if secondary_o11y_requested; then
    cat >> "${path}" <<EOF
agent:
  extraEnvs:
    - name: SPLUNK_OTEL_SECONDARY_REALM
      value: $(yaml_quote "${SECONDARY_REALM}")
    - name: SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN
      value: $(yaml_quote "${SECONDARY_ACCESS_TOKEN}")
    - name: SPLUNK_OTEL_SECONDARY_INGEST_URL
      value: $(yaml_quote "${SECONDARY_INGEST_URL}")
    - name: SPLUNK_OTEL_SECONDARY_API_URL
      value: $(yaml_quote "${SECONDARY_API_URL}")
clusterReceiver:
  extraEnvs:
    - name: SPLUNK_OTEL_SECONDARY_REALM
      value: $(yaml_quote "${SECONDARY_REALM}")
    - name: SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN
      value: $(yaml_quote "${SECONDARY_ACCESS_TOKEN}")
    - name: SPLUNK_OTEL_SECONDARY_INGEST_URL
      value: $(yaml_quote "${SECONDARY_INGEST_URL}")
    - name: SPLUNK_OTEL_SECONDARY_API_URL
      value: $(yaml_quote "${SECONDARY_API_URL}")
EOF
  fi
}

ensure_agent_service_cluster_routing() {
  if has_agent_service_cluster_routing; then
    log "Collector agent Service already routes OTLP traffic cluster-wide"
    return
  fi

  log "Patching Service ${AGENT_SERVICE} to internalTrafficPolicy=${REQUIRED_AGENT_SERVICE_TRAFFIC_POLICY}"
  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" patch service "${AGENT_SERVICE}" \
    --type=merge \
    -p '{"spec":{"internalTrafficPolicy":"Cluster"}}' >/dev/null

  has_agent_service_cluster_routing \
    || fail "collector agent Service ${AGENT_SERVICE} did not retain internalTrafficPolicy=${REQUIRED_AGENT_SERVICE_TRAFFIC_POLICY}"
}

wait_for_instrumentation() {
  local elapsed=0

  while (( elapsed <= INSTRUMENTATION_WAIT_SECONDS )); do
    if has_collector_resource instrumentations.opentelemetry.io "${COLLECTOR_RELEASE}"; then
      return
    fi

    sleep 2
    elapsed=$((elapsed + 2))
  done

  fail "Timed out waiting for instrumentation/${COLLECTOR_RELEASE} in namespace ${COLLECTOR_NAMESPACE}"
}

install_or_upgrade_collector() {
  local operator_deployment
  local runtime_values
  local chart_version
  local -a helm_args

  require_value "${SPLUNK_REALM:-}" "SPLUNK_REALM"
  require_value "${SPLUNK_ACCESS_TOKEN:-}" "SPLUNK_ACCESS_TOKEN"
  require_value "${CLUSTER_NAME:-}" "collector cluster name"
  chart_version="$(resolve_chart_version)"

  ensure_helm_repo

  TEMP_DIR="$(mktemp -d)"
  runtime_values="${TEMP_DIR}/collector.runtime.values.yaml"
  write_runtime_values "${runtime_values}"

  helm_args=(
    upgrade
    --install
    "${COLLECTOR_RELEASE}"
    "${CHART_NAME}"
    --namespace
    "${COLLECTOR_NAMESPACE}"
    --create-namespace
    --wait
    --timeout
    "${ROLLOUT_TIMEOUT}"
    --force-conflicts
    --version
    "${chart_version}"
    -f
    "${REPO_ROOT}/k8s/otel-splunk/collector.values.yaml"
  )

  if helm_supports_path_post_renderer; then
    helm_args+=(
      --post-renderer
      "${POST_RENDERER_SCRIPT}"
    )
  fi

  if [[ "${PLATFORM}" == "openshift" ]]; then
    helm_args+=(
      -f
      "${REPO_ROOT}/k8s/otel-splunk/collector.openshift.values.yaml"
    )
  fi

  if secondary_o11y_requested; then
    helm_args+=(
      -f
      "${REPO_ROOT}/k8s/otel-splunk/collector.secondary-o11y.values.yaml"
    )
  fi

  helm_args+=(
    -f
    "${runtime_values}"
  )

  log "Using Splunk OTel chart version ${chart_version}"
  if secondary_o11y_requested; then
    log "Enabling secondary Splunk Observability export to realm ${SECONDARY_REALM}"
  fi
  if helm_supports_path_post_renderer; then
    log "Applying Helm post-renderer ${POST_RENDERER_SCRIPT}"
  else
    log "Helm $(helm version --short 2>/dev/null || helm version 2>/dev/null || printf 'unknown') does not support path-based post-renderers; relying on the post-upgrade Service patch"
  fi
  log "Installing Splunk OTel collector release ${COLLECTOR_RELEASE} in namespace ${COLLECTOR_NAMESPACE}"
  SPLUNK_OTEL_AGENT_SERVICE_NAME="${AGENT_SERVICE}" helm "${helm_args[@]}"

  wait_for_instrumentation
  operator_deployment="$(operator_deployment_name || true)"
  [[ -n "${operator_deployment}" ]] || fail "Could not find the collector operator deployment after install"
  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" rollout status "deployment/${operator_deployment}" --timeout="${ROLLOUT_TIMEOUT}"
  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" rollout status "deployment/${COLLECTOR_RELEASE}-k8s-cluster-receiver" --timeout="${ROLLOUT_TIMEOUT}"
  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" rollout status "daemonset/${COLLECTOR_RELEASE}-agent" --timeout="${ROLLOUT_TIMEOUT}"
  ensure_agent_service_cluster_routing
  primary_runtime_values_match_requested \
    || fail "collector release did not retain the requested primary runtime values"
}

MODE="${MODE:-reuse}"
PLATFORM="${PLATFORM:-auto}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-600s}"

case "${MODE}" in
  reuse|install-if-missing) ;;
  *)
    fail "mode must be one of: reuse, install-if-missing"
    ;;
esac

if [[ "${PLATFORM}" == "auto" ]]; then
  PLATFORM="$(detect_platform)"
fi

case "${PLATFORM}" in
  kubernetes|openshift) ;;
  *)
    fail "platform must be one of: auto, kubernetes, openshift"
    ;;
esac

if [[ -z "${KUBECLI}" ]]; then
  if [[ "${PLATFORM}" == "openshift" ]]; then
    KUBECLI="oc"
  else
    KUBECLI="kubectl"
  fi
fi

if [[ -z "${CLUSTER_NAME}" ]]; then
  CLUSTER_NAME="$("${KUBECLI}" config current-context 2>/dev/null || true)"
fi

validate_secondary_o11y_inputs
normalize_secondary_endpoints
verify_repo_layout
verify_tooling

log "Splunk OTel mode: ${MODE}"
log "Splunk OTel platform: ${PLATFORM}"
log "Splunk OTel CLI: ${KUBECLI}"
log "Splunk OTel namespace: ${COLLECTOR_NAMESPACE}"
log "Splunk OTel release: ${COLLECTOR_RELEASE}"
if [[ -n "${CLUSTER_NAME}" ]]; then
  log "Splunk OTel cluster name: ${CLUSTER_NAME}"
fi
if secondary_o11y_requested; then
  log "Splunk OTel secondary realm: ${SECONDARY_REALM}"
  log "Splunk OTel secondary ingest URL: ${SECONDARY_INGEST_URL}"
  log "Splunk OTel secondary API URL: ${SECONDARY_API_URL}"
fi

if [[ "${MODE}" == "reuse" ]]; then
  require_compatible_collector
  log "Compatible Splunk OTel collector already present"
  exit 0
fi

if collector_is_reconciled; then
  log "Compatible Splunk OTel collector already present; skipping install"
  exit 0
fi

if collector_is_compatible && ! primary_runtime_values_match_requested; then
  log "Collector shape matches repo requirements, but live runtime values differ; reconciling release"
fi

install_or_upgrade_collector
log "Splunk OTel collector is ready for repo-managed Java and Node.js auto-instrumentation"
