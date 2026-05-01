#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"

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

NAMESPACE="${NAMESPACE:-${STREAMING_K8S_NAMESPACE:-streaming-service-app}}"
FRONTEND_SERVICE_NAME="${FRONTEND_SERVICE_NAME:-streaming-frontend}"
LOADGEN_BROWSER_JOB_NAME="${LOADGEN_BROWSER_JOB_NAME:-browser-rum-loadgen}"
LOADGEN_BROWSER_PROFILE="${LOADGEN_BROWSER_PROFILE:-booth}"
LOADGEN_BROWSER_K8S_MODE="${LOADGEN_BROWSER_K8S_MODE:-job}"
LOADGEN_BROWSER_K8S_ACTION="${LOADGEN_BROWSER_K8S_ACTION:-apply}"
LOADGEN_BROWSER_CRONJOB_NAME="${LOADGEN_BROWSER_CRONJOB_NAME:-browser-rum-loadgen-recurring}"
LOADGEN_BROWSER_CRON_SCHEDULE="${LOADGEN_BROWSER_CRON_SCHEDULE-}"
LOADGEN_BROWSER_CRON_CONCURRENCY_POLICY="${LOADGEN_BROWSER_CRON_CONCURRENCY_POLICY-}"
LOADGEN_BROWSER_CRON_SUSPEND="${LOADGEN_BROWSER_CRON_SUSPEND:-false}"
LOADGEN_BROWSER_CRON_SUCCESS_HISTORY="${LOADGEN_BROWSER_CRON_SUCCESS_HISTORY:-0}"
LOADGEN_BROWSER_CRON_FAILED_HISTORY="${LOADGEN_BROWSER_CRON_FAILED_HISTORY:-1}"
LOADGEN_BROWSER_TRIGGER_JOB_NAME="${LOADGEN_BROWSER_TRIGGER_JOB_NAME:-}"
LOADGEN_BROWSER_SCRIPT_CONFIGMAP_NAME="${LOADGEN_BROWSER_SCRIPT_CONFIGMAP_NAME:-browser-rum-loadgen-script}"
LOADGEN_BROWSER_IMAGE="${LOADGEN_BROWSER_IMAGE:-mcr.microsoft.com/playwright:v1.56.1-noble}"
LOADGEN_BROWSER_PLAYWRIGHT_VERSION="${LOADGEN_BROWSER_PLAYWRIGHT_VERSION:-1.56.1}"
LOADGEN_BROWSER_IMAGE_PULL_POLICY="${LOADGEN_BROWSER_IMAGE_PULL_POLICY:-IfNotPresent}"
LOADGEN_BROWSER_TTL_SECONDS="${LOADGEN_BROWSER_TTL_SECONDS:-120}"
LOADGEN_BROWSER_AUTO_DELETE_JOB="${LOADGEN_BROWSER_AUTO_DELETE_JOB:-true}"
LOADGEN_BROWSER_CPU_REQUEST="${LOADGEN_BROWSER_CPU_REQUEST:-1000m}"
LOADGEN_BROWSER_CPU_LIMIT="${LOADGEN_BROWSER_CPU_LIMIT:-3000m}"
LOADGEN_BROWSER_MEMORY_REQUEST="${LOADGEN_BROWSER_MEMORY_REQUEST:-2048Mi}"
LOADGEN_BROWSER_MEMORY_LIMIT="${LOADGEN_BROWSER_MEMORY_LIMIT:-6144Mi}"
LOADGEN_BROWSER_ROUTER_EGRESS="${LOADGEN_BROWSER_ROUTER_EGRESS:-true}"
LOADGEN_BROWSER_NODE_SELECTOR_KEY="${LOADGEN_BROWSER_NODE_SELECTOR_KEY:-eks.amazonaws.com/nodegroup}"
LOADGEN_BROWSER_NODE_SELECTOR_VALUE="${LOADGEN_BROWSER_NODE_SELECTOR_VALUE:-private}"
LOADGEN_BROWSER_DEDICATED_TOLERATION="${LOADGEN_BROWSER_DEDICATED_TOLERATION:-otel}"
K8S_DRY_RUN="${K8S_DRY_RUN:-false}"

