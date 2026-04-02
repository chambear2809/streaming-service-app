#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
NAMESPACE="${NAMESPACE:-${STREAMING_K8S_NAMESPACE:-streaming-service-app}}"
FRONTEND_SERVICE_NAME="${FRONTEND_SERVICE_NAME:-streaming-frontend}"
LOADGEN_JOB_NAME="${LOADGEN_JOB_NAME:-broadcast-loadgen}"
LOADGEN_PROFILE="${LOADGEN_PROFILE:-booth}"
LOADGEN_K8S_MODE="${LOADGEN_K8S_MODE:-job}"
LOADGEN_K8S_ACTION="${LOADGEN_K8S_ACTION:-apply}"
LOADGEN_CRONJOB_NAME="${LOADGEN_CRONJOB_NAME:-broadcast-loadgen-recurring}"
LOADGEN_CRON_SCHEDULE="${LOADGEN_CRON_SCHEDULE-}"
LOADGEN_CRON_CONCURRENCY_POLICY="${LOADGEN_CRON_CONCURRENCY_POLICY-}"
LOADGEN_CRON_SUSPEND="${LOADGEN_CRON_SUSPEND:-false}"
LOADGEN_CRON_SUCCESS_HISTORY="${LOADGEN_CRON_SUCCESS_HISTORY:-0}"
LOADGEN_CRON_FAILED_HISTORY="${LOADGEN_CRON_FAILED_HISTORY:-1}"
LOADGEN_TRIGGER_JOB_NAME="${LOADGEN_TRIGGER_JOB_NAME:-}"
LOADGEN_SCRIPT_CONFIGMAP_NAME="${LOADGEN_SCRIPT_CONFIGMAP_NAME:-broadcast-loadgen-script}"
LOADGEN_IMAGE="${LOADGEN_IMAGE:-node:22-alpine}"
LOADGEN_IMAGE_PULL_POLICY="${LOADGEN_IMAGE_PULL_POLICY:-IfNotPresent}"
LOADGEN_TTL_SECONDS="${LOADGEN_TTL_SECONDS:-120}"
LOADGEN_AUTO_DELETE_JOB="${LOADGEN_AUTO_DELETE_JOB:-true}"
LOADGEN_CPU_REQUEST="${LOADGEN_CPU_REQUEST:-250m}"
LOADGEN_CPU_LIMIT="${LOADGEN_CPU_LIMIT:-1000m}"
LOADGEN_MEMORY_REQUEST="${LOADGEN_MEMORY_REQUEST:-256Mi}"
LOADGEN_MEMORY_LIMIT="${LOADGEN_MEMORY_LIMIT:-1024Mi}"
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
LOADGEN_PROFILE="${(L)LOADGEN_PROFILE}"
LOADGEN_K8S_MODE="${(L)LOADGEN_K8S_MODE}"
LOADGEN_K8S_ACTION="${(L)LOADGEN_K8S_ACTION}"

set_env_default() {
  local key="$1" value="$2"

  if (( ! ${+parameters[$key]} )); then
    export "${key}=${value}"
  fi
}

apply_profile_defaults() {
  case "${LOADGEN_PROFILE}" in
    warmup)
      set_env_default LOADGEN_TARGET_VIEWERS "25"
      set_env_default LOADGEN_DURATION "6m"
      set_env_default LOADGEN_RAMP_UP "1m"
      set_env_default LOADGEN_RAMP_DOWN "1m"
      set_env_default LOADGEN_PAGE_VIEWER_RATIO "0.25"
      set_env_default LOADGEN_TRACE_MAP_SESSION_RATIO "0.05"
      ;;
    booth)
      set_env_default LOADGEN_TARGET_VIEWERS "90"
      set_env_default LOADGEN_DURATION "10m"
      set_env_default LOADGEN_RAMP_UP "2m"
      set_env_default LOADGEN_RAMP_DOWN "1m"
      set_env_default LOADGEN_PAGE_VIEWER_RATIO "0.35"
      set_env_default LOADGEN_TRACE_MAP_SESSION_RATIO "0.10"
      ;;
    stress)
      set_env_default LOADGEN_TARGET_VIEWERS "120"
      set_env_default LOADGEN_DURATION "12m"
      set_env_default LOADGEN_RAMP_UP "3m"
      set_env_default LOADGEN_RAMP_DOWN "1m"
      set_env_default LOADGEN_PAGE_VIEWER_RATIO "0.35"
      set_env_default LOADGEN_TRACE_MAP_SESSION_RATIO "0.10"
      ;;
    custom) ;;
    *)
      echo "Unsupported LOADGEN_PROFILE: ${LOADGEN_PROFILE}. Use 'warmup', 'booth', 'stress', or 'custom'." >&2
      exit 1
      ;;
  esac
}

