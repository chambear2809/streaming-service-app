#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel 2>/dev/null || true)"
FRONTEND_DIR=""
ENV_FILE="${ENV_FILE-}"
NAMESPACE="${NAMESPACE-}"
PLATFORM="${PLATFORM-}"
KUBECLI="${KUBECLI-}"
FRONTEND_SERVICE_TYPE="${FRONTEND_SERVICE_TYPE-}"
RTSP_SERVICE_TYPE="${RTSP_SERVICE_TYPE-}"
FRONTEND_LOAD_BALANCER_SCHEME="${FRONTEND_LOAD_BALANCER_SCHEME-}"
RTSP_LOAD_BALANCER_SCHEME="${RTSP_LOAD_BALANCER_SCHEME-}"
FRONTEND_AWS_LOAD_BALANCER_TYPE="${FRONTEND_AWS_LOAD_BALANCER_TYPE-}"
RTSP_AWS_LOAD_BALANCER_TYPE="${RTSP_AWS_LOAD_BALANCER_TYPE-}"
CLUSTER_LABEL="${CLUSTER_LABEL-}"
ENVIRONMENT_LABEL="${ENVIRONMENT_LABEL-}"
REGION_LABEL="${REGION_LABEL-}"
CONTROL_ROOM_LABEL="${CONTROL_ROOM_LABEL-}"
PUBLIC_RTSP_URL="${PUBLIC_RTSP_URL-}"
APP_VERSION="${APP_VERSION-}"
SPLUNK_RUM_APP_NAME="${SPLUNK_RUM_APP_NAME-}"
SPLUNK_RUM_ACCESS_TOKEN="${SPLUNK_RUM_ACCESS_TOKEN-}"
FRONTEND_ROUTE_NAME="${FRONTEND_ROUTE_NAME-}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT-}"
ROLLOUT_SNAPSHOT_INTERVAL_SECONDS="${ROLLOUT_SNAPSHOT_INTERVAL_SECONDS-}"
EXTERNAL_URL_TIMEOUT_SECONDS="${EXTERNAL_URL_TIMEOUT_SECONDS-}"
SPLUNK_OTEL_MODE="${SPLUNK_OTEL_MODE-}"
SPLUNK_OTEL_CLUSTER_NAME="${SPLUNK_OTEL_CLUSTER_NAME-}"
SPLUNK_OTEL_HELPER_SCRIPT="${SPLUNK_OTEL_HELPER_SCRIPT-}"
DEMO_AUTH_SECRET="${DEMO_AUTH_SECRET-}"
DEMO_AUTH_PASSWORD="${DEMO_AUTH_PASSWORD-}"
DEMO_AUTH_SECRET_NAME="streaming-demo-auth"
DEMO_AUTH_PASSWORD_DEFAULT="password123"
DEMO_AUTH_PASSWORD_SOURCE="provided"
TEMP_DIR=""
RENDERED_NAMESPACE=""

usage() {
  cat <<'EOF'
Usage: deploy-demo.sh [options]

Deploy the streaming-service-app demo stack to Kubernetes or OpenShift without
editing the checked-in manifests.

Options:
  --platform <auto|kubernetes|openshift>
  --namespace <name>
  --cli <kubectl|oc>
  --frontend-service-type <ClusterIP|NodePort|LoadBalancer>
  --frontend-load-balancer-scheme <internet-facing|internal>
  --frontend-aws-load-balancer-type <type>
  --rtsp-service-type <ClusterIP|NodePort|LoadBalancer>
  --rtsp-load-balancer-scheme <internet-facing|internal>
  --rtsp-aws-load-balancer-type <type>
  --cluster-label <label>
  --environment-label <label>
  --region-label <label>
  --control-room-label <label>
  --public-rtsp-url <url>
  --app-version <version>
  --splunk-otel-mode <skip|reuse|install-if-missing>
  --splunk-otel-cluster-name <name>
  --rollout-timeout <duration>
  --rollout-snapshot-interval <seconds>
  --external-url-timeout <seconds>
  --help

Environment variables:
  ENV_FILE                              Optional path to a repo-style env file
  SPLUNK_REALM / SPLUNK_ACCESS_TOKEN
                                        Optional frontend sourcemap upload credentials
  SPLUNK_SOURCEMAP_UPLOAD_TOKEN         Optional explicit sourcemap upload token override
  SPLUNK_OTEL_CLUSTER_NAME             Optional collector cluster name override
  SPLUNK_OTEL_HELM_CHART_VERSION       Optional collector Helm chart version override
  SPLUNK_RUM_APP_NAME                  Optional frontend RUM app name
  DEMO_AUTH_PASSWORD / DEMO_AUTH_SECRET
                                        Optional demo login credentials to persist
  ROLLOUT_SNAPSHOT_INTERVAL_SECONDS   How often to print rollout progress snapshots
  EXTERNAL_URL_TIMEOUT_SECONDS         How long to wait for Route/LB hosts
EOF
}

log() {
  printf '[deploy-streaming-app] %s\n' "$*"
}

warn() {
  printf '[deploy-streaming-app] WARN: %s\n' "$*" >&2
}

fail() {
  printf '[deploy-streaming-app] ERROR: %s\n' "$*" >&2
  exit 1
}