LOADGEN_BROWSER_PROFILE="${(L)LOADGEN_BROWSER_PROFILE}"
LOADGEN_BROWSER_K8S_MODE="${(L)LOADGEN_BROWSER_K8S_MODE}"
LOADGEN_BROWSER_K8S_ACTION="${(L)LOADGEN_BROWSER_K8S_ACTION}"
LOADGEN_BROWSER_ROUTER_EGRESS="${(L)LOADGEN_BROWSER_ROUTER_EGRESS}"

set_env_default() {
  local key="$1" value="$2"

  if (( ! ${+parameters[$key]} )); then
    export "${key}=${value}"
  fi
}

apply_profile_defaults() {
  case "${LOADGEN_BROWSER_PROFILE}" in
    warmup)
      set_env_default LOADGEN_BROWSER_PATHS "/broadcast,/broadcast,/,/#operations,/demo-monkey"
      set_env_default LOADGEN_BROWSER_AUTH_PERSONA "operator"
      set_env_default LOADGEN_BROWSER_TARGET_BROWSERS "3"
      set_env_default LOADGEN_BROWSER_DURATION "8m"
      set_env_default LOADGEN_BROWSER_RAMP_UP "1m"
      set_env_default LOADGEN_BROWSER_RAMP_DOWN "1m"
      set_env_default LOADGEN_BROWSER_SESSION_MIN "1m"
      set_env_default LOADGEN_BROWSER_SESSION_MAX "3m"
      set_env_default LOADGEN_BROWSER_THINK_TIME_MIN "3s"
      set_env_default LOADGEN_BROWSER_THINK_TIME_MAX "8s"
      set_env_default LOADGEN_BROWSER_TRACE_MAP_RATIO "0.12"
      set_env_default LOADGEN_BROWSER_NAVIGATION_RATIO "0.06"
      ;;
    booth)
      set_env_default LOADGEN_BROWSER_PATHS "/broadcast,/broadcast,/,/#operations,/demo-monkey"
      set_env_default LOADGEN_BROWSER_AUTH_PERSONA "operator"
      set_env_default LOADGEN_BROWSER_TARGET_BROWSERS "6"
      set_env_default LOADGEN_BROWSER_DURATION "15m"
      set_env_default LOADGEN_BROWSER_RAMP_UP "2m"
      set_env_default LOADGEN_BROWSER_RAMP_DOWN "2m"
      set_env_default LOADGEN_BROWSER_SESSION_MIN "1m"
      set_env_default LOADGEN_BROWSER_SESSION_MAX "4m"
      set_env_default LOADGEN_BROWSER_THINK_TIME_MIN "3s"
      set_env_default LOADGEN_BROWSER_THINK_TIME_MAX "9s"
      set_env_default LOADGEN_BROWSER_TRACE_MAP_RATIO "0.15"
      set_env_default LOADGEN_BROWSER_NAVIGATION_RATIO "0.08"
      ;;
    stress)
      set_env_default LOADGEN_BROWSER_PATHS "/broadcast,/broadcast,/,/#operations,/demo-monkey"
      set_env_default LOADGEN_BROWSER_AUTH_PERSONA "operator"
      set_env_default LOADGEN_BROWSER_TARGET_BROWSERS "16"
      set_env_default LOADGEN_BROWSER_DURATION "15m"
      set_env_default LOADGEN_BROWSER_RAMP_UP "2m"
      set_env_default LOADGEN_BROWSER_RAMP_DOWN "1m"
      set_env_default LOADGEN_BROWSER_SESSION_MIN "45s"
      set_env_default LOADGEN_BROWSER_SESSION_MAX "3m"
      set_env_default LOADGEN_BROWSER_THINK_TIME_MIN "2s"
      set_env_default LOADGEN_BROWSER_THINK_TIME_MAX "6s"
      set_env_default LOADGEN_BROWSER_TRACE_MAP_RATIO "0.30"
      set_env_default LOADGEN_BROWSER_NAVIGATION_RATIO "0.12"
      ;;
    custom) ;;
    *)
      echo "Unsupported LOADGEN_BROWSER_PROFILE: ${LOADGEN_BROWSER_PROFILE}. Use 'warmup', 'booth', 'stress', or 'custom'." >&2
      exit 1
      ;;
  esac
}

