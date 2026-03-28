#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
NAMESPACE="${NAMESPACE:-streaming-service-app}"
TEMP_DIR="$(mktemp -d)"
ARCHIVE_PATH="${TEMP_DIR}/billing-service-source.tgz"

cleanup() {
  rm -rf "${TEMP_DIR}"
}
trap cleanup EXIT

kubectl apply -f "${ROOT_DIR}/k8s/frontend/namespace.yaml"

tar -C "${ROOT_DIR}" -czf "${ARCHIVE_PATH}" services/billing-service

kubectl -n "${NAMESPACE}" create configmap billing-service-source \
  --from-file=billing-service-source.tgz="${ARCHIVE_PATH}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/billing-service.yaml"

kubectl -n "${NAMESPACE}" rollout restart deployment/billing-service
kubectl -n "${NAMESPACE}" rollout status deployment/billing-service --timeout=900s

echo
kubectl -n "${NAMESPACE}" get svc billing-service
