#!/usr/bin/env zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
NAMESPACE="${NAMESPACE:-streaming-service-app}"
RTSP_SERVICE_NAME="${RTSP_SERVICE_NAME:-media-service-demo-rtsp}"
FRONTEND_SERVICE_NAME="${FRONTEND_SERVICE_NAME:-streaming-frontend}"
THOUSANDEYES_SECRET_NAME="${THOUSANDEYES_SECRET_NAME:-thousandeyes-api}"
THOUSANDEYES_SCRIPT_CONFIGMAP_NAME="${THOUSANDEYES_SCRIPT_CONFIGMAP_NAME:-thousandeyes-rtsp-script}"
THOUSANDEYES_JOB_NAME="${THOUSANDEYES_JOB_NAME:-thousandeyes-rtsp-tests}"
THOUSANDEYES_JOB_ACTION="${THOUSANDEYES_JOB_ACTION:-create-all}"
THOUSANDEYES_API_BASE_URL="${THOUSANDEYES_API_BASE_URL:-https://api.thousandeyes.com/v7}"
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

THOUSANDEYES_BEARER_TOKEN="${THOUSANDEYES_BEARER_TOKEN:-${THOUSANDEYES_TOKEN:-}}"

discover_rtsp_server() {
  local hostname ip

  hostname="$(kubectl -n "${NAMESPACE}" get service "${RTSP_SERVICE_NAME}" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
  if [[ -n "${hostname}" ]]; then
    printf '%s' "${hostname}"
    return 0
  fi

  ip="$(kubectl -n "${NAMESPACE}" get service "${RTSP_SERVICE_NAME}" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  if [[ -n "${ip}" ]]; then
    printf '%s' "${ip}"
    return 0
  fi

  return 1
}

discover_rtsp_port() {
  local named_port first_port

  named_port="$(kubectl -n "${NAMESPACE}" get service "${RTSP_SERVICE_NAME}" -o jsonpath='{.spec.ports[?(@.name=="rtsp")].port}' 2>/dev/null || true)"
  if [[ -n "${named_port}" ]]; then
    printf '%s' "${named_port}"
    return 0
  fi

  first_port="$(kubectl -n "${NAMESPACE}" get service "${RTSP_SERVICE_NAME}" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || true)"
  if [[ -n "${first_port}" ]]; then
    printf '%s' "${first_port}"
    return 0
  fi

  return 1
}

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

require_env() {
  local name
  for name in "$@"; do
    if [[ -z "${(P)name:-}" ]]; then
      echo "Missing required environment variable: ${name}" >&2
      return 1
    fi
  done
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

  kubectl -n "${NAMESPACE}" delete job "${THOUSANDEYES_JOB_NAME}" --ignore-not-found=true >/dev/null
}

wait_and_stream_logs() {
  if [[ "${K8S_DRY_RUN}" == "true" ]]; then
    return 0
  fi

  kubectl -n "${NAMESPACE}" wait --for=condition=complete "job/${THOUSANDEYES_JOB_NAME}" --timeout=180s
  echo
  kubectl -n "${NAMESPACE}" logs "job/${THOUSANDEYES_JOB_NAME}"
}

require_env THOUSANDEYES_BEARER_TOKEN TE_SOURCE_AGENT_IDS TE_TARGET_AGENT_ID

TE_RTSP_SERVER="${TE_RTSP_SERVER:-$(discover_rtsp_server)}"
TE_RTSP_PORT="${TE_RTSP_PORT:-$(discover_rtsp_port)}"
TE_DEMO_MONKEY_FRONTEND_PORT="${TE_DEMO_MONKEY_FRONTEND_PORT:-$(discover_frontend_port)}"
TE_DEMO_MONKEY_FRONTEND_BASE_URL="${TE_DEMO_MONKEY_FRONTEND_BASE_URL:-http://${FRONTEND_SERVICE_NAME}.${NAMESPACE}.svc.cluster.local${TE_DEMO_MONKEY_FRONTEND_PORT:+:${TE_DEMO_MONKEY_FRONTEND_PORT}}}"
TE_TRACE_MAP_TEST_URL="${TE_TRACE_MAP_TEST_URL:-${TE_DEMO_MONKEY_FRONTEND_BASE_URL%/}/api/v1/demo/public/trace-map}"
TE_BROADCAST_TEST_URL="${TE_BROADCAST_TEST_URL:-${TE_DEMO_MONKEY_FRONTEND_BASE_URL%/}/api/v1/demo/public/broadcast/live/index.m3u8}"

kubectl -n "${NAMESPACE}" create secret generic "${THOUSANDEYES_SECRET_NAME}" \
  --from-literal=THOUSANDEYES_BEARER_TOKEN="${THOUSANDEYES_BEARER_TOKEN}" \
  --dry-run=client -o yaml | apply_or_dry_run

kubectl -n "${NAMESPACE}" create configmap "${THOUSANDEYES_SCRIPT_CONFIGMAP_NAME}" \
  --from-file=run.sh="${ROOT_DIR}/scripts/thousandeyes/create-rtsp-tests-in-cluster.sh" \
  --dry-run=client -o yaml | apply_or_dry_run

delete_job_if_needed