if [[ -z "${REPO_ROOT}" ]]; then
  fail "Could not determine the repository root. Run this script from within the streaming-service-app git checkout."
fi

FRONTEND_DIR="${REPO_ROOT}/frontend"
ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"

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

load_env_file "${ENV_FILE}"

cleanup() {
  if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
    rm -rf "${TEMP_DIR}"
  fi
}

trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform)
      PLATFORM="${2:?missing value for --platform}"
      shift 2
      ;;
    --namespace)
      NAMESPACE="${2:?missing value for --namespace}"
      shift 2
      ;;
    --cli)
      KUBECLI="${2:?missing value for --cli}"
      shift 2
      ;;
    --frontend-service-type)
      FRONTEND_SERVICE_TYPE="${2:?missing value for --frontend-service-type}"
      shift 2
      ;;
    --frontend-load-balancer-scheme)
      FRONTEND_LOAD_BALANCER_SCHEME="${2:?missing value for --frontend-load-balancer-scheme}"
      shift 2
      ;;
    --frontend-aws-load-balancer-type)
      FRONTEND_AWS_LOAD_BALANCER_TYPE="${2:?missing value for --frontend-aws-load-balancer-type}"
      shift 2
      ;;
    --rtsp-service-type)
      RTSP_SERVICE_TYPE="${2:?missing value for --rtsp-service-type}"
      shift 2
      ;;
    --rtsp-load-balancer-scheme)
      RTSP_LOAD_BALANCER_SCHEME="${2:?missing value for --rtsp-load-balancer-scheme}"
      shift 2
      ;;
    --rtsp-aws-load-balancer-type)
      RTSP_AWS_LOAD_BALANCER_TYPE="${2:?missing value for --rtsp-aws-load-balancer-type}"
      shift 2
      ;;
    --cluster-label)
      CLUSTER_LABEL="${2:?missing value for --cluster-label}"
      shift 2
      ;;
    --environment-label)
      ENVIRONMENT_LABEL="${2:?missing value for --environment-label}"
      shift 2
      ;;
    --region-label)
      REGION_LABEL="${2:?missing value for --region-label}"
      shift 2
      ;;
    --control-room-label)
      CONTROL_ROOM_LABEL="${2:?missing value for --control-room-label}"
      shift 2
      ;;
    --public-rtsp-url)
      PUBLIC_RTSP_URL="${2-}"
      shift 2
      ;;
    --app-version)
      APP_VERSION="${2:?missing value for --app-version}"
      shift 2
      ;;
    --splunk-otel-mode)
      SPLUNK_OTEL_MODE="${2:?missing value for --splunk-otel-mode}"
      shift 2
      ;;
    --splunk-otel-cluster-name)
      SPLUNK_OTEL_CLUSTER_NAME="${2:?missing value for --splunk-otel-cluster-name}"
      shift 2
      ;;
    --rollout-timeout)
      ROLLOUT_TIMEOUT="${2:?missing value for --rollout-timeout}"
      shift 2
      ;;
    --rollout-snapshot-interval)
      ROLLOUT_SNAPSHOT_INTERVAL_SECONDS="${2:?missing value for --rollout-snapshot-interval}"
      shift 2
      ;;
    --external-url-timeout)
      EXTERNAL_URL_TIMEOUT_SECONDS="${2:?missing value for --external-url-timeout}"
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

require_dir() {
  [[ -d "$1" ]] || fail "required directory not found: $1"
}

validate_dns_label() {
  local value="$1"
  local label="$2"

  [[ "${value}" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || fail "${label} must be a lowercase RFC 1123 label"
  [[ "${#value}" -le 63 ]] || fail "${label} must be 63 characters or fewer"
}

validate_non_negative_integer() {
  local value="$1"
  local label="$2"

  [[ "${value}" =~ ^[0-9]+$ ]] || fail "${label} must be a non-negative integer"
}

escape_sed_replacement() {
  printf '%s' "$1" | sed -e 's/[\/&]/\\&/g'
}

verify_repo_layout() {
  require_dir "${FRONTEND_DIR}"
  require_dir "${REPO_ROOT}/services/content-service"
  require_dir "${REPO_ROOT}/services/media-service"
  require_dir "${REPO_ROOT}/services/user-service"
  require_dir "${REPO_ROOT}/services/billing-service"
  require_dir "${REPO_ROOT}/services/ad-service"
  require_file "${FRONTEND_DIR}/package.json"
  require_file "${REPO_ROOT}/k8s/frontend/deployment.yaml"
  require_file "${REPO_ROOT}/k8s/frontend/service.yaml"
  require_file "${REPO_ROOT}/k8s/backend-demo/postgres.yaml"
  require_file "${REPO_ROOT}/k8s/backend-demo/content-service.yaml"
  require_file "${REPO_ROOT}/k8s/backend-demo/media-service.yaml"
  require_file "${REPO_ROOT}/k8s/backend-demo/user-service.yaml"
  require_file "${REPO_ROOT}/k8s/backend-demo/billing-service.yaml"
  require_file "${REPO_ROOT}/k8s/backend-demo/ad-service.yaml"
  require_file "${REPO_ROOT}/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"
}

verify_tooling() {
  require_command tar
  require_command sed
  require_command head
  require_command tr
  require_command npm
  require_command "${KUBECLI}"

  if [[ -z "${APP_VERSION}" ]]; then
    require_command git
  fi

  if [[ "${PLATFORM}" == "openshift" ]]; then
    require_command oc
  fi
}

detect_platform() {
  if command_exists oc && oc api-resources --api-group=route.openshift.io >/dev/null 2>&1; then
    printf 'openshift\n'
  else
    printf 'kubernetes\n'
  fi
}

validate_service_type() {
  case "$1" in
    ClusterIP|NodePort|LoadBalancer) ;;
    *)
      fail "invalid service type '$1'"
      ;;
  esac
}

