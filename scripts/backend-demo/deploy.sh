#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
NAMESPACE="${NAMESPACE:-streaming-service-app}"
TEMP_DIR="$(mktemp -d)"
CONTENT_ARCHIVE_PATH="${TEMP_DIR}/content-service-source.tgz"
MEDIA_ARCHIVE_PATH="${TEMP_DIR}/media-service-source.tgz"
USER_ARCHIVE_PATH="${TEMP_DIR}/user-service-source.tgz"
ARCHIVE_PATH="${TEMP_DIR}/billing-service-source.tgz"
AD_ARCHIVE_PATH="${TEMP_DIR}/ad-service-source.tgz"

cleanup() {
  rm -rf "${TEMP_DIR}"
}
trap cleanup EXIT

kubectl apply -f "${ROOT_DIR}/k8s/frontend/namespace.yaml"

tar -C "${ROOT_DIR}" --exclude='services/content-service/src/test' -czf "${CONTENT_ARCHIVE_PATH}" services/content-service
tar -C "${ROOT_DIR}" --exclude='services/media-service/src/test' -czf "${MEDIA_ARCHIVE_PATH}" services/media-service
tar -C "${ROOT_DIR}" --exclude='services/user-service/src/test' -czf "${USER_ARCHIVE_PATH}" services/user-service
tar -C "${ROOT_DIR}" -czf "${ARCHIVE_PATH}" services/billing-service
tar -C "${ROOT_DIR}" -czf "${AD_ARCHIVE_PATH}" services/ad-service

kubectl -n "${NAMESPACE}" create configmap content-service-source \
  --from-file=content-service-source.tgz="${CONTENT_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "${NAMESPACE}" create configmap media-service-source \
  --from-file=media-service-source.tgz="${MEDIA_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "${NAMESPACE}" create configmap user-service-source \
  --from-file=user-service-source.tgz="${USER_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "${NAMESPACE}" create configmap billing-service-source \
  --from-file=billing-service-source.tgz="${ARCHIVE_PATH}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "${NAMESPACE}" create configmap ad-service-source \
  --from-file=ad-service-source.tgz="${AD_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/content-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/media-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/user-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/billing-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/ad-service.yaml"

kubectl -n "${NAMESPACE}" rollout restart deployment/content-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/media-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/user-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/billing-service
kubectl -n "${NAMESPACE}" rollout restart deployment/ad-service-demo

kubectl -n "${NAMESPACE}" rollout status deployment/content-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/media-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/user-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/billing-service --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/ad-service-demo --timeout=900s

zsh "${ROOT_DIR}/scripts/frontend/deploy.sh"

echo
kubectl -n "${NAMESPACE}" get svc ad-service-demo billing-service content-service-demo media-service-demo media-service-demo-rtsp streaming-frontend user-service-demo