cat <<EOF | apply_or_dry_run
apiVersion: batch/v1
kind: Job
metadata:
  name: ${THOUSANDEYES_JOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: ${THOUSANDEYES_JOB_NAME}
    app.kubernetes.io/part-of: streaming-service-app
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 600
  template:
    metadata:
      labels:
        app.kubernetes.io/name: ${THOUSANDEYES_JOB_NAME}
        app.kubernetes.io/part-of: streaming-service-app
    spec:
      restartPolicy: Never
      containers:
        - name: creator
          image: curlimages/curl:8.13.0
          imagePullPolicy: IfNotPresent
          command:
            - sh
            - /opt/thousandeyes/run.sh
            - ${THOUSANDEYES_JOB_ACTION}
          env:
            - name: THOUSANDEYES_API_BASE_URL
              value: "${THOUSANDEYES_API_BASE_URL}"
            - name: THOUSANDEYES_ACCOUNT_GROUP_ID
              value: "${THOUSANDEYES_ACCOUNT_GROUP_ID:-}"
            - name: TE_SOURCE_AGENT_IDS
              value: "${TE_SOURCE_AGENT_IDS}"
            - name: TE_TARGET_AGENT_ID
              value: "${TE_TARGET_AGENT_ID}"
            - name: TE_UDP_TARGET_AGENT_ID
              value: "${TE_UDP_TARGET_AGENT_ID:-}"
            - name: TE_DSCP_ID
              value: "${TE_DSCP_ID:-0}"
            - name: TE_RTSP_SERVER
              value: "${TE_RTSP_SERVER}"
            - name: TE_RTSP_PORT
              value: "${TE_RTSP_PORT}"
            - name: TE_RTSP_TCP_TEST_NAME
              value: "${TE_RTSP_TCP_TEST_NAME:-RTSP-TCP-${TE_RTSP_PORT}}"
            - name: TE_RTSP_TCP_INTERVAL
              value: "${TE_RTSP_TCP_INTERVAL:-60}"
            - name: TE_DEMO_MONKEY_FRONTEND_BASE_URL
              value: "${TE_DEMO_MONKEY_FRONTEND_BASE_URL}"
            - name: TE_TRACE_MAP_TEST_NAME
              value: "${TE_TRACE_MAP_TEST_NAME:-aleccham-broadcast-trace-map}"
            - name: TE_TRACE_MAP_TEST_URL
              value: "${TE_TRACE_MAP_TEST_URL}"
            - name: TE_TRACE_MAP_INTERVAL
              value: "${TE_TRACE_MAP_INTERVAL:-60}"
            - name: TE_TRACE_MAP_NETWORK_MEASUREMENTS
              value: "${TE_TRACE_MAP_NETWORK_MEASUREMENTS:-true}"
            - name: TE_BROADCAST_TEST_NAME
              value: "${TE_BROADCAST_TEST_NAME:-aleccham-broadcast-playback}"
            - name: TE_BROADCAST_TEST_URL
              value: "${TE_BROADCAST_TEST_URL}"
            - name: TE_BROADCAST_INTERVAL
              value: "${TE_BROADCAST_INTERVAL:-60}"
            - name: TE_BROADCAST_NETWORK_MEASUREMENTS
              value: "${TE_BROADCAST_NETWORK_MEASUREMENTS:-true}"
            - name: TE_UDP_MEDIA_TEST_NAME
              value: "${TE_UDP_MEDIA_TEST_NAME:-UDP-Media-Path}"
            - name: TE_UDP_MEDIA_INTERVAL
              value: "${TE_UDP_MEDIA_INTERVAL:-60}"
            - name: TE_A2A_PORT
              value: "${TE_A2A_PORT:-5004}"
            - name: TE_A2A_THROUGHPUT_MEASUREMENTS
              value: "${TE_A2A_THROUGHPUT_MEASUREMENTS:-true}"
            - name: TE_A2A_THROUGHPUT_RATE_MBPS
              value: "${TE_A2A_THROUGHPUT_RATE_MBPS:-10}"
            - name: TE_A2A_THROUGHPUT_DURATION_MS
              value: "${TE_A2A_THROUGHPUT_DURATION_MS:-10000}"
            - name: TE_RTP_STREAM_TEST_NAME
              value: "${TE_RTP_STREAM_TEST_NAME:-RTP-Stream-Proxy}"
            - name: TE_RTP_STREAM_INTERVAL
              value: "${TE_RTP_STREAM_INTERVAL:-60}"
            - name: TE_VOICE_PORT
              value: "${TE_VOICE_PORT:-49152}"
            - name: TE_VOICE_CODEC_ID
              value: "${TE_VOICE_CODEC_ID:-0}"
            - name: TE_VOICE_DURATION_SEC
              value: "${TE_VOICE_DURATION_SEC:-10}"
            - name: THOUSANDEYES_BEARER_TOKEN
              valueFrom:
                secretKeyRef:
                  name: ${THOUSANDEYES_SECRET_NAME}
                  key: THOUSANDEYES_BEARER_TOKEN
          volumeMounts:
            - name: thousandeyes-script
              mountPath: /opt/thousandeyes
              readOnly: true
      volumes:
        - name: thousandeyes-script
          configMap:
            name: ${THOUSANDEYES_SCRIPT_CONFIGMAP_NAME}
            defaultMode: 0555
EOF

echo "ThousandEyes RTSP test job prepared in namespace ${NAMESPACE}."
echo "RTSP target: ${TE_RTSP_SERVER}:${TE_RTSP_PORT}"
echo "Demo Monkey trace map target: ${TE_TRACE_MAP_TEST_URL}"
echo "Demo Monkey broadcast target: ${TE_BROADCAST_TEST_URL}"

wait_and_stream_logs