validate_load_balancer_scheme() {
  case "$1" in
    ''|internet-facing|internal) ;;
    *)
      fail "invalid load balancer scheme '$1'"
      ;;
  esac
}

validate_splunk_otel_mode() {
  case "$1" in
    skip|reuse|install-if-missing) ;;
    *)
      fail "invalid Splunk OTel mode '$1'"
      ;;
  esac
}

create_namespace() {
  if [[ "${PLATFORM}" == "openshift" ]]; then
    if ! oc get project "${NAMESPACE}" >/dev/null 2>&1; then
      oc new-project "${NAMESPACE}" >/dev/null
    fi
  else
    "${KUBECLI}" create namespace "${NAMESPACE}" --dry-run=client -o yaml | "${KUBECLI}" apply -f - >/dev/null
  fi
}

render_manifest() {
  sed \
    -e "s/streaming-service-app/${RENDERED_NAMESPACE}/g" \
    -e 's#/root/\.m2#/tmp/.m2#g' \
    -e 's#mvn #HOME=/tmp mvn -Dmaven.repo.local=/tmp/.m2/repository #g' \
    "$1"
}

apply_manifest() {
  render_manifest "$1" | "${KUBECLI}" apply -f -
}

create_or_update_configmap() {
  local name="$1"
  shift
  "${KUBECLI}" -n "${NAMESPACE}" create configmap "${name}" "$@" --dry-run=client -o yaml | "${KUBECLI}" apply --server-side -f -
}

create_or_update_secret() {
  local name="$1"
  shift
  "${KUBECLI}" -n "${NAMESPACE}" create secret generic "${name}" "$@" --dry-run=client -o yaml | "${KUBECLI}" apply --server-side -f -
}

secret_field_b64() {
  local name="$1"
  local key="$2"
  "${KUBECLI}" -n "${NAMESPACE}" get secret "${name}" -o "jsonpath={.data.${key}}" 2>/dev/null || true
}

ensure_splunk_otel_collector() {
  local -a helper_args

  if [[ "${SPLUNK_OTEL_MODE}" == "skip" ]]; then
    return
  fi

  helper_args=(
    "${SPLUNK_OTEL_HELPER_SCRIPT}"
    --mode
    "${SPLUNK_OTEL_MODE}"
    --platform
    "${PLATFORM}"
    --cli
    "${KUBECLI}"
    --timeout
    "${ROLLOUT_TIMEOUT}"
    --env-file
    "${ENV_FILE}"
  )

  if [[ -n "${SPLUNK_OTEL_CLUSTER_NAME}" ]]; then
    helper_args+=(
      --cluster-name
      "${SPLUNK_OTEL_CLUSTER_NAME}"
    )
  fi

  log "Ensuring Splunk OTel collector (${SPLUNK_OTEL_MODE})"
  env \
    SPLUNK_REALM="${SPLUNK_REALM:-}" \
    SPLUNK_ACCESS_TOKEN="${SPLUNK_ACCESS_TOKEN:-}" \
    SPLUNK_DEPLOYMENT_ENVIRONMENT="${SPLUNK_DEPLOYMENT_ENVIRONMENT:-}" \
    SPLUNK_OTEL_HELM_CHART_VERSION="${SPLUNK_OTEL_HELM_CHART_VERSION:-}" \
    bash "${helper_args[@]}"
}

random_alnum() {
  local length="$1"
  local value=""

  set +o pipefail
  value="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${length}")"
  set -o pipefail

  [[ -n "${value}" ]] || fail "unable to generate a random credential"
  printf '%s' "${value}"
}

package_service_source() {
  local service_dir="$1"
  local archive_path="$2"
  shift 2
  local -a excludes
  local -a archive_paths
  local extra_path

  excludes=(
    "--exclude=${service_dir}/target"
    "--exclude=${service_dir}/build"
    "--exclude=${service_dir}/.idea"
    "--exclude=${service_dir}/.vscode"
    "--exclude=${service_dir}/.DS_Store"
  )
  archive_paths=(
    "pom.xml"
    "${service_dir}"
  )

  for extra_path in "$@"; do
    excludes+=("--exclude=${service_dir}/${extra_path}")
  done

  tar -C "${REPO_ROOT}" "${excludes[@]}" -czf "${archive_path}" "${archive_paths[@]}"
}

