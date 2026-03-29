#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
NAMESPACE="${NAMESPACE:-${STREAMING_K8S_NAMESPACE:-streaming-service-app}}"
FRONTEND_SERVICE_NAME="${FRONTEND_SERVICE_NAME:-streaming-frontend}"
LOADGEN_OPERATOR_JOB_NAME="${LOADGEN_OPERATOR_JOB_NAME:-operator-billing-loadgen}"
LOADGEN_OPERATOR_PROFILE="${LOADGEN_OPERATOR_PROFILE:-booth}"
LOADGEN_OPERATOR_K8S_MODE="${LOADGEN_OPERATOR_K8S_MODE:-job}"
LOADGEN_OPERATOR_K8S_ACTION="${LOADGEN_OPERATOR_K8S_ACTION:-apply}"
LOADGEN_OPERATOR_CRONJOB_NAME="${LOADGEN_OPERATOR_CRONJOB_NAME:-operator-billing-loadgen-recurring}"
LOADGEN_OPERATOR_CRON_SCHEDULE="${LOADGEN_OPERATOR_CRON_SCHEDULE:-*/20 * * * *}"
LOADGEN_OPERATOR_CRON_CONCURRENCY_POLICY="${LOADGEN_OPERATOR_CRON_CONCURRENCY_POLICY:-Forbid}"
LOADGEN_OPERATOR_CRON_SUSPEND="${LOADGEN_OPERATOR_CRON_SUSPEND:-false}"
LOADGEN_OPERATOR_CRON_SUCCESS_HISTORY="${LOADGEN_OPERATOR_CRON_SUCCESS_HISTORY:-0}"
LOADGEN_OPERATOR_CRON_FAILED_HISTORY="${LOADGEN_OPERATOR_CRON_FAILED_HISTORY:-1}"
LOADGEN_OPERATOR_TRIGGER_JOB_NAME="${LOADGEN_OPERATOR_TRIGGER_JOB_NAME:-}"
LOADGEN_OPERATOR_SCRIPT_CONFIGMAP_NAME="${LOADGEN_OPERATOR_SCRIPT_CONFIGMAP_NAME:-operator-billing-loadgen-script}"
LOADGEN_OPERATOR_IMAGE="${LOADGEN_OPERATOR_IMAGE:-node:22-alpine}"
LOADGEN_OPERATOR_IMAGE_PULL_POLICY="${LOADGEN_OPERATOR_IMAGE_PULL_POLICY:-IfNotPresent}"
LOADGEN_OPERATOR_TTL_SECONDS="${LOADGEN_OPERATOR_TTL_SECONDS:-120}"
LOADGEN_OPERATOR_AUTO_DELETE_JOB="${LOADGEN_OPERATOR_AUTO_DELETE_JOB:-true}"
LOADGEN_OPERATOR_CPU_REQUEST="${LOADGEN_OPERATOR_CPU_REQUEST:-250m}"
LOADGEN_OPERATOR_CPU_LIMIT="${LOADGEN_OPERATOR_CPU_LIMIT:-1000m}"
LOADGEN_OPERATOR_MEMORY_REQUEST="${LOADGEN_OPERATOR_MEMORY_REQUEST:-256Mi}"
LOADGEN_OPERATOR_MEMORY_LIMIT="${LOADGEN_OPERATOR_MEMORY_LIMIT:-1024Mi}"
K8S_DRY_RUN="${K8S_DRY_RUN:-false}"

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
LOADGEN_OPERATOR_PROFILE="${(L)LOADGEN_OPERATOR_PROFILE}"
LOADGEN_OPERATOR_K8S_MODE="${(L)LOADGEN_OPERATOR_K8S_MODE}"
LOADGEN_OPERATOR_K8S_ACTION="${(L)LOADGEN_OPERATOR_K8S_ACTION}"

set_env_default() {
  local key="$1" value="$2"

  if (( ! ${+parameters[$key]} )); then
    export "${key}=${value}"
  fi
}

