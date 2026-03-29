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
EXTERNAL_URL_TIMEOUT_SECONDS="${EXTERNAL_URL_TIMEOUT_SECONDS-}"
DEMO_AUTH_SECRET="${DEMO_AUTH_SECRET-}"
DEMO_AUTH_PASSWORD="${DEMO_AUTH_PASSWORD-}"
DEMO_AUTH_SECRET_NAME="streaming-demo-auth"
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
  --rtsp-service-type <ClusterIP|NodePort|LoadBalancer>
  --cluster-label <label>
  --environment-label <label>
  --region-label <label>
  --control-room-label <label>
  --public-rtsp-url <url>
  --app-version <version>
  --rollout-timeout <duration>
  --external-url-timeout <seconds>
  --help

Environment variables:
  ENV_FILE                              Optional path to a repo-style env file
  SPLUNK_REALM / SPLUNK_RUM_ACCESS_TOKEN
                                        Optional frontend sourcemap upload credentials
  SPLUNK_ACCESS_TOKEN                   Backward-compatible sourcemap token fallback
  SPLUNK_RUM_APP_NAME                  Optional frontend RUM app name
  DEMO_AUTH_PASSWORD / DEMO_AUTH_SECRET
                                        Optional demo login credentials to persist
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
    --rtsp-service-type)
      RTSP_SERVICE_TYPE="${2:?missing value for --rtsp-service-type}"
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
    --rollout-timeout)
      ROLLOUT_TIMEOUT="${2:?missing value for --rollout-timeout}"
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
  local extra_path

  excludes=(
    "--exclude=${service_dir}/target"
    "--exclude=${service_dir}/build"
    "--exclude=${service_dir}/.idea"
    "--exclude=${service_dir}/.vscode"
    "--exclude=${service_dir}/.DS_Store"
  )

  for extra_path in "$@"; do
    excludes+=("--exclude=${service_dir}/${extra_path}")
  done

  tar -C "${REPO_ROOT}" "${excludes[@]}" -czf "${archive_path}" "${service_dir}"
}

ensure_demo_auth_secret() {
  if [[ -z "${DEMO_AUTH_SECRET}" ]]; then
    DEMO_AUTH_SECRET="$(random_alnum 48)"
  fi

  if [[ -z "${DEMO_AUTH_PASSWORD}" ]]; then
    DEMO_AUTH_PASSWORD="$(random_alnum 20)"
    DEMO_AUTH_PASSWORD_SOURCE="generated"
  fi

  create_or_update_secret "${DEMO_AUTH_SECRET_NAME}" \
    --from-literal=DEMO_AUTH_SECRET="${DEMO_AUTH_SECRET}" \
    --from-literal=DEMO_AUTH_PASSWORD="${DEMO_AUTH_PASSWORD}"
}

restart_and_wait() {
  local deployment="$1"
  "${KUBECLI}" -n "${NAMESPACE}" rollout restart "deployment/${deployment}" >/dev/null
  "${KUBECLI}" -n "${NAMESPACE}" rollout status "deployment/${deployment}" --timeout="${ROLLOUT_TIMEOUT}"
}

wait_for_deployment() {
  local deployment="$1"
  "${KUBECLI}" -n "${NAMESPACE}" rollout status "deployment/${deployment}" --timeout="${ROLLOUT_TIMEOUT}"
}

patch_service_type() {
  local service_name="$1"
  local service_type="$2"
  "${KUBECLI}" -n "${NAMESPACE}" patch service "${service_name}" --type merge -p "{\"spec\":{\"type\":\"${service_type}\"}}" >/dev/null
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
  local splunk_upload_token="${SPLUNK_RUM_ACCESS_TOKEN:-${SPLUNK_ACCESS_TOKEN:-}}"

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

  if [[ -n "${SPLUNK_REALM:-}" && -n "${splunk_upload_token}" ]]; then
    log "Uploading frontend sourcemaps to Splunk RUM"
    if ! (
      cd "${FRONTEND_DIR}"
      env APP_VERSION="${APP_VERSION}" ./node_modules/.bin/splunk-rum sourcemaps upload \
        --app-name "${SPLUNK_RUM_APP_NAME}" \
        --app-version "${APP_VERSION}" \
        --path dist \
        --realm "${SPLUNK_REALM}" \
        --token "${splunk_upload_token}"
    ); then
      warn "Splunk sourcemap upload failed; continuing deploy because the frontend rollout does not depend on it"
    fi
  else
    log "Skipping Splunk sourcemap upload because SPLUNK_REALM and/or a sourcemap token are unset"
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
EXTERNAL_URL_TIMEOUT_SECONDS="${EXTERNAL_URL_TIMEOUT_SECONDS:-60}"

validate_dns_label "${NAMESPACE}" "namespace"
validate_dns_label "${FRONTEND_ROUTE_NAME}" "frontend route name"
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

validate_service_type "${FRONTEND_SERVICE_TYPE}"
validate_service_type "${RTSP_SERVICE_TYPE}"

if [[ -z "${APP_VERSION}" ]]; then
  APP_VERSION="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || true)"
  APP_VERSION="${APP_VERSION:-dev}"
fi

if [[ -z "${CLUSTER_LABEL}" ]]; then
  CLUSTER_LABEL="$("${KUBECLI}" config current-context 2>/dev/null || true)"
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
log "External URL timeout: ${EXTERNAL_URL_TIMEOUT_SECONDS}s"
log "Env file: ${ENV_FILE}"
log "Skill path: ${SKILL_DIR}"

create_namespace
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