ensure_demo_auth_secret() {
  if [[ -z "${DEMO_AUTH_SECRET//[[:space:]]/}" ]]; then
    DEMO_AUTH_SECRET="$(random_alnum 48)"
  fi

  if [[ -z "${DEMO_AUTH_PASSWORD//[[:space:]]/}" ]]; then
    DEMO_AUTH_PASSWORD="${DEMO_AUTH_PASSWORD_DEFAULT}"
    DEMO_AUTH_PASSWORD_SOURCE="default"
  fi

  create_or_update_secret "${DEMO_AUTH_SECRET_NAME}" \
    --from-literal=DEMO_AUTH_SECRET="${DEMO_AUTH_SECRET}" \
    --from-literal=DEMO_AUTH_PASSWORD="${DEMO_AUTH_PASSWORD}"

  [[ -n "$(secret_field_b64 "${DEMO_AUTH_SECRET_NAME}" DEMO_AUTH_SECRET)" ]] \
    || fail "deployed secret ${DEMO_AUTH_SECRET_NAME} is missing DEMO_AUTH_SECRET"
  [[ -n "$(secret_field_b64 "${DEMO_AUTH_SECRET_NAME}" DEMO_AUTH_PASSWORD)" ]] \
    || fail "deployed secret ${DEMO_AUTH_SECRET_NAME} is missing DEMO_AUTH_PASSWORD"
}

restart_and_wait() {
  local deployment="$1"
  "${KUBECLI}" -n "${NAMESPACE}" rollout restart "deployment/${deployment}" >/dev/null
  wait_for_deployment "${deployment}"
}

log_block() {
  local line

  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    log "  ${line}"
  done <<< "$1"
}

deployment_selector() {
  local deployment="$1"
  printf 'app.kubernetes.io/name=%s\n' "${deployment}"
}

latest_rollout_pod_ref() {
  local deployment="$1"
  local selector
  local pod_refs

  selector="$(deployment_selector "${deployment}")"
  pod_refs="$("${KUBECLI}" -n "${NAMESPACE}" get pods -l "${selector}" --sort-by=.metadata.creationTimestamp -o name 2>/dev/null || true)"
  [[ -n "${pod_refs}" ]] || return 0

  printf '%s\n' "${pod_refs}" | tail -n 1
}

show_rollout_pod_table() {
  local deployment="$1"
  local selector
  local pod_table

  selector="$(deployment_selector "${deployment}")"
  pod_table="$("${KUBECLI}" -n "${NAMESPACE}" get pods -l "${selector}" -o wide 2>/dev/null || true)"
  [[ -n "${pod_table}" ]] || return 0

  log "Rollout snapshot for deployment/${deployment}"
  log_block "${pod_table}"
}

show_rollout_pod_health() {
  local deployment="$1"
  local selector
  local pod_refs
  local pod_ref
  local pod_name
  local pod_summary

  selector="$(deployment_selector "${deployment}")"
  pod_refs="$("${KUBECLI}" -n "${NAMESPACE}" get pods -l "${selector}" -o name 2>/dev/null || true)"
  [[ -n "${pod_refs}" ]] || return 0

  while IFS= read -r pod_ref; do
    [[ -n "${pod_ref}" ]] || continue
    pod_name="${pod_ref#pod/}"
    pod_summary="$("${KUBECLI}" -n "${NAMESPACE}" get "${pod_ref}" -o jsonpath='{range .status.initContainerStatuses[*]}init:{.name}:restart={.restartCount}:wait={.state.waiting.reason}:last={.lastState.terminated.reason}{" "}{end}{range .status.containerStatuses[*]}container:{.name}:ready={.ready}:restart={.restartCount}:wait={.state.waiting.reason}:last={.lastState.terminated.reason}{" "}{end}' 2>/dev/null || true)"
    [[ -n "${pod_summary}" ]] || continue
    log "  pod ${pod_name}: ${pod_summary}"
  done <<< "${pod_refs}"
}

show_rollout_metrics() {
  local deployment="$1"
  local selector
  local metrics

  selector="$(deployment_selector "${deployment}")"
  metrics="$("${KUBECLI}" -n "${NAMESPACE}" top pod -l "${selector}" --containers 2>/dev/null || true)"
  [[ -n "${metrics}" ]] || return 0

  log "  metrics:"
  log_block "${metrics}"
}

show_media_rollout_progress() {
  local pod_ref
  local pod_name
  local latest_log
  local segment_count
  local endpoints

  pod_ref="$(latest_rollout_pod_ref media-service-demo)"
  [[ -n "${pod_ref}" ]] || return 0

  pod_name="${pod_ref#pod/}"

  latest_log="$("${KUBECLI}" -n "${NAMESPACE}" logs "${pod_name}" -c stage-demo-movie --tail=1 2>/dev/null || true)"
  if [[ -n "${latest_log}" ]]; then
    log "  media init: ${latest_log}"
  fi

  segment_count="$("${KUBECLI}" -n "${NAMESPACE}" exec "${pod_name}" -c app -- sh -c 'find /opt/demo/house-loop-segments -maxdepth 1 -type f -name "*.mp4" | wc -l' 2>/dev/null | tr -d '[:space:]' || true)"
  if [[ "${segment_count}" =~ ^[0-9]+$ ]]; then
    log "  media init segment files: ${segment_count}"
  fi

  endpoints="$("${KUBECLI}" -n "${NAMESPACE}" get endpoints media-service-demo media-service-demo-rtsp -o wide 2>/dev/null || true)"
  [[ -n "${endpoints}" ]] || return 0

  log "  media endpoints:"
  log_block "${endpoints}"
}