apply_profile_defaults

apply_cron_defaults() {
  if [[ -z "${LOADGEN_BROWSER_CRON_SCHEDULE}" ]]; then
    case "${LOADGEN_BROWSER_PROFILE}" in
      warmup)
        LOADGEN_BROWSER_CRON_SCHEDULE="*/5 * * * *"
        ;;
      booth)
        LOADGEN_BROWSER_CRON_SCHEDULE="*/5 * * * *"
        ;;
      stress)
        LOADGEN_BROWSER_CRON_SCHEDULE="*/5 * * * *"
        ;;
      custom)
        LOADGEN_BROWSER_CRON_SCHEDULE="*/15 * * * *"
        ;;
    esac
  fi

  if [[ -z "${LOADGEN_BROWSER_CRON_CONCURRENCY_POLICY}" ]]; then
    LOADGEN_BROWSER_CRON_CONCURRENCY_POLICY="Allow"
  fi
}

apply_cron_defaults

validate_mode() {
  case "${LOADGEN_BROWSER_K8S_MODE}" in
    job|cronjob) ;;
    *)
      echo "Unsupported LOADGEN_BROWSER_K8S_MODE: ${LOADGEN_BROWSER_K8S_MODE}. Use 'job' or 'cronjob'." >&2
      exit 1
      ;;
  esac
}

validate_mode

validate_action() {
  case "${LOADGEN_BROWSER_K8S_ACTION}" in
    apply|delete|status) ;;
    pause|resume|trigger)
      if [[ "${LOADGEN_BROWSER_K8S_MODE}" != "cronjob" ]]; then
        echo "LOADGEN_BROWSER_K8S_ACTION=${LOADGEN_BROWSER_K8S_ACTION} requires LOADGEN_BROWSER_K8S_MODE=cronjob." >&2
        exit 1
      fi
      ;;
    *)
      echo "Unsupported LOADGEN_BROWSER_K8S_ACTION: ${LOADGEN_BROWSER_K8S_ACTION}. Use 'apply', 'delete', 'status', 'pause', 'resume', or 'trigger'." >&2
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

  kubectl -n "${NAMESPACE}" delete job "${LOADGEN_BROWSER_JOB_NAME}" --ignore-not-found=true >/dev/null
}

delete_cronjob_if_needed() {
  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" delete cronjob "${LOADGEN_BROWSER_CRONJOB_NAME}" --ignore-not-found=true >/dev/null
}

delete_completed_job() {
  if [[ "${K8S_DRY_RUN}" == "true" || "${LOADGEN_BROWSER_AUTO_DELETE_JOB}" != "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" delete job "${LOADGEN_BROWSER_JOB_NAME}" --ignore-not-found=true >/dev/null
}

wait_and_stream_logs() {
  local wait_status=0

  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" wait --for=condition=complete "job/${LOADGEN_BROWSER_JOB_NAME}" --timeout=7200s || wait_status=$?

  echo
  kubectl -n "${NAMESPACE}" get job "${LOADGEN_BROWSER_JOB_NAME}" -o wide 2>/dev/null || true
  kubectl -n "${NAMESPACE}" get pod -l app.kubernetes.io/name="${LOADGEN_BROWSER_JOB_NAME}" -o wide 2>/dev/null || true
  kubectl -n "${NAMESPACE}" logs "job/${LOADGEN_BROWSER_JOB_NAME}" --all-containers=true 2>/dev/null || true

  if (( wait_status != 0 )); then
    return "${wait_status}"
  fi

  delete_completed_job
}

show_job_status() {
  kubectl -n "${NAMESPACE}" get job "${LOADGEN_BROWSER_JOB_NAME}" -o wide 2>/dev/null || echo "Job ${LOADGEN_BROWSER_JOB_NAME} not found in ${NAMESPACE}."
  kubectl -n "${NAMESPACE}" get pod -l app.kubernetes.io/name="${LOADGEN_BROWSER_JOB_NAME}" -o wide
}

show_cronjob_status() {
  kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_BROWSER_CRONJOB_NAME}" -o wide 2>/dev/null || echo "CronJob ${LOADGEN_BROWSER_CRONJOB_NAME} not found in ${NAMESPACE}."
  kubectl -n "${NAMESPACE}" get job,pod -l app.kubernetes.io/name="${LOADGEN_BROWSER_JOB_NAME}" -o wide
}

set_cronjob_suspend() {
  local suspend_value="$1"

  kubectl -n "${NAMESPACE}" patch cronjob "${LOADGEN_BROWSER_CRONJOB_NAME}" \
    --type=merge \
    -p "{\"spec\":{\"suspend\":${suspend_value}}}"
  kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_BROWSER_CRONJOB_NAME}" -o wide
}