apply_profile_defaults

apply_cron_defaults() {
  if [[ -z "${LOADGEN_CRON_SCHEDULE}" ]]; then
    case "${LOADGEN_PROFILE}" in
      warmup)
        LOADGEN_CRON_SCHEDULE="*/6 * * * *"
        ;;
      booth)
        LOADGEN_CRON_SCHEDULE="*/10 * * * *"
        ;;
      stress)
        LOADGEN_CRON_SCHEDULE="*/12 * * * *"
        ;;
      custom)
        LOADGEN_CRON_SCHEDULE="*/15 * * * *"
        ;;
    esac
  fi

  if [[ -z "${LOADGEN_CRON_CONCURRENCY_POLICY}" ]]; then
    LOADGEN_CRON_CONCURRENCY_POLICY="Allow"
  fi
}

apply_cron_defaults

validate_mode() {
  case "${LOADGEN_K8S_MODE}" in
    job|cronjob) ;;
    *)
      echo "Unsupported LOADGEN_K8S_MODE: ${LOADGEN_K8S_MODE}. Use 'job' or 'cronjob'." >&2
      exit 1
      ;;
  esac
}

validate_mode

validate_action() {
  case "${LOADGEN_K8S_ACTION}" in
    apply|delete|status) ;;
    pause|resume|trigger)
      if [[ "${LOADGEN_K8S_MODE}" != "cronjob" ]]; then
        echo "LOADGEN_K8S_ACTION=${LOADGEN_K8S_ACTION} requires LOADGEN_K8S_MODE=cronjob." >&2
        exit 1
      fi
      ;;
    *)
      echo "Unsupported LOADGEN_K8S_ACTION: ${LOADGEN_K8S_ACTION}. Use 'apply', 'delete', 'status', 'pause', 'resume', or 'trigger'." >&2
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

  kubectl -n "${NAMESPACE}" delete job "${LOADGEN_JOB_NAME}" --ignore-not-found=true >/dev/null
}

delete_cronjob_if_needed() {
  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" delete cronjob "${LOADGEN_CRONJOB_NAME}" --ignore-not-found=true >/dev/null
}

delete_completed_job() {
  if [[ "${K8S_DRY_RUN}" == "true" || "${LOADGEN_AUTO_DELETE_JOB}" != "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" delete job "${LOADGEN_JOB_NAME}" --ignore-not-found=true >/dev/null
}

wait_and_stream_logs() {
  local wait_status=0

  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" wait --for=condition=complete "job/${LOADGEN_JOB_NAME}" --timeout=7200s || wait_status=$?

  echo
  kubectl -n "${NAMESPACE}" get job "${LOADGEN_JOB_NAME}" -o wide 2>/dev/null || true
  kubectl -n "${NAMESPACE}" get pod -l app.kubernetes.io/name="${LOADGEN_JOB_NAME}" -o wide 2>/dev/null || true
  kubectl -n "${NAMESPACE}" logs "job/${LOADGEN_JOB_NAME}" --all-containers=true 2>/dev/null || true

  if (( wait_status != 0 )); then
    return "${wait_status}"
  fi

  delete_completed_job
}

show_job_status() {
  kubectl -n "${NAMESPACE}" get job "${LOADGEN_JOB_NAME}" -o wide 2>/dev/null || echo "Job ${LOADGEN_JOB_NAME} not found in ${NAMESPACE}."
  kubectl -n "${NAMESPACE}" get pod -l app.kubernetes.io/name="${LOADGEN_JOB_NAME}" -o wide
}

show_cronjob_status() {
  kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_CRONJOB_NAME}" -o wide 2>/dev/null || echo "CronJob ${LOADGEN_CRONJOB_NAME} not found in ${NAMESPACE}."
  kubectl -n "${NAMESPACE}" get job,pod -l app.kubernetes.io/name="${LOADGEN_JOB_NAME}" -o wide
}

set_cronjob_suspend() {
  local suspend_value="$1"

  kubectl -n "${NAMESPACE}" patch cronjob "${LOADGEN_CRONJOB_NAME}" \
    --type=merge \
    -p "{\"spec\":{\"suspend\":${suspend_value}}}"
  kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_CRONJOB_NAME}" -o wide
}