print_rollout_snapshot() {
  local deployment="$1"

  show_rollout_pod_table "${deployment}"
  show_rollout_pod_health "${deployment}"
  show_rollout_metrics "${deployment}"

  if [[ "${deployment}" == "media-service-demo" ]]; then
    show_media_rollout_progress
  fi
}

wait_for_deployment() {
  local deployment="$1"
  local rollout_pid=0
  local rollout_status=0
  local next_snapshot_epoch
  local current_epoch

  log "Waiting for deployment/${deployment} rollout"

  if (( ROLLOUT_SNAPSHOT_INTERVAL_SECONDS <= 0 )); then
    "${KUBECLI}" -n "${NAMESPACE}" rollout status "deployment/${deployment}" --timeout="${ROLLOUT_TIMEOUT}"
    return
  fi

  "${KUBECLI}" -n "${NAMESPACE}" rollout status "deployment/${deployment}" --timeout="${ROLLOUT_TIMEOUT}" &
  rollout_pid="$!"
  next_snapshot_epoch=$(( $(date +%s) + ROLLOUT_SNAPSHOT_INTERVAL_SECONDS ))

  while kill -0 "${rollout_pid}" 2>/dev/null; do
    current_epoch="$(date +%s)"
    if (( current_epoch >= next_snapshot_epoch )); then
      print_rollout_snapshot "${deployment}"
      next_snapshot_epoch=$(( current_epoch + ROLLOUT_SNAPSHOT_INTERVAL_SECONDS ))
    fi
    sleep 1
  done

  wait "${rollout_pid}" || rollout_status=$?
  if (( rollout_status != 0 )); then
    print_rollout_snapshot "${deployment}"
    return "${rollout_status}"
  fi
}

patch_service_type() {
  local service_name="$1"
  local service_type="$2"
  "${KUBECLI}" -n "${NAMESPACE}" patch service "${service_name}" --type merge -p "{\"spec\":{\"type\":\"${service_type}\"}}" >/dev/null
}

patch_service_annotation() {
  local service_name="$1"
  local annotation_key="$2"
  local annotation_value="$3"

  [[ -n "${annotation_value}" ]] || return 0

  "${KUBECLI}" -n "${NAMESPACE}" patch service "${service_name}" --type merge \
    -p "{\"metadata\":{\"annotations\":{\"${annotation_key}\":\"${annotation_value}\"}}}" >/dev/null
}

patch_service_load_balancer_scheme() {
  local service_name="$1"
  local scheme="$2"

  patch_service_annotation "${service_name}" "service.beta.kubernetes.io/aws-load-balancer-scheme" "${scheme}"
}

patch_service_aws_load_balancer_type() {
  local service_name="$1"
  local load_balancer_type="$2"

  patch_service_annotation "${service_name}" "service.beta.kubernetes.io/aws-load-balancer-type" "${load_balancer_type}"
}

discover_service_host() {
  local service_name="$1"
  local hostname
  local ip

  hostname="$("${KUBECLI}" -n "${NAMESPACE}" get service "${service_name}" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
  ip="$("${KUBECLI}" -n "${NAMESPACE}" get service "${service_name}" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"

  if [[ -n "${hostname}" ]]; then
    printf '%s\n' "${hostname}"
  elif [[ -n "${ip}" ]]; then
    printf '%s\n' "${ip}"
  else
    printf '\n'
  fi
}

wait_for_service_host() {
  local service_name="$1"
  local elapsed=0
  local host=""
  local remaining=0
  local sleep_seconds=0

  while (( elapsed <= EXTERNAL_URL_TIMEOUT_SECONDS )); do
    host="$(discover_service_host "${service_name}")"
    if [[ -n "${host}" ]]; then
      printf '%s\n' "${host}"
      return
    fi

    if (( elapsed == EXTERNAL_URL_TIMEOUT_SECONDS )); then
      break
    fi

    remaining=$((EXTERNAL_URL_TIMEOUT_SECONDS - elapsed))
    sleep_seconds=5
    if (( remaining < sleep_seconds )); then
      sleep_seconds=${remaining}
    fi

    sleep "${sleep_seconds}"
    elapsed=$((elapsed + sleep_seconds))
  done

  printf '\n'
}

wait_for_route_host() {
  local elapsed=0
  local route_host=""
  local remaining=0
  local sleep_seconds=0

  while (( elapsed <= EXTERNAL_URL_TIMEOUT_SECONDS )); do
    route_host="$(oc -n "${NAMESPACE}" get route "${FRONTEND_ROUTE_NAME}" -o jsonpath='{.spec.host}' 2>/dev/null || true)"
    if [[ -n "${route_host}" ]]; then
      printf '%s\n' "${route_host}"
      return
    fi

    if (( elapsed == EXTERNAL_URL_TIMEOUT_SECONDS )); then
      break
    fi

    remaining=$((EXTERNAL_URL_TIMEOUT_SECONDS - elapsed))
    sleep_seconds=5
    if (( remaining < sleep_seconds )); then
      sleep_seconds=${remaining}
    fi

    sleep "${sleep_seconds}"
    elapsed=$((elapsed + sleep_seconds))
  done

  printf '\n'
}