apply_profile_defaults() {
  case "${LOADGEN_OPERATOR_PROFILE}" in
    warmup)
      set_env_default LOADGEN_OPERATOR_DURATION "5m"
      set_env_default LOADGEN_OPERATOR_CONCURRENCY "1"
      set_env_default LOADGEN_OPERATOR_PAUSE "6s"
      set_env_default LOADGEN_OPERATOR_REQUEST_TIMEOUT "15s"
      set_env_default LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE "24"
      set_env_default LOADGEN_OPERATOR_BILLING_EVENT_RATIO "0.35"
      set_env_default LOADGEN_OPERATOR_PAYMENT_RATIO "0.15"
      set_env_default LOADGEN_OPERATOR_PAYMENT_READ_RATIO "0.60"
      set_env_default LOADGEN_OPERATOR_COMMERCE_READ_RATIO "0.65"
      set_env_default LOADGEN_OPERATOR_ORDER_CREATE_RATIO "0.15"
      set_env_default LOADGEN_OPERATOR_ORDER_SETTLE_RATIO "0.15"
      set_env_default LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO "0.05"
      set_env_default LOADGEN_OPERATOR_RTSP_JOB_RATIO "0.20"
      set_env_default LOADGEN_OPERATOR_TAKE_LIVE_RATIO "0.10"
      ;;
    booth)
      set_env_default LOADGEN_OPERATOR_DURATION "8m"
      set_env_default LOADGEN_OPERATOR_CONCURRENCY "3"
      set_env_default LOADGEN_OPERATOR_PAUSE "4s"
      set_env_default LOADGEN_OPERATOR_REQUEST_TIMEOUT "15s"
      set_env_default LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE "24"
      set_env_default LOADGEN_OPERATOR_BILLING_EVENT_RATIO "0.55"
      set_env_default LOADGEN_OPERATOR_PAYMENT_RATIO "0.20"
      set_env_default LOADGEN_OPERATOR_PAYMENT_READ_RATIO "0.80"
      set_env_default LOADGEN_OPERATOR_COMMERCE_READ_RATIO "0.85"
      set_env_default LOADGEN_OPERATOR_ORDER_CREATE_RATIO "0.35"
      set_env_default LOADGEN_OPERATOR_ORDER_SETTLE_RATIO "0.35"
      set_env_default LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO "0.15"
      set_env_default LOADGEN_OPERATOR_RTSP_JOB_RATIO "0.35"
      set_env_default LOADGEN_OPERATOR_TAKE_LIVE_RATIO "0.20"
      ;;
    stress)
      set_env_default LOADGEN_OPERATOR_DURATION "10m"
      set_env_default LOADGEN_OPERATOR_CONCURRENCY "5"
      set_env_default LOADGEN_OPERATOR_PAUSE "3s"
      set_env_default LOADGEN_OPERATOR_REQUEST_TIMEOUT "15s"
      set_env_default LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE "24"
      set_env_default LOADGEN_OPERATOR_BILLING_EVENT_RATIO "0.65"
      set_env_default LOADGEN_OPERATOR_PAYMENT_RATIO "0.25"
      set_env_default LOADGEN_OPERATOR_PAYMENT_READ_RATIO "0.90"
      set_env_default LOADGEN_OPERATOR_COMMERCE_READ_RATIO "0.95"
      set_env_default LOADGEN_OPERATOR_ORDER_CREATE_RATIO "0.45"
      set_env_default LOADGEN_OPERATOR_ORDER_SETTLE_RATIO "0.45"
      set_env_default LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO "0.20"
      set_env_default LOADGEN_OPERATOR_RTSP_JOB_RATIO "0.40"
      set_env_default LOADGEN_OPERATOR_TAKE_LIVE_RATIO "0.25"
      ;;
    custom) ;;
    *)
      echo "Unsupported LOADGEN_OPERATOR_PROFILE: ${LOADGEN_OPERATOR_PROFILE}. Use 'warmup', 'booth', 'stress', or 'custom'." >&2
      exit 1
      ;;
  esac
}

apply_profile_defaults

validate_mode() {
  case "${LOADGEN_OPERATOR_K8S_MODE}" in
    job|cronjob) ;;
    *)
      echo "Unsupported LOADGEN_OPERATOR_K8S_MODE: ${LOADGEN_OPERATOR_K8S_MODE}. Use 'job' or 'cronjob'." >&2
      exit 1
      ;;
  esac
}

validate_mode