trigger_cronjob_now() {
  local manual_job_name="${LOADGEN_BROWSER_TRIGGER_JOB_NAME}"

  if [[ -z "${manual_job_name}" ]]; then
    manual_job_name="${LOADGEN_BROWSER_JOB_NAME}-manual-$(date +%Y%m%d%H%M%S)"
  fi

  kubectl -n "${NAMESPACE}" create job --from=cronjob/"${LOADGEN_BROWSER_CRONJOB_NAME}" "${manual_job_name}"
  kubectl -n "${NAMESPACE}" get job "${manual_job_name}" -o wide
}

print_indented() {
  local indent="$1" text="$2"
  printf "%*s%s\n" "${indent}" "" "${text}"
}

emit_router_egress_placement() {
  local indent="$1"

  [[ "${LOADGEN_BROWSER_ROUTER_EGRESS}" == "true" ]] || return 0
  [[ -n "${LOADGEN_BROWSER_NODE_SELECTOR_KEY}" && -n "${LOADGEN_BROWSER_NODE_SELECTOR_VALUE}" ]] || return 0

  print_indented "${indent}" "nodeSelector:"
  print_indented "$((indent + 2))" "\"${LOADGEN_BROWSER_NODE_SELECTOR_KEY}\": \"${LOADGEN_BROWSER_NODE_SELECTOR_VALUE}\""

  if [[ -n "${LOADGEN_BROWSER_DEDICATED_TOLERATION}" ]]; then
    print_indented "${indent}" "tolerations:"
    print_indented "$((indent + 2))" "- key: dedicated"
    print_indented "$((indent + 4))" "operator: Equal"
    print_indented "$((indent + 4))" "value: ${LOADGEN_BROWSER_DEDICATED_TOLERATION}"
    print_indented "$((indent + 4))" "effect: NoSchedule"
  fi
}

emit_container_env() {
  local indent="$1"

  print_indented "${indent}" "- name: LOADGEN_BROWSER_BASE_URL"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_BASE_URL}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_PATHS"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_PATHS:-/broadcast}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_TARGET_BROWSERS"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_TARGET_BROWSERS:-6}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_DURATION"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_DURATION:-10m}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_RAMP_UP"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_RAMP_UP:-1m}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_RAMP_DOWN"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_RAMP_DOWN:-1m}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_SESSION_MIN"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_SESSION_MIN:-45s}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_SESSION_MAX"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_SESSION_MAX:-2m}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_THINK_TIME_MIN"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_THINK_TIME_MIN:-2s}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_THINK_TIME_MAX"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_THINK_TIME_MAX:-6s}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_TRACE_MAP_RATIO"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_TRACE_MAP_RATIO:-0.20}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_NAVIGATION_RATIO"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_NAVIGATION_RATIO:-0.08}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_AUTH_PERSONA"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_AUTH_PERSONA:-}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_BROWSER"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_BROWSER:-chromium}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_HEADLESS"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_HEADLESS:-true}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_VIEWPORT"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_VIEWPORT:-}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_NAVIGATION_TIMEOUT"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_NAVIGATION_TIMEOUT:-30s}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_ACTION_TIMEOUT"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_ACTION_TIMEOUT:-10s}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_RUM_FLUSH_WAIT"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_RUM_FLUSH_WAIT:-8s}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_EGRESS_CHECK_URL"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_EGRESS_CHECK_URL:-https://checkip.amazonaws.com}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_LOG_EVERY"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_LOG_EVERY:-5s}\""
  print_indented "${indent}" "- name: LOADGEN_BROWSER_PLAYWRIGHT_VERSION"
  print_indented "$((indent + 2))" "value: \"${LOADGEN_BROWSER_PLAYWRIGHT_VERSION}\""
}