discover_rtsp_url() {
  if [[ -n "${PUBLIC_RTSP_URL}" ]]; then
    printf '%s\n' "${PUBLIC_RTSP_URL}"
    return
  fi

  if [[ "${RTSP_SERVICE_TYPE}" != "LoadBalancer" ]]; then
    printf '\n'
    return
  fi

  local host
  host="$(wait_for_service_host media-service-demo-rtsp)"
  if [[ -n "${host}" ]]; then
    printf 'rtsp://%s:8554/live\n' "${host}"
    return
  fi

  printf '\n'
}

ensure_frontend_deps() {
  if [[ -d "${FRONTEND_DIR}/node_modules" ]]; then
    return
  fi

  log "Installing frontend dependencies"
  (
    cd "${FRONTEND_DIR}"
    npm install --no-fund --no-audit
  )
}

build_frontend() {
  local -a build_env
  local splunk_upload_token="${SPLUNK_SOURCEMAP_UPLOAD_TOKEN:-${SPLUNK_ACCESS_TOKEN:-}}"

  build_env=(
    "APP_VERSION=${APP_VERSION}"
    "STREAMING_NAMESPACE=${NAMESPACE}"
    "STREAMING_PUBLIC_RTSP_URL=${PUBLIC_RTSP_URL}"
  )

  if [[ -n "${CLUSTER_LABEL}" ]]; then
    build_env+=("STREAMING_CLUSTER_LABEL=${CLUSTER_LABEL}")
  fi
  if [[ -n "${ENVIRONMENT_LABEL}" ]]; then
    build_env+=("STREAMING_ENVIRONMENT_LABEL=${ENVIRONMENT_LABEL}")
  fi
  if [[ -n "${REGION_LABEL}" ]]; then
    build_env+=("STREAMING_REGION_LABEL=${REGION_LABEL}")
  fi
  if [[ -n "${CONTROL_ROOM_LABEL}" ]]; then
    build_env+=("STREAMING_CONTROL_ROOM_LABEL=${CONTROL_ROOM_LABEL}")
  fi

  log "Building frontend assets"
  (
    cd "${FRONTEND_DIR}"
    env "${build_env[@]}" npm run build:production
  )

  if [[ -n "${SPLUNK_REALM:-}" && -z "${splunk_upload_token}" && -n "${SPLUNK_RUM_ACCESS_TOKEN:-}" ]]; then
    warn "SPLUNK_RUM_ACCESS_TOKEN is set, but sourcemap upload needs SPLUNK_ACCESS_TOKEN or SPLUNK_SOURCEMAP_UPLOAD_TOKEN. Browser RUM will still work; sourcemap upload is being skipped."
  elif [[ -n "${SPLUNK_REALM:-}" && -n "${splunk_upload_token}" ]]; then
    if ! LOG_PREFIX="[deploy-streaming-app]" \
      FRONTEND_DIR="${FRONTEND_DIR}" \
      APP_VERSION="${APP_VERSION}" \
      SPLUNK_RUM_APP_NAME="${SPLUNK_RUM_APP_NAME}" \
      SPLUNK_REALM="${SPLUNK_REALM}" \
      SPLUNK_SOURCEMAP_UPLOAD_TOKEN="${splunk_upload_token}" \
      bash "${REPO_ROOT}/scripts/frontend/upload-sourcemaps.sh"; then
      warn "Splunk sourcemap upload still failed after retries; continuing deploy because the frontend rollout does not depend on it"
    fi
  else
    log "Skipping Splunk sourcemap upload because SPLUNK_REALM and/or SPLUNK_ACCESS_TOKEN are unset"
  fi
}

create_backend_sources() {
  local content_archive="${TEMP_DIR}/content-service-source.tgz"
  local media_archive="${TEMP_DIR}/media-service-source.tgz"
  local user_archive="${TEMP_DIR}/user-service-source.tgz"
  local billing_archive="${TEMP_DIR}/billing-service-source.tgz"
  local ad_archive="${TEMP_DIR}/ad-service-source.tgz"

  log "Packaging backend source archives"
  package_service_source services/content-service "${content_archive}" src/test
  package_service_source services/media-service "${media_archive}" src/test
  package_service_source services/user-service "${user_archive}" src/test
  package_service_source services/billing-service "${billing_archive}" src/test
  package_service_source services/ad-service "${ad_archive}" src/test

  create_or_update_configmap content-service-source --from-file=content-service-source.tgz="${content_archive}"
  create_or_update_configmap media-service-source --from-file=media-service-source.tgz="${media_archive}"
  create_or_update_configmap user-service-source --from-file=user-service-source.tgz="${user_archive}"
  create_or_update_configmap billing-service-source --from-file=billing-service-source.tgz="${billing_archive}"
  create_or_update_configmap ad-service-source --from-file=ad-service-source.tgz="${ad_archive}"
}

