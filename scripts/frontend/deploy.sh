#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"

log() {
  print -r -- "[frontend-deploy] $*"
}

warn() {
  print -u2 -r -- "[frontend-deploy] WARN: $*"
}

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

    if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi

    export "${key}=${value}"
  done < "${env_file}"
}

load_env_file "${ENV_FILE}"

NAMESPACE="${NAMESPACE:-streaming-service-app}"
APP_NAME="${APP_NAME:-streaming-frontend}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-streaming-frontend-assets}"
FRONTEND_DIR="${ROOT_DIR}/frontend"
DIST_DIR="${FRONTEND_DIR}/dist"
APP_VERSION="${APP_VERSION:-$(git -C "${ROOT_DIR}" rev-parse --short HEAD)}"
RUM_CONFIGMAP_NAME="${RUM_CONFIGMAP_NAME:-streaming-frontend-rum-assets}"
SPLUNK_RUM_APP_NAME="${SPLUNK_RUM_APP_NAME:-streaming-app-frontend}"
SPLUNK_RUM_ACCESS_TOKEN="${SPLUNK_RUM_ACCESS_TOKEN:-}"

kubectl apply -f "${ROOT_DIR}/k8s/frontend/namespace.yaml"

if [[ ! -d "${FRONTEND_DIR}/node_modules" ]]; then
  log "Installing frontend dependencies"
  (
    cd "${FRONTEND_DIR}"
    npm install --no-fund --no-audit
  )
fi

log "Building frontend assets"
(
  cd "${FRONTEND_DIR}"
  APP_VERSION="${APP_VERSION}" npm run build:production
)

if [[ -n "${SPLUNK_REALM:-}" && -n "${SPLUNK_RUM_ACCESS_TOKEN:-}" ]]; then
  log "Uploading frontend sourcemaps to Splunk RUM"
  if ! (
    cd "${FRONTEND_DIR}"
    APP_VERSION="${APP_VERSION}" ./node_modules/.bin/splunk-rum sourcemaps upload \
      --app-name "${SPLUNK_RUM_APP_NAME}" \
      --app-version "${APP_VERSION}" \
      --path dist \
      --realm "${SPLUNK_REALM}" \
      --token "${SPLUNK_RUM_ACCESS_TOKEN}"
  ); then
    warn "Splunk source map upload failed; continuing deploy because the frontend rollout does not depend on it."
  fi
else
  log "Skipping Splunk source map upload because SPLUNK_REALM and/or SPLUNK_RUM_ACCESS_TOKEN are not set."
fi

kubectl -n "${NAMESPACE}" create configmap "${CONFIGMAP_NAME}" \
  --from-file=index.html="${DIST_DIR}/index.html" \
  --from-file=broadcast.html="${DIST_DIR}/broadcast.html" \
  --from-file=demo-monkey.html="${DIST_DIR}/demo-monkey.html" \
  --from-file=styles.css="${DIST_DIR}/styles.css" \
  --from-file=app.js="${DIST_DIR}/app.js" \
  --from-file=broadcast.js="${DIST_DIR}/broadcast.js" \
  --from-file=demo-monkey.js="${DIST_DIR}/demo-monkey.js" \
  --from-file=config.js="${DIST_DIR}/config.js" \
  --from-file=server.js="${DIST_DIR}/server.js" \
  --dry-run=client -o yaml | kubectl apply --server-side -f -

kubectl -n "${NAMESPACE}" create configmap "${RUM_CONFIGMAP_NAME}" \
  --from-file=splunk-instrumentation.js="${DIST_DIR}/splunk-instrumentation.js" \
  --dry-run=client -o yaml | kubectl apply --server-side -f -

kubectl apply -f "${ROOT_DIR}/k8s/frontend/deployment.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/frontend/service.yaml"
kubectl -n "${NAMESPACE}" rollout restart deployment/"${APP_NAME}"
kubectl -n "${NAMESPACE}" rollout status deployment/"${APP_NAME}" --timeout=180s

echo
echo "Service status:"
kubectl -n "${NAMESPACE}" get service "${APP_NAME}"