validate_action() {
  case "${LOADGEN_OPERATOR_K8S_ACTION}" in
    apply|delete|status) ;;
    pause|resume|trigger)
      if [[ "${LOADGEN_OPERATOR_K8S_MODE}" != "cronjob" ]]; then
        echo "LOADGEN_OPERATOR_K8S_ACTION=${LOADGEN_OPERATOR_K8S_ACTION} requires LOADGEN_OPERATOR_K8S_MODE=cronjob." >&2
        exit 1
      fi
      ;;
    *)
      echo "Unsupported LOADGEN_OPERATOR_K8S_ACTION: ${LOADGEN_OPERATOR_K8S_ACTION}. Use 'apply', 'delete', 'status', 'pause', 'resume', or 'trigger'." >&2
      exit 1
      ;;
  esac
}

validate_action

discover_frontend_port() {
  local named_port first_port

  named_port="$(kubectl -n "${NAMESPACE}" get service "${FRONTEND_SERVICE_NAME}" -o jsonpath='{.spec.ports[?(@.name=="http")].port}' 2>/dev/null || true)"
  if [[ -n "${named_port}" ]]; then
    printf '%s' "${named_port}"
    return 0
  fi

  first_port="$(kubectl -n "${NAMESPACE}" get service "${FRONTEND_SERVICE_NAME}" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || true)"
  if [[ -n "${first_port}" ]]; then
    printf '%s' "${first_port}"
    return 0
  fi

  return 1
}

apply_or_dry_run() {
  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    kubectl apply --dry-run=client -f -
  else
    kubectl apply -f -
  fi
}

delete_job_if_needed() {
  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" delete job "${LOADGEN_OPERATOR_JOB_NAME}" --ignore-not-found=true >/dev/null
}

delete_cronjob_if_needed() {
  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" delete cronjob "${LOADGEN_OPERATOR_CRONJOB_NAME}" --ignore-not-found=true >/dev/null
}

delete_completed_job() {
  if [[ "${K8S_DRY_RUN}" == "true" || "${LOADGEN_OPERATOR_AUTO_DELETE_JOB}" != "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" delete job "${LOADGEN_OPERATOR_JOB_NAME}" --ignore-not-found=true >/dev/null
}

wait_and_stream_logs() {
  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" wait --for=condition=complete "job/${LOADGEN_OPERATOR_JOB_NAME}" --timeout=7200s
  echo
  kubectl -n "${NAMESPACE}" logs "job/${LOADGEN_OPERATOR_JOB_NAME}"
  delete_completed_job
}

show_job_status() {
  kubectl -n "${NAMESPACE}" get job "${LOADGEN_OPERATOR_JOB_NAME}" -o wide 2>/dev/null || echo "Job ${LOADGEN_OPERATOR_JOB_NAME} not found in ${NAMESPACE}."
  kubectl -n "${NAMESPACE}" get pod -l app.kubernetes.io/name="${LOADGEN_OPERATOR_JOB_NAME}" -o wide
}

show_cronjob_status() {
  kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_OPERATOR_CRONJOB_NAME}" -o wide 2>/dev/null || echo "CronJob ${LOADGEN_OPERATOR_CRONJOB_NAME} not found in ${NAMESPACE}."
  kubectl -n "${NAMESPACE}" get job,pod -l app.kubernetes.io/name="${LOADGEN_OPERATOR_JOB_NAME}" -o wide
}

set_cronjob_suspend() {
  local suspend_value="$1"

  kubectl -n "${NAMESPACE}" patch cronjob "${LOADGEN_OPERATOR_CRONJOB_NAME}" \
    --type=merge \
    -p "{\"spec\":{\"suspend\":${suspend_value}}}"
  kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_OPERATOR_CRONJOB_NAME}" -o wide
}

trigger_cronjob_now() {
  local manual_job_name="${LOADGEN_OPERATOR_TRIGGER_JOB_NAME}"

  if [[ -z "${manual_job_name}" ]]; then
    manual_job_name="${LOADGEN_OPERATOR_JOB_NAME}-manual-$(date +%Y%m%d%H%M%S)"
  fi

  kubectl -n "${NAMESPACE}" create job --from=cronjob/"${LOADGEN_OPERATOR_CRONJOB_NAME}" "${manual_job_name}"
  kubectl -n "${NAMESPACE}" get job "${manual_job_name}" -o wide
}