create_frontend_configmaps() {
  local dist_dir="${FRONTEND_DIR}/dist"

  create_or_update_configmap streaming-frontend-assets \
    --from-file=index.html="${dist_dir}/index.html" \
    --from-file=broadcast.html="${dist_dir}/broadcast.html" \
    --from-file=demo-monkey.html="${dist_dir}/demo-monkey.html" \
    --from-file=styles.css="${dist_dir}/styles.css" \
    --from-file=app.js="${dist_dir}/app.js" \
    --from-file=broadcast.js="${dist_dir}/broadcast.js" \
    --from-file=demo-monkey.js="${dist_dir}/demo-monkey.js" \
    --from-file=config.js="${dist_dir}/config.js" \
    --from-file=server.js="${dist_dir}/server.js"

  create_or_update_configmap streaming-frontend-rum-assets \
    --from-file=splunk-instrumentation.js="${dist_dir}/splunk-instrumentation.js"
}

ensure_frontend_route() {
  if [[ "${PLATFORM}" != "openshift" ]]; then
    return
  fi

  if ! oc -n "${NAMESPACE}" get route "${FRONTEND_ROUTE_NAME}" >/dev/null 2>&1; then
    oc -n "${NAMESPACE}" expose service/streaming-frontend --name="${FRONTEND_ROUTE_NAME}" >/dev/null
  fi
}

discover_frontend_url() {
  if [[ "${PLATFORM}" == "openshift" ]]; then
    local route_host
    route_host="$(wait_for_route_host)"
    if [[ -n "${route_host}" ]]; then
      printf 'https://%s\n' "${route_host}"
      return
    fi
  fi

  if [[ "${FRONTEND_SERVICE_TYPE}" == "LoadBalancer" ]]; then
    local host
    host="$(wait_for_service_host streaming-frontend)"
    if [[ -n "${host}" ]]; then
      printf 'http://%s\n' "${host}"
      return
    fi
  fi

  printf '\n'
}

NAMESPACE="${NAMESPACE:-streaming-service-app}"
PLATFORM="${PLATFORM:-auto}"
SPLUNK_RUM_APP_NAME="${SPLUNK_RUM_APP_NAME:-streaming-app-frontend}"
FRONTEND_ROUTE_NAME="${FRONTEND_ROUTE_NAME:-streaming-frontend}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-900s}"
ROLLOUT_SNAPSHOT_INTERVAL_SECONDS="${ROLLOUT_SNAPSHOT_INTERVAL_SECONDS:-15}"
EXTERNAL_URL_TIMEOUT_SECONDS="${EXTERNAL_URL_TIMEOUT_SECONDS:-60}"
SPLUNK_OTEL_MODE="${SPLUNK_OTEL_MODE:-skip}"
SPLUNK_OTEL_HELPER_SCRIPT="${SPLUNK_OTEL_HELPER_SCRIPT:-${SKILL_DIR}/scripts/ensure-splunk-otel-collector.sh}"

validate_dns_label "${NAMESPACE}" "namespace"
validate_dns_label "${FRONTEND_ROUTE_NAME}" "frontend route name"
validate_non_negative_integer "${ROLLOUT_SNAPSHOT_INTERVAL_SECONDS}" "rollout snapshot interval"
validate_non_negative_integer "${EXTERNAL_URL_TIMEOUT_SECONDS}" "external URL timeout"

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

if [[ -z "${FRONTEND_SERVICE_TYPE}" ]]; then
  if [[ "${PLATFORM}" == "openshift" ]]; then
    FRONTEND_SERVICE_TYPE="ClusterIP"
  else
    FRONTEND_SERVICE_TYPE="LoadBalancer"
  fi
fi

if [[ -z "${RTSP_SERVICE_TYPE}" ]]; then
  if [[ "${PLATFORM}" == "openshift" ]]; then
    RTSP_SERVICE_TYPE="ClusterIP"
  else
    RTSP_SERVICE_TYPE="LoadBalancer"
  fi
fi

if [[ -z "${FRONTEND_LOAD_BALANCER_SCHEME}" && "${PLATFORM}" == "kubernetes" && "${FRONTEND_SERVICE_TYPE}" == "LoadBalancer" ]]; then
  FRONTEND_LOAD_BALANCER_SCHEME="internet-facing"
fi

if [[ -z "${RTSP_LOAD_BALANCER_SCHEME}" && "${PLATFORM}" == "kubernetes" && "${RTSP_SERVICE_TYPE}" == "LoadBalancer" ]]; then
  RTSP_LOAD_BALANCER_SCHEME="internet-facing"
fi

validate_service_type "${FRONTEND_SERVICE_TYPE}"
validate_service_type "${RTSP_SERVICE_TYPE}"
validate_load_balancer_scheme "${FRONTEND_LOAD_BALANCER_SCHEME}"
validate_load_balancer_scheme "${RTSP_LOAD_BALANCER_SCHEME}"
validate_splunk_otel_mode "${SPLUNK_OTEL_MODE}"

if [[ -z "${APP_VERSION}" ]]; then
  APP_VERSION="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || true)"
  APP_VERSION="${APP_VERSION:-dev}"
fi

if [[ -z "${CLUSTER_LABEL}" ]]; then
  CLUSTER_LABEL="$("${KUBECLI}" config current-context 2>/dev/null || true)"
fi

if [[ -z "${SPLUNK_OTEL_CLUSTER_NAME}" ]]; then
  SPLUNK_OTEL_CLUSTER_NAME="${CLUSTER_LABEL}"
fi