trigger_cronjob_now() {
  local manual_job_name="${LOADGEN_TRIGGER_JOB_NAME}"

  if [[ -z "${manual_job_name}" ]]; then
    manual_job_name="${LOADGEN_JOB_NAME}-manual-$(date +%Y%m%d%H%M%S)"
  fi

  kubectl -n "${NAMESPACE}" create job --from=cronjob/"${LOADGEN_CRONJOB_NAME}" "${manual_job_name}"
  kubectl -n "${NAMESPACE}" get job "${manual_job_name}" -o wide
}

apply_job_manifest() {
  cat <<EOF | apply_or_dry_run
apiVersion: batch/v1
kind: Job
metadata:
  name: ${LOADGEN_JOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${LOADGEN_JOB_NAME}
    app.kubernetes.io/part-of: streaming-service-app
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: ${LOADGEN_TTL_SECONDS}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: ${LOADGEN_JOB_NAME}
        app.kubernetes.io/part-of: streaming-service-app
    spec:
      restartPolicy: Never
      containers:
        - name: loadgen
          image: ${LOADGEN_IMAGE}
          imagePullPolicy: ${LOADGEN_IMAGE_PULL_POLICY}
          command:
            - node
            - /opt/loadgen/broadcast-loadgen.mjs
          env:
            - name: LOADGEN_BASE_URL
              value: "${LOADGEN_BASE_URL}"
            - name: LOADGEN_PAGE_PATH
              value: "${LOADGEN_PAGE_PATH:-/broadcast}"
            - name: LOADGEN_STATUS_PATH
              value: "${LOADGEN_STATUS_PATH:-/api/v1/demo/public/broadcast/current}"
            - name: LOADGEN_PLAYLIST_PATH
              value: "${LOADGEN_PLAYLIST_PATH:-/api/v1/demo/public/broadcast/live/index.m3u8}"
            - name: LOADGEN_TRACE_MAP_PATH
              value: "${LOADGEN_TRACE_MAP_PATH:-/api/v1/demo/public/trace-map}"
            - name: LOADGEN_TARGET_VIEWERS
              value: "${LOADGEN_TARGET_VIEWERS:-60}"
            - name: LOADGEN_DURATION
              value: "${LOADGEN_DURATION:-10m}"
            - name: LOADGEN_RAMP_UP
              value: "${LOADGEN_RAMP_UP:-2m}"
            - name: LOADGEN_RAMP_DOWN
              value: "${LOADGEN_RAMP_DOWN:-1m}"
            - name: LOADGEN_SESSION_MIN
              value: "${LOADGEN_SESSION_MIN:-90s}"
            - name: LOADGEN_SESSION_MAX
              value: "${LOADGEN_SESSION_MAX:-4m}"
            - name: LOADGEN_PAGE_VIEWER_RATIO
              value: "${LOADGEN_PAGE_VIEWER_RATIO:-0.35}"
            - name: LOADGEN_TRACE_MAP_SESSION_RATIO
              value: "${LOADGEN_TRACE_MAP_SESSION_RATIO:-0.10}"
            - name: LOADGEN_STATUS_POLL_INTERVAL
              value: "${LOADGEN_STATUS_POLL_INTERVAL:-5s}"
            - name: LOADGEN_REQUEST_TIMEOUT
              value: "${LOADGEN_REQUEST_TIMEOUT:-15s}"
            - name: LOADGEN_PLAYLIST_POLL_FLOOR
              value: "${LOADGEN_PLAYLIST_POLL_FLOOR:-1s}"
            - name: LOADGEN_PLAYLIST_POLL_CEILING
              value: "${LOADGEN_PLAYLIST_POLL_CEILING:-6s}"
            - name: LOADGEN_VARIANT_STRATEGY
              value: "${LOADGEN_VARIANT_STRATEGY:-balanced}"
            - name: LOADGEN_LIVE_EDGE_SEGMENTS
              value: "${LOADGEN_LIVE_EDGE_SEGMENTS:-1}"
            - name: LOADGEN_LIVE_EDGE_PARTS
              value: "${LOADGEN_LIVE_EDGE_PARTS:-8}"
            - name: LOADGEN_MAX_PLAYBACK_ERRORS
              value: "${LOADGEN_MAX_PLAYBACK_ERRORS:-6}"
            - name: LOADGEN_LOG_EVERY
              value: "${LOADGEN_LOG_EVERY:-5s}"
            - name: LOADGEN_VOD_LOOP
              value: "${LOADGEN_VOD_LOOP:-true}"
          resources:
            requests:
              cpu: ${LOADGEN_CPU_REQUEST}
              memory: ${LOADGEN_MEMORY_REQUEST}
            limits:
              cpu: ${LOADGEN_CPU_LIMIT}
              memory: ${LOADGEN_MEMORY_LIMIT}
          volumeMounts:
            - name: loadgen-script
              mountPath: /opt/loadgen
              readOnly: true
      volumes:
        - name: loadgen-script
          configMap:
            name: ${LOADGEN_SCRIPT_CONFIGMAP_NAME}
            defaultMode: 0555
EOF
}

apply_cronjob_manifest() {
  cat <<EOF | apply_or_dry_run
apiVersion: batch/v1
kind: CronJob
metadata:
  name: ${LOADGEN_CRONJOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${LOADGEN_JOB_NAME}
    app.kubernetes.io/part-of: streaming-service-app
spec:
  schedule: "${LOADGEN_CRON_SCHEDULE}"
  concurrencyPolicy: ${LOADGEN_CRON_CONCURRENCY_POLICY}
  suspend: ${LOADGEN_CRON_SUSPEND}
  successfulJobsHistoryLimit: ${LOADGEN_CRON_SUCCESS_HISTORY}
  failedJobsHistoryLimit: ${LOADGEN_CRON_FAILED_HISTORY}
  jobTemplate:
    metadata:
      labels:
        app.kubernetes.io/name: ${LOADGEN_JOB_NAME}
        app.kubernetes.io/part-of: streaming-service-app
    spec:
      backoffLimit: 0
      ttlSecondsAfterFinished: ${LOADGEN_TTL_SECONDS}
      template:
        metadata:
          labels:
            app.kubernetes.io/name: ${LOADGEN_JOB_NAME}
            app.kubernetes.io/part-of: streaming-service-app
        spec:
          restartPolicy: Never
          containers:
            - name: loadgen
              image: ${LOADGEN_IMAGE}
              imagePullPolicy: ${LOADGEN_IMAGE_PULL_POLICY}
              command:
                - node
                - /opt/loadgen/broadcast-loadgen.mjs
              env:
                - name: LOADGEN_BASE_URL
                  value: "${LOADGEN_BASE_URL}"
                - name: LOADGEN_PAGE_PATH
                  value: "${LOADGEN_PAGE_PATH:-/broadcast}"
                - name: LOADGEN_STATUS_PATH
                  value: "${LOADGEN_STATUS_PATH:-/api/v1/demo/public/broadcast/current}"
                - name: LOADGEN_PLAYLIST_PATH
                  value: "${LOADGEN_PLAYLIST_PATH:-/api/v1/demo/public/broadcast/live/index.m3u8}"
                - name: LOADGEN_TRACE_MAP_PATH
                  value: "${LOADGEN_TRACE_MAP_PATH:-/api/v1/demo/public/trace-map}"
                - name: LOADGEN_TARGET_VIEWERS
                  value: "${LOADGEN_TARGET_VIEWERS:-60}"
                - name: LOADGEN_DURATION
                  value: "${LOADGEN_DURATION:-10m}"
                - name: LOADGEN_RAMP_UP
                  value: "${LOADGEN_RAMP_UP:-2m}"
                - name: LOADGEN_RAMP_DOWN
                  value: "${LOADGEN_RAMP_DOWN:-1m}"
                - name: LOADGEN_SESSION_MIN
                  value: "${LOADGEN_SESSION_MIN:-90s}"
                - name: LOADGEN_SESSION_MAX
                  value: "${LOADGEN_SESSION_MAX:-4m}"
                - name: LOADGEN_PAGE_VIEWER_RATIO
                  value: "${LOADGEN_PAGE_VIEWER_RATIO:-0.35}"
                - name: LOADGEN_TRACE_MAP_SESSION_RATIO
                  value: "${LOADGEN_TRACE_MAP_SESSION_RATIO:-0.10}"
                - name: LOADGEN_STATUS_POLL_INTERVAL
                  value: "${LOADGEN_STATUS_POLL_INTERVAL:-5s}"
                - name: LOADGEN_REQUEST_TIMEOUT
                  value: "${LOADGEN_REQUEST_TIMEOUT:-15s}"
                - name: LOADGEN_PLAYLIST_POLL_FLOOR
                  value: "${LOADGEN_PLAYLIST_POLL_FLOOR:-1s}"
                - name: LOADGEN_PLAYLIST_POLL_CEILING
                  value: "${LOADGEN_PLAYLIST_POLL_CEILING:-6s}"
                - name: LOADGEN_VARIANT_STRATEGY
                  value: "${LOADGEN_VARIANT_STRATEGY:-balanced}"
                - name: LOADGEN_LIVE_EDGE_SEGMENTS
                  value: "${LOADGEN_LIVE_EDGE_SEGMENTS:-1}"
                - name: LOADGEN_LIVE_EDGE_PARTS
                  value: "${LOADGEN_LIVE_EDGE_PARTS:-8}"
                - name: LOADGEN_MAX_PLAYBACK_ERRORS
                  value: "${LOADGEN_MAX_PLAYBACK_ERRORS:-6}"
                - name: LOADGEN_LOG_EVERY
                  value: "${LOADGEN_LOG_EVERY:-5s}"
                - name: LOADGEN_VOD_LOOP
                  value: "${LOADGEN_VOD_LOOP:-true}"
              resources:
                requests:
                  cpu: ${LOADGEN_CPU_REQUEST}
                  memory: ${LOADGEN_MEMORY_REQUEST}
                limits:
                  cpu: ${LOADGEN_CPU_LIMIT}
                  memory: ${LOADGEN_MEMORY_LIMIT}
              volumeMounts:
                - name: loadgen-script
                  mountPath: /opt/loadgen
                  readOnly: true
          volumes:
            - name: loadgen-script
              configMap:
                name: ${LOADGEN_SCRIPT_CONFIGMAP_NAME}
                defaultMode: 0555
EOF
}

LOADGEN_FRONTEND_PORT="${LOADGEN_FRONTEND_PORT:-$(discover_frontend_port)}"
LOADGEN_BASE_URL="${LOADGEN_BASE_URL:-http://${FRONTEND_SERVICE_NAME}.${NAMESPACE}.svc.cluster.local${LOADGEN_FRONTEND_PORT:+:${LOADGEN_FRONTEND_PORT}}}"

case "${LOADGEN_K8S_ACTION}" in
  apply)
    kubectl -n "${NAMESPACE}" create configmap "${LOADGEN_SCRIPT_CONFIGMAP_NAME}" \
      --from-file=broadcast-loadgen.mjs="${ROOT_DIR}/scripts/loadgen/broadcast-loadgen.mjs" \
      --dry-run=client -o yaml | apply_or_dry_run

    if [[ "${LOADGEN_K8S_MODE}" == "cronjob" ]]; then
      apply_cronjob_manifest
      echo "Broadcast loadgen cronjob prepared in namespace ${NAMESPACE}."
      echo "Profile: ${LOADGEN_PROFILE}"
      echo "CronJob: ${LOADGEN_CRONJOB_NAME}"
      echo "Schedule: ${LOADGEN_CRON_SCHEDULE}"
      echo "Concurrency Policy: ${LOADGEN_CRON_CONCURRENCY_POLICY}"
    else
      delete_job_if_needed
      apply_job_manifest
      echo "Broadcast loadgen job prepared in namespace ${NAMESPACE}."
      echo "Profile: ${LOADGEN_PROFILE}"
    fi

    echo "Base URL: ${LOADGEN_BASE_URL}"
    echo "Viewers: ${LOADGEN_TARGET_VIEWERS:-60}"
    echo "Duration: ${LOADGEN_DURATION:-10m}"

    if [[ "${LOADGEN_K8S_MODE}" == "job" ]]; then
      wait_and_stream_logs
    elif [[ "${K8S_DRY_RUN}" != "true" ]]; then
      kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_CRONJOB_NAME}"
    fi
    ;;
  delete)
    if [[ "${LOADGEN_K8S_MODE}" == "cronjob" ]]; then
      delete_cronjob_if_needed
      echo "Deleted cronjob ${LOADGEN_CRONJOB_NAME} in namespace ${NAMESPACE}."
    else
      delete_job_if_needed
      echo "Deleted job ${LOADGEN_JOB_NAME} in namespace ${NAMESPACE}."
    fi
    ;;
  status)
    if [[ "${LOADGEN_K8S_MODE}" == "cronjob" ]]; then
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
