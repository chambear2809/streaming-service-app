#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
DEMO_AUTH_SECRET_NAME="streaming-demo-auth"

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

    if (( ${+parameters[$key]} )); then
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
DEMO_AUTH_SECRET="${DEMO_AUTH_SECRET:-}"
DEMO_AUTH_PASSWORD="${DEMO_AUTH_PASSWORD:-}"
TEMP_DIR="$(mktemp -d)"
CONTENT_ARCHIVE_PATH="${TEMP_DIR}/content-service-source.tgz"
MEDIA_ARCHIVE_PATH="${TEMP_DIR}/media-service-source.tgz"
USER_ARCHIVE_PATH="${TEMP_DIR}/user-service-source.tgz"
ARCHIVE_PATH="${TEMP_DIR}/billing-service-source.tgz"
AD_ARCHIVE_PATH="${TEMP_DIR}/ad-service-source.tgz"
CUSTOMER_ARCHIVE_PATH="${TEMP_DIR}/customer-service-source.tgz"
PAYMENT_ARCHIVE_PATH="${TEMP_DIR}/payment-service-source.tgz"
SUBSCRIPTION_ARCHIVE_PATH="${TEMP_DIR}/subscription-service-source.tgz"
ORDER_ARCHIVE_PATH="${TEMP_DIR}/order-service-source.tgz"

cleanup() {
  rm -rf "${TEMP_DIR}"
}
trap cleanup EXIT

apply_generated_resource() {
  kubectl apply --server-side -f -
}

random_alnum() {
  local length="$1"
  local value=""

  set +o pipefail
  value="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${length}")"
  set -o pipefail

  print -r -- "${value}"
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

  tar -C "${ROOT_DIR}" "${excludes[@]}" -czf "${archive_path}" "${service_dir}"
}

ensure_demo_auth_secret() {
  if [[ -z "${DEMO_AUTH_SECRET}" ]]; then
    DEMO_AUTH_SECRET="$(random_alnum 48)"
  fi

  if [[ -z "${DEMO_AUTH_PASSWORD}" ]]; then
    DEMO_AUTH_PASSWORD="$(random_alnum 20)"
  fi

  kubectl -n "${NAMESPACE}" create secret generic "${DEMO_AUTH_SECRET_NAME}" \
    --from-literal=DEMO_AUTH_SECRET="${DEMO_AUTH_SECRET}" \
    --from-literal=DEMO_AUTH_PASSWORD="${DEMO_AUTH_PASSWORD}" \
    --dry-run=client -o yaml | apply_generated_resource
}

kubectl apply -f "${ROOT_DIR}/k8s/frontend/namespace.yaml"

package_service_source services/content-service "${CONTENT_ARCHIVE_PATH}" src/test
package_service_source services/media-service "${MEDIA_ARCHIVE_PATH}" src/test
package_service_source services/user-service "${USER_ARCHIVE_PATH}" src/test
package_service_source services/billing-service "${ARCHIVE_PATH}" src/test
package_service_source services/ad-service "${AD_ARCHIVE_PATH}" src/test
package_service_source services/customer-service "${CUSTOMER_ARCHIVE_PATH}" src/test
package_service_source services/payment-service "${PAYMENT_ARCHIVE_PATH}" src/test
package_service_source services/subscription-service "${SUBSCRIPTION_ARCHIVE_PATH}" src/test
package_service_source services/order-service "${ORDER_ARCHIVE_PATH}" src/test

kubectl -n "${NAMESPACE}" create configmap content-service-source \
  --from-file=content-service-source.tgz="${CONTENT_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap media-service-source \
  --from-file=media-service-source.tgz="${MEDIA_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap user-service-source \
  --from-file=user-service-source.tgz="${USER_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap billing-service-source \
  --from-file=billing-service-source.tgz="${ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap ad-service-source \
  --from-file=ad-service-source.tgz="${AD_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap customer-service-source \
  --from-file=customer-service-source.tgz="${CUSTOMER_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap payment-service-source \
  --from-file=payment-service-source.tgz="${PAYMENT_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap subscription-service-source \
  --from-file=subscription-service-source.tgz="${SUBSCRIPTION_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

kubectl -n "${NAMESPACE}" create configmap order-service-source \
  --from-file=order-service-source.tgz="${ORDER_ARCHIVE_PATH}" \
  --dry-run=client -o yaml | apply_generated_resource

ensure_demo_auth_secret

kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/postgres.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/content-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/media-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/user-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/billing-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/ad-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/customer-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/payment-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/subscription-service.yaml"
kubectl apply -f "${ROOT_DIR}/k8s/backend-demo/order-service.yaml"

kubectl -n "${NAMESPACE}" rollout restart deployment/content-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/media-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/user-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/billing-service
kubectl -n "${NAMESPACE}" rollout restart deployment/ad-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/customer-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/payment-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/subscription-service-demo
kubectl -n "${NAMESPACE}" rollout restart deployment/order-service-demo

kubectl -n "${NAMESPACE}" rollout status deployment/streaming-postgres --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/content-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/media-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/user-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/billing-service --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/ad-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/customer-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/payment-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/subscription-service-demo --timeout=900s
kubectl -n "${NAMESPACE}" rollout status deployment/order-service-demo --timeout=900s

zsh "${ROOT_DIR}/scripts/frontend/deploy.sh"

echo
kubectl -n "${NAMESPACE}" get svc ad-service-demo billing-service content-service-demo customer-service-demo media-service-demo media-service-demo-rtsp order-service-demo payment-service-demo streaming-frontend streaming-postgres subscription-service-demo user-service-demo
echo
printf 'Demo login password: %s\n' "${DEMO_AUTH_PASSWORD}"
