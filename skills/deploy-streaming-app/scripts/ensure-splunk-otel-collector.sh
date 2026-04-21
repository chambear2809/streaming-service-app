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
COLLECTOR_NAMESPACE="otel-splunk"
COLLECTOR_RELEASE="splunk-otel-collector"
HELM_REPO_NAME="splunk-otel-collector-chart"
HELM_REPO_URL="https://signalfx.github.io/splunk-otel-collector-chart"
CHART_NAME="${HELM_REPO_NAME}/splunk-otel-collector"
CHART_VERSION="${SPLUNK_OTEL_HELM_CHART_VERSION:-0.148.0}"
REQUIRED_PROPAGATORS=("baggage" "b3" "tracecontext")
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

ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"
load_env_file "${ENV_FILE}"

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
}

verify_tooling() {
  require_command "${KUBECLI}"

  if [[ "${MODE}" == "install-if-missing" ]]; then
    require_command helm
  fi
}

yaml_quote() {
  local value="$1"
  value="${value//\'/\'\'}"
  printf "'%s'" "${value}"
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
    -o jsonpath='{range .spec.propagators[*]}{.}{"\n"}{end}' 2>/dev/null || true
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

collector_is_compatible() {
  has_collector_namespace \
    && has_collector_operator_deployment \
    && has_collector_resource deployment "${COLLECTOR_RELEASE}-k8s-cluster-receiver" \
    && has_collector_resource daemonset "${COLLECTOR_RELEASE}-agent" \
    && has_collector_resource instrumentations.opentelemetry.io "${COLLECTOR_RELEASE}" \
    && has_required_propagators
}

require_compatible_collector() {
  local -a missing

  missing=()

  has_collector_namespace || missing+=("namespace/${COLLECTOR_NAMESPACE}")
  has_collector_operator_deployment || missing+=("deployment/${COLLECTOR_RELEASE}-operator")
  has_collector_resource deployment "${COLLECTOR_RELEASE}-k8s-cluster-receiver" || missing+=("deployment/${COLLECTOR_RELEASE}-k8s-cluster-receiver")
  has_collector_resource daemonset "${COLLECTOR_RELEASE}-agent" || missing+=("daemonset/${COLLECTOR_RELEASE}-agent")
  has_collector_resource instrumentations.opentelemetry.io "${COLLECTOR_RELEASE}" || missing+=("instrumentation/${COLLECTOR_RELEASE}")
  has_required_propagators || missing+=("instrumentation.propagators=${REQUIRED_PROPAGATORS[*]}")

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
  local runtime_values
  local -a helm_args

  require_value "${SPLUNK_REALM:-}" "SPLUNK_REALM"
  require_value "${SPLUNK_ACCESS_TOKEN:-}" "SPLUNK_ACCESS_TOKEN"
  require_value "${CLUSTER_NAME:-}" "collector cluster name"

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
    --version
    "${CHART_VERSION}"
    -f
    "${REPO_ROOT}/k8s/otel-splunk/collector.values.yaml"
  )

  if [[ "${PLATFORM}" == "openshift" ]]; then
    helm_args+=(
      -f
      "${REPO_ROOT}/k8s/otel-splunk/collector.openshift.values.yaml"
    )
  fi

  helm_args+=(
    -f
    "${runtime_values}"
  )

  log "Installing Splunk OTel collector release ${COLLECTOR_RELEASE} in namespace ${COLLECTOR_NAMESPACE}"
  helm "${helm_args[@]}"

  wait_for_instrumentation
  if has_collector_resource deployment "${COLLECTOR_RELEASE}-operator"; then
    "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" rollout status "deployment/${COLLECTOR_RELEASE}-operator" --timeout="${ROLLOUT_TIMEOUT}"
  else
    "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" rollout status "deployment/${COLLECTOR_RELEASE}-opentelemetry-operator" --timeout="${ROLLOUT_TIMEOUT}"
  fi
  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" rollout status "deployment/${COLLECTOR_RELEASE}-k8s-cluster-receiver" --timeout="${ROLLOUT_TIMEOUT}"
  "${KUBECLI}" -n "${COLLECTOR_NAMESPACE}" rollout status "daemonset/${COLLECTOR_RELEASE}-agent" --timeout="${ROLLOUT_TIMEOUT}"
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

if [[ "${MODE}" == "reuse" ]]; then
  require_compatible_collector
  log "Compatible Splunk OTel collector already present"
  exit 0
fi

if collector_is_compatible; then
  log "Compatible Splunk OTel collector already present; skipping install"
  exit 0
fi

install_or_upgrade_collector
log "Splunk OTel collector is ready for repo-managed Java and Node.js auto-instrumentation"