TEMP_DIR="$(mktemp -d)"
RENDERED_NAMESPACE="$(escape_sed_replacement "${NAMESPACE}")"

verify_repo_layout
verify_tooling

log "Platform: ${PLATFORM}"
log "CLI: ${KUBECLI}"
log "Namespace: ${NAMESPACE}"
log "Frontend service type: ${FRONTEND_SERVICE_TYPE}"
log "RTSP service type: ${RTSP_SERVICE_TYPE}"
if [[ -n "${FRONTEND_LOAD_BALANCER_SCHEME}" ]]; then
  log "Frontend load balancer scheme: ${FRONTEND_LOAD_BALANCER_SCHEME}"
fi
if [[ -n "${RTSP_LOAD_BALANCER_SCHEME}" ]]; then
  log "RTSP load balancer scheme: ${RTSP_LOAD_BALANCER_SCHEME}"
fi
log "Splunk OTel mode: ${SPLUNK_OTEL_MODE}"
if [[ "${SPLUNK_OTEL_MODE}" != "skip" && -n "${SPLUNK_OTEL_CLUSTER_NAME}" ]]; then
  log "Splunk OTel cluster name: ${SPLUNK_OTEL_CLUSTER_NAME}"
fi
log "Rollout snapshot interval: ${ROLLOUT_SNAPSHOT_INTERVAL_SECONDS}s"
log "External URL timeout: ${EXTERNAL_URL_TIMEOUT_SECONDS}s"
log "Env file: ${ENV_FILE}"
log "Skill path: ${SKILL_DIR}"

create_namespace
ensure_splunk_otel_collector
create_backend_sources
ensure_demo_auth_secret

log "Applying PostgreSQL and backend demo manifests"
apply_manifest "${REPO_ROOT}/k8s/backend-demo/postgres.yaml"
wait_for_deployment streaming-postgres

apply_manifest "${REPO_ROOT}/k8s/backend-demo/content-service.yaml"
apply_manifest "${REPO_ROOT}/k8s/backend-demo/media-service.yaml"
apply_manifest "${REPO_ROOT}/k8s/backend-demo/user-service.yaml"
apply_manifest "${REPO_ROOT}/k8s/backend-demo/billing-service.yaml"
apply_manifest "${REPO_ROOT}/k8s/backend-demo/ad-service.yaml"
patch_service_type media-service-demo-rtsp "${RTSP_SERVICE_TYPE}"
patch_service_load_balancer_scheme media-service-demo-rtsp "${RTSP_LOAD_BALANCER_SCHEME}"
patch_service_aws_load_balancer_type media-service-demo-rtsp "${RTSP_AWS_LOAD_BALANCER_TYPE}"

restart_and_wait content-service-demo
restart_and_wait media-service-demo
restart_and_wait user-service-demo
restart_and_wait billing-service
restart_and_wait ad-service-demo

PUBLIC_RTSP_URL="$(discover_rtsp_url)"

ensure_frontend_deps
build_frontend
create_frontend_configmaps

log "Applying frontend manifests"
apply_manifest "${REPO_ROOT}/k8s/frontend/deployment.yaml"
apply_manifest "${REPO_ROOT}/k8s/frontend/service.yaml"
patch_service_type streaming-frontend "${FRONTEND_SERVICE_TYPE}"
patch_service_load_balancer_scheme streaming-frontend "${FRONTEND_LOAD_BALANCER_SCHEME}"
patch_service_aws_load_balancer_type streaming-frontend "${FRONTEND_AWS_LOAD_BALANCER_TYPE}"
restart_and_wait streaming-frontend
ensure_frontend_route

FRONTEND_URL="$(discover_frontend_url)"

echo
"${KUBECLI}" -n "${NAMESPACE}" get service \
  ad-service-demo \
  billing-service \
  content-service-demo \
  media-service-demo \
  media-service-demo-rtsp \
  streaming-frontend \
  user-service-demo

if [[ "${PLATFORM}" == "openshift" ]]; then
  echo
  oc -n "${NAMESPACE}" get route "${FRONTEND_ROUTE_NAME}"
fi

echo
if [[ -n "${FRONTEND_URL}" ]]; then
  printf 'Frontend URL: %s\n' "${FRONTEND_URL}"
else
  printf 'Frontend URL: not automatically discoverable\n'
  printf 'Access hint: %s -n %s port-forward service/streaming-frontend 8080:80\n' "${KUBECLI}" "${NAMESPACE}"
fi

if [[ -n "${PUBLIC_RTSP_URL}" ]]; then
  printf 'RTSP URL: %s\n' "${PUBLIC_RTSP_URL}"
else
  printf 'RTSP URL: not exposed externally\n'
  if [[ "${PLATFORM}" == "openshift" ]]; then
    printf 'OpenShift note: Routes do not expose RTSP/TCP. Use a LoadBalancer service or a TCP-capable ingress if you need external RTSP.\n'
  fi
fi

echo
printf 'Demo login password (%s): %s\n' "${DEMO_AUTH_PASSWORD_SOURCE}" "${DEMO_AUTH_PASSWORD}"
printf 'Demo auth accounts: ops, platform, programming, qa, exec, staff, billingadmin, finance, controller @ acmebroadcasting.com\n'
printf 'Persona shortcuts: operator, exec, programming\n'
