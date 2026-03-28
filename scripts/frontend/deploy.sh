#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
NAMESPACE="${NAMESPACE:-streaming-service-app}"
APP_NAME="${APP_NAME:-streaming-frontend}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-streaming-frontend-assets}"
RUM_CONFIGMAP_NAME="${RUM_CONFIGMAP_NAME:-streaming-frontend-rum-assets}"
FRONTEND_DIR="${ROOT_DIR}/frontend"
DIST_DIR="${FRONTEND_DIR}/dist"
APP_VERSION="${APP_VERSION:-$(git -C "${ROOT_DIR}" rev-parse --short HEAD)}"
SPLUNK_RUM_APP_NAME="${SPLUNK_RUM_APP_NAME:-streaming-app-frontend}"

kubectl apply -f "${ROOT_DIR}/k8s/frontend/namespace.yaml"

if [[ ! -d "${FRONTEND_DIR}/node_modules" ]]; then
  (
    cd "${FRONTEND_DIR}"
    npm install --no-fund --no-audit
  )
fi

(
  cd "${FRONTEND_DIR}"
  APP_VERSION="${APP_VERSION}" npm run build:production
)

if [[ -n "${SPLUNK_REALM:-}" && -n "${SPLUNK_ACCESS_TOKEN:-}" ]]; then
  (
    cd "${FRONTEND_DIR}"
    APP_VERSION="${APP_VERSION}" ./node_modules/.bin/splunk-rum sourcemaps upload \
      --app-name "${SPLUNK_RUM_APP_NAME}" \
      --app-version "${APP_VERSION}" \
      --path dist \
      --realm "${SPLUNK_REALM}" \
      --token "${SPLUNK_ACCESS_TOKEN}"
  )
else
  echo "Skipping Splunk source map upload because SPLUNK_REALM and/or SPLUNK_ACCESS_TOKEN are not set."
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