apply_job_manifest() {
  cat <<EOF | apply_or_dry_run
apiVersion: batch/v1
kind: Job
metadata:
  name: ${LOADGEN_OPERATOR_JOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${LOADGEN_OPERATOR_JOB_NAME}
    app.kubernetes.io/part-of: streaming-service-app
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: ${LOADGEN_OPERATOR_TTL_SECONDS}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: ${LOADGEN_OPERATOR_JOB_NAME}
        app.kubernetes.io/part-of: streaming-service-app
    spec:
      restartPolicy: Never
      containers:
        - name: loadgen
          image: ${LOADGEN_OPERATOR_IMAGE}
          imagePullPolicy: ${LOADGEN_OPERATOR_IMAGE_PULL_POLICY}
          command:
            - node
            - /opt/loadgen/operator-billing-loadgen.mjs
          env:
            - name: LOADGEN_OPERATOR_BASE_URL
              value: "${LOADGEN_OPERATOR_BASE_URL}"
            - name: LOADGEN_OPERATOR_PERSONA
              value: "${LOADGEN_OPERATOR_PERSONA:-operator}"
            - name: LOADGEN_OPERATOR_DURATION
              value: "${LOADGEN_OPERATOR_DURATION:-8m}"
            - name: LOADGEN_OPERATOR_CONCURRENCY
              value: "${LOADGEN_OPERATOR_CONCURRENCY:-3}"
            - name: LOADGEN_OPERATOR_PAUSE
              value: "${LOADGEN_OPERATOR_PAUSE:-4s}"
            - name: LOADGEN_OPERATOR_REQUEST_TIMEOUT
              value: "${LOADGEN_OPERATOR_REQUEST_TIMEOUT:-15s}"
            - name: LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE
              value: "${LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE:-24}"
            - name: LOADGEN_OPERATOR_BILLING_EVENT_RATIO
              value: "${LOADGEN_OPERATOR_BILLING_EVENT_RATIO:-0.55}"
            - name: LOADGEN_OPERATOR_PAYMENT_RATIO
              value: "${LOADGEN_OPERATOR_PAYMENT_RATIO:-0.20}"
            - name: LOADGEN_OPERATOR_PAYMENT_READ_RATIO
              value: "${LOADGEN_OPERATOR_PAYMENT_READ_RATIO:-0.80}"
            - name: LOADGEN_OPERATOR_COMMERCE_READ_RATIO
              value: "${LOADGEN_OPERATOR_COMMERCE_READ_RATIO:-0.85}"
            - name: LOADGEN_OPERATOR_ORDER_CREATE_RATIO
              value: "${LOADGEN_OPERATOR_ORDER_CREATE_RATIO:-0.35}"
            - name: LOADGEN_OPERATOR_ORDER_SETTLE_RATIO
              value: "${LOADGEN_OPERATOR_ORDER_SETTLE_RATIO:-0.35}"
            - name: LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO
              value: "${LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO:-0.15}"
            - name: LOADGEN_OPERATOR_RTSP_JOB_RATIO
              value: "${LOADGEN_OPERATOR_RTSP_JOB_RATIO:-0.35}"
            - name: LOADGEN_OPERATOR_TAKE_LIVE_RATIO
              value: "${LOADGEN_OPERATOR_TAKE_LIVE_RATIO:-0.20}"
            - name: LOADGEN_OPERATOR_RESTORE_HOUSE_LOOP
              value: "${LOADGEN_OPERATOR_RESTORE_HOUSE_LOOP:-true}"
          resources:
            requests:
              cpu: ${LOADGEN_OPERATOR_CPU_REQUEST}
              memory: ${LOADGEN_OPERATOR_MEMORY_REQUEST}
            limits:
              cpu: ${LOADGEN_OPERATOR_CPU_LIMIT}
              memory: ${LOADGEN_OPERATOR_MEMORY_LIMIT}
          volumeMounts:
            - name: loadgen-script
              mountPath: /opt/loadgen
              readOnly: true
      volumes:
        - name: loadgen-script
          configMap:
            name: ${LOADGEN_OPERATOR_SCRIPT_CONFIGMAP_NAME}
            defaultMode: 0555
EOF
}

apply_cronjob_manifest() {
  cat <<EOF | apply_or_dry_run
apiVersion: batch/v1
kind: CronJob
metadata:
  name: ${LOADGEN_OPERATOR_CRONJOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${LOADGEN_OPERATOR_JOB_NAME}
    app.kubernetes.io/part-of: streaming-service-app
spec:
  schedule: "${LOADGEN_OPERATOR_CRON_SCHEDULE}"
  concurrencyPolicy: ${LOADGEN_OPERATOR_CRON_CONCURRENCY_POLICY}
  suspend: ${LOADGEN_OPERATOR_CRON_SUSPEND}
  successfulJobsHistoryLimit: ${LOADGEN_OPERATOR_CRON_SUCCESS_HISTORY}
  failedJobsHistoryLimit: ${LOADGEN_OPERATOR_CRON_FAILED_HISTORY}
  jobTemplate:
    metadata:
      labels:
        app.kubernetes.io/name: ${LOADGEN_OPERATOR_JOB_NAME}
        app.kubernetes.io/part-of: streaming-service-app
    spec:
      backoffLimit: 0
      ttlSecondsAfterFinished: ${LOADGEN_OPERATOR_TTL_SECONDS}
      template:
        metadata:
          labels:
            app.kubernetes.io/name: ${LOADGEN_OPERATOR_JOB_NAME}
            app.kubernetes.io/part-of: streaming-service-app
        spec:
          restartPolicy: Never
          containers:
            - name: loadgen
              image: ${LOADGEN_OPERATOR_IMAGE}
              imagePullPolicy: ${LOADGEN_OPERATOR_IMAGE_PULL_POLICY}
              command:
                - node
                - /opt/loadgen/operator-billing-loadgen.mjs
              env:
                - name: LOADGEN_OPERATOR_BASE_URL
                  value: "${LOADGEN_OPERATOR_BASE_URL}"
                - name: LOADGEN_OPERATOR_PERSONA
                  value: "${LOADGEN_OPERATOR_PERSONA:-operator}"
                - name: LOADGEN_OPERATOR_DURATION
                  value: "${LOADGEN_OPERATOR_DURATION:-8m}"
                - name: LOADGEN_OPERATOR_CONCURRENCY
                  value: "${LOADGEN_OPERATOR_CONCURRENCY:-3}"
                - name: LOADGEN_OPERATOR_PAUSE
                  value: "${LOADGEN_OPERATOR_PAUSE:-4s}"
                - name: LOADGEN_OPERATOR_REQUEST_TIMEOUT
                  value: "${LOADGEN_OPERATOR_REQUEST_TIMEOUT:-15s}"
                - name: LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE
                  value: "${LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE:-24}"
                - name: LOADGEN_OPERATOR_BILLING_EVENT_RATIO
                  value: "${LOADGEN_OPERATOR_BILLING_EVENT_RATIO:-0.55}"
                - name: LOADGEN_OPERATOR_PAYMENT_RATIO
                  value: "${LOADGEN_OPERATOR_PAYMENT_RATIO:-0.20}"
                - name: LOADGEN_OPERATOR_PAYMENT_READ_RATIO
                  value: "${LOADGEN_OPERATOR_PAYMENT_READ_RATIO:-0.80}"
                - name: LOADGEN_OPERATOR_COMMERCE_READ_RATIO
                  value: "${LOADGEN_OPERATOR_COMMERCE_READ_RATIO:-0.85}"
                - name: LOADGEN_OPERATOR_ORDER_CREATE_RATIO
                  value: "${LOADGEN_OPERATOR_ORDER_CREATE_RATIO:-0.35}"
                - name: LOADGEN_OPERATOR_ORDER_SETTLE_RATIO
                  value: "${LOADGEN_OPERATOR_ORDER_SETTLE_RATIO:-0.35}"
                - name: LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO
                  value: "${LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO:-0.15}"
                - name: LOADGEN_OPERATOR_RTSP_JOB_RATIO
                  value: "${LOADGEN_OPERATOR_RTSP_JOB_RATIO:-0.35}"
                - name: LOADGEN_OPERATOR_TAKE_LIVE_RATIO
                  value: "${LOADGEN_OPERATOR_TAKE_LIVE_RATIO:-0.20}"
                - name: LOADGEN_OPERATOR_RESTORE_HOUSE_LOOP
                  value: "${LOADGEN_OPERATOR_RESTORE_HOUSE_LOOP:-true}"
              resources:
                requests:
                  cpu: ${LOADGEN_OPERATOR_CPU_REQUEST}
                  memory: ${LOADGEN_OPERATOR_MEMORY_REQUEST}
                limits:
                  cpu: ${LOADGEN_OPERATOR_CPU_LIMIT}
                  memory: ${LOADGEN_OPERATOR_MEMORY_LIMIT}
              volumeMounts:
                - name: loadgen-script
                  mountPath: /opt/loadgen
                  readOnly: true
          volumes:
            - name: loadgen-script
              configMap:
                name: ${LOADGEN_OPERATOR_SCRIPT_CONFIGMAP_NAME}
                defaultMode: 0555
EOF
}

LOADGEN_OPERATOR_FRONTEND_PORT="${LOADGEN_OPERATOR_FRONTEND_PORT:-$(discover_frontend_port)}"
LOADGEN_OPERATOR_BASE_URL="${LOADGEN_OPERATOR_BASE_URL:-http://${FRONTEND_SERVICE_NAME}.${NAMESPACE}.svc.cluster.local${LOADGEN_OPERATOR_FRONTEND_PORT:+:${LOADGEN_OPERATOR_FRONTEND_PORT}}}"

case "${LOADGEN_OPERATOR_K8S_ACTION}" in
  apply)
    kubectl -n "${NAMESPACE}" create configmap "${LOADGEN_OPERATOR_SCRIPT_CONFIGMAP_NAME}" \
      --from-file=operator-billing-loadgen.mjs="${ROOT_DIR}/scripts/loadgen/operator-billing-loadgen.mjs" \
      --dry-run=client -o yaml | apply_or_dry_run

    if [[ "${LOADGEN_OPERATOR_K8S_MODE}" == "cronjob" ]]; then
      apply_cronjob_manifest
      echo "Operator loadgen cronjob prepared in namespace ${NAMESPACE}."
      echo "Profile: ${LOADGEN_OPERATOR_PROFILE}"
      echo "CronJob: ${LOADGEN_OPERATOR_CRONJOB_NAME}"
      echo "Schedule: ${LOADGEN_OPERATOR_CRON_SCHEDULE}"
      echo "Concurrency Policy: ${LOADGEN_OPERATOR_CRON_CONCURRENCY_POLICY}"
    else
      delete_job_if_needed
      apply_job_manifest
      echo "Operator loadgen job prepared in namespace ${NAMESPACE}."
      echo "Profile: ${LOADGEN_OPERATOR_PROFILE}"
    fi

    echo "Base URL: ${LOADGEN_OPERATOR_BASE_URL}"
    echo "Duration: ${LOADGEN_OPERATOR_DURATION:-8m}"
    echo "Concurrency: ${LOADGEN_OPERATOR_CONCURRENCY:-3}"

    if [[ "${LOADGEN_OPERATOR_K8S_MODE}" == "job" ]]; then
      wait_and_stream_logs
    elif [[ "${K8S_DRY_RUN}" != "true" ]]; then
      kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_OPERATOR_CRONJOB_NAME}"
    fi
    ;;
  delete)
    if [[ "${LOADGEN_OPERATOR_K8S_MODE}" == "cronjob" ]]; then
      delete_cronjob_if_needed
      echo "Deleted cronjob ${LOADGEN_OPERATOR_CRONJOB_NAME} in namespace ${NAMESPACE}."
    else
      delete_job_if_needed
      echo "Deleted job ${LOADGEN_OPERATOR_JOB_NAME} in namespace ${NAMESPACE}."
    fi
    ;;
  status)
    if [[ "${LOADGEN_OPERATOR_K8S_MODE}" == "cronjob" ]]; then
      show_cronjob_status
    else
      show_job_status
    fi
    ;;
  pause)
    set_cronjob_suspend true
    ;;
  resume)
    set_cronjob_suspend false
    ;;
  trigger)
    trigger_cronjob_now
    ;;
esac