emit_container_command() {
  local indent="$1"

  print_indented "${indent}" "command:"
  print_indented "$((indent + 2))" "- /bin/bash"
  print_indented "$((indent + 2))" "- -lc"
  print_indented "$((indent + 2))" "- |"
  print_indented "$((indent + 4))" "set -euo pipefail"
  print_indented "$((indent + 4))" "mkdir -p /tmp/browser-rum-loadgen"
  print_indented "$((indent + 4))" "cd /tmp/browser-rum-loadgen"
  print_indented "$((indent + 4))" "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --no-audit --no-fund --omit=dev \"playwright@${LOADGEN_BROWSER_PLAYWRIGHT_VERSION}\" >/dev/null"
  print_indented "$((indent + 4))" "export NODE_PATH=\"\${PWD}/node_modules\""
  print_indented "$((indent + 4))" "node /opt/loadgen/browser-rum-loadgen.mjs"
}

apply_job_manifest() {
  cat <<EOF | apply_or_dry_run
apiVersion: batch/v1
kind: Job
metadata:
  name: ${LOADGEN_BROWSER_JOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${LOADGEN_BROWSER_JOB_NAME}
    app.kubernetes.io/part-of: streaming-service-app
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: ${LOADGEN_BROWSER_TTL_SECONDS}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: ${LOADGEN_BROWSER_JOB_NAME}
        app.kubernetes.io/part-of: streaming-service-app
    spec:
      restartPolicy: Never
$(emit_router_egress_placement 6)
      containers:
        - name: browser-rum-loadgen
          image: ${LOADGEN_BROWSER_IMAGE}
          imagePullPolicy: ${LOADGEN_BROWSER_IMAGE_PULL_POLICY}
$(emit_container_command 10)
          env:
$(emit_container_env 12)
          resources:
            requests:
              cpu: ${LOADGEN_BROWSER_CPU_REQUEST}
              memory: ${LOADGEN_BROWSER_MEMORY_REQUEST}
            limits:
              cpu: ${LOADGEN_BROWSER_CPU_LIMIT}
              memory: ${LOADGEN_BROWSER_MEMORY_LIMIT}
          volumeMounts:
            - name: loadgen-script
              mountPath: /opt/loadgen
              readOnly: true
      volumes:
        - name: loadgen-script
          configMap:
            name: ${LOADGEN_BROWSER_SCRIPT_CONFIGMAP_NAME}
            defaultMode: 0555
EOF
}

apply_cronjob_manifest() {
  cat <<EOF | apply_or_dry_run
apiVersion: batch/v1
kind: CronJob
metadata:
  name: ${LOADGEN_BROWSER_CRONJOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${LOADGEN_BROWSER_JOB_NAME}
    app.kubernetes.io/part-of: streaming-service-app
spec:
  schedule: "${LOADGEN_BROWSER_CRON_SCHEDULE}"
  concurrencyPolicy: ${LOADGEN_BROWSER_CRON_CONCURRENCY_POLICY}
  suspend: ${LOADGEN_BROWSER_CRON_SUSPEND}
  successfulJobsHistoryLimit: ${LOADGEN_BROWSER_CRON_SUCCESS_HISTORY}
  failedJobsHistoryLimit: ${LOADGEN_BROWSER_CRON_FAILED_HISTORY}
  jobTemplate:
    metadata:
      labels:
        app.kubernetes.io/name: ${LOADGEN_BROWSER_JOB_NAME}
        app.kubernetes.io/part-of: streaming-service-app
    spec:
      backoffLimit: 0
      ttlSecondsAfterFinished: ${LOADGEN_BROWSER_TTL_SECONDS}
      template:
        metadata:
          labels:
            app.kubernetes.io/name: ${LOADGEN_BROWSER_JOB_NAME}
            app.kubernetes.io/part-of: streaming-service-app
        spec:
          restartPolicy: Never
$(emit_router_egress_placement 10)
          containers:
            - name: browser-rum-loadgen
              image: ${LOADGEN_BROWSER_IMAGE}
              imagePullPolicy: ${LOADGEN_BROWSER_IMAGE_PULL_POLICY}
$(emit_container_command 14)
              env:
$(emit_container_env 16)
              resources:
                requests:
                  cpu: ${LOADGEN_BROWSER_CPU_REQUEST}
                  memory: ${LOADGEN_BROWSER_MEMORY_REQUEST}
                limits:
                  cpu: ${LOADGEN_BROWSER_CPU_LIMIT}
                  memory: ${LOADGEN_BROWSER_MEMORY_LIMIT}
              volumeMounts:
                - name: loadgen-script
                  mountPath: /opt/loadgen
                  readOnly: true
          volumes:
            - name: loadgen-script
              configMap:
                name: ${LOADGEN_BROWSER_SCRIPT_CONFIGMAP_NAME}
                defaultMode: 0555
EOF
}

LOADGEN_BROWSER_FRONTEND_PORT="${LOADGEN_BROWSER_FRONTEND_PORT:-$(discover_frontend_port)}"
LOADGEN_BROWSER_BASE_URL="${LOADGEN_BROWSER_BASE_URL:-${LOADGEN_BASE_URL:-http://${FRONTEND_SERVICE_NAME}.${NAMESPACE}.svc.cluster.local${LOADGEN_BROWSER_FRONTEND_PORT:+:${LOADGEN_BROWSER_FRONTEND_PORT}}}}"

case "${LOADGEN_BROWSER_K8S_ACTION}" in
  apply)
    kubectl -n "${NAMESPACE}" create configmap "${LOADGEN_BROWSER_SCRIPT_CONFIGMAP_NAME}" \
      --from-file=browser-rum-loadgen.mjs="${ROOT_DIR}/scripts/loadgen/browser-rum-loadgen.mjs" \
      --dry-run=client -o yaml | apply_or_dry_run

    if [[ "${LOADGEN_BROWSER_K8S_MODE}" == "cronjob" ]]; then
      apply_cronjob_manifest
      echo "Browser RUM loadgen cronjob prepared in namespace ${NAMESPACE}."
      echo "Profile: ${LOADGEN_BROWSER_PROFILE}"
      echo "CronJob: ${LOADGEN_BROWSER_CRONJOB_NAME}"
      echo "Schedule: ${LOADGEN_BROWSER_CRON_SCHEDULE}"
      echo "Concurrency Policy: ${LOADGEN_BROWSER_CRON_CONCURRENCY_POLICY}"
    else
      delete_job_if_needed
      apply_job_manifest
      echo "Browser RUM loadgen job prepared in namespace ${NAMESPACE}."
      echo "Profile: ${LOADGEN_BROWSER_PROFILE}"
    fi

    echo "Base URL: ${LOADGEN_BROWSER_BASE_URL}"
    echo "Browser contexts: ${LOADGEN_BROWSER_TARGET_BROWSERS:-6}"
    echo "Duration: ${LOADGEN_BROWSER_DURATION:-10m}"
    echo "Router egress placement: ${LOADGEN_BROWSER_ROUTER_EGRESS}"

    if [[ "${LOADGEN_BROWSER_K8S_MODE}" == "job" ]]; then
      wait_and_stream_logs
    elif [[ "${K8S_DRY_RUN}" != "true" ]]; then
      kubectl -n "${NAMESPACE}" get cronjob "${LOADGEN_BROWSER_CRONJOB_NAME}"
    fi
    ;;
  delete)
    if [[ "${LOADGEN_BROWSER_K8S_MODE}" == "cronjob" ]]; then
      delete_cronjob_if_needed
      echo "Deleted cronjob ${LOADGEN_BROWSER_CRONJOB_NAME} in namespace ${NAMESPACE}."
    else
      delete_job_if_needed
      echo "Deleted job ${LOADGEN_BROWSER_JOB_NAME} in namespace ${NAMESPACE}."
    fi
    ;;
  status)
    if [[ "${LOADGEN_BROWSER_K8S_MODE}" == "cronjob" ]]; then
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
