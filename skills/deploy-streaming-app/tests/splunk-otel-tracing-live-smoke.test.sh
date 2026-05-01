#!/usr/bin/env bash

set -euo pipefail

OTEL_NAMESPACE="${OTEL_NAMESPACE:-otel-splunk}"
APP_NAMESPACE="${APP_NAMESPACE:-streaming-service-app}"
AGENT_DAEMONSET="${AGENT_DAEMONSET:-splunk-otel-collector-agent}"
AGENT_SERVICE="${AGENT_SERVICE:-splunk-otel-collector-agent}"
CLUSTER_RECEIVER_LABEL="${CLUSTER_RECEIVER_LABEL:-component=otel-k8s-cluster-receiver}"
INSTRUMENTATION_NAME="${INSTRUMENTATION_NAME:-splunk-otel-collector}"
FRONTEND_DEPLOYMENT="${FRONTEND_DEPLOYMENT:-streaming-frontend}"
TRACE_URL="${TRACE_URL:-http://streaming-frontend.${APP_NAMESPACE}.svc.cluster.local/api/v1/demo/public/trace-map}"
REQUEST_IMAGE="${REQUEST_IMAGE:-curlimages/curl}"
REQUEST_COUNT="${REQUEST_COUNT:-6}"
REQUEST_INTERVAL_SECONDS="${REQUEST_INTERVAL_SECONDS:-1}"
REQUEST_POD_READY_TIMEOUT="${REQUEST_POD_READY_TIMEOUT:-120s}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-180s}"
ACTIVITY_TIMEOUT_SECONDS="${ACTIVITY_TIMEOUT_SECONDS:-90}"
METRICS_PORT_BASE="${METRICS_PORT_BASE:-29000}"
REQUIRE_SECONDARY_EXPORT="${REQUIRE_SECONDARY_EXPORT:-auto}"
EXPORTER_ERROR_REGEX="${EXPORTER_ERROR_REGEX:-no such host|unsupported protocol scheme|RBAC: access denied|x509:|error exporting items}"
KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
CURL_BIN="${CURL_BIN:-curl}"

TMP_DIR="$(mktemp -d)"
REQUEST_POD="splunk-otel-trace-smoke-$$"
PORT_FORWARD_PID=""
PORT_FORWARD_LOG=""
PORT_FORWARD_CURSOR=0
SECONDARY_ENABLED="0"
FRONTEND_DEPLOYMENT_ENVIRONMENT="unknown"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '[splunk-otel-tracing-live-smoke] %s\n' "$*"
}

cleanup_port_forward() {
  if [[ -n "${PORT_FORWARD_PID}" ]] && kill -0 "${PORT_FORWARD_PID}" 2>/dev/null; then
    kill "${PORT_FORWARD_PID}" 2>/dev/null || true
    wait "${PORT_FORWARD_PID}" 2>/dev/null || true
  fi

  PORT_FORWARD_PID=""
  PORT_FORWARD_LOG=""
}

cleanup() {
  cleanup_port_forward
  "${KUBECTL_BIN}" -n "${APP_NAMESPACE}" delete pod "${REQUEST_POD}" --ignore-not-found >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}

trap cleanup EXIT

assert_text_contains() {
  local text="$1"
  local needle="$2"

  [[ "${text}" == *"${needle}"* ]] || fail "expected text to contain: ${needle}"
}

numeric_gt_zero() {
  local value="$1"

  awk -v value="${value}" 'BEGIN { exit (value + 0 > 0 ? 0 : 1) }'
}

numeric_zero() {
  local value="$1"

  awk -v value="${value}" 'BEGIN { exit (value + 0 == 0 ? 0 : 1) }'
}

extract_metric_total() {
  local metrics_dir="$1"
  local metric_name="$2"
  local selector="$3"
  local default_zero="${4:-0}"
  local output

  output="$(
    awk -v metric_name="${metric_name}" -v selector="${selector}" -v default_zero="${default_zero}" '
      index($1, metric_name "{") == 1 && index($0, selector) {
        sum += $2
        found = 1
      }

      END {
        if (found) {
          printf "%.10g", sum
          exit 0
        }

        if (default_zero == "1") {
          printf "0"
          exit 0
        }

        exit 1
      }
    ' "${metrics_dir}"/*.prom 2>/dev/null
  )" || fail "missing ${metric_name} with selector ${selector}"

  printf '%s\n' "${output}"
}

metric_delta() {
  local baseline_value="$1"
  local current_value="$2"

  awk -v baseline_value="${baseline_value}" -v current_value="${current_value}" '
    BEGIN {
      printf "%.10g", current_value - baseline_value
    }
  '
}

list_agent_pods() {
  "${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get pods -l component=otel-collector-agent \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
}

list_cluster_receiver_pods() {
  "${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get pods -l "${CLUSTER_RECEIVER_LABEL}" \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
}

next_local_port() {
  local port

  port=$((METRICS_PORT_BASE + PORT_FORWARD_CURSOR))
  PORT_FORWARD_CURSOR=$((PORT_FORWARD_CURSOR + 1))
  printf '%s\n' "${port}"
}

capture_metrics_for_pod() {
  local pod_name="$1"
  local output_file="$2"
  local last_error=""
  local local_port

  for _ in $(seq 1 12); do
    local_port="$(next_local_port)"
    PORT_FORWARD_LOG="${TMP_DIR}/${pod_name}.${local_port}.port-forward.log"

    "${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" port-forward "pod/${pod_name}" "${local_port}:8889" >"${PORT_FORWARD_LOG}" 2>&1 &
    PORT_FORWARD_PID=$!

    for _ in $(seq 1 20); do
      if "${CURL_BIN}" -sf "http://127.0.0.1:${local_port}/metrics" >"${output_file}"; then
        cleanup_port_forward
        return 0
      fi

      if ! kill -0 "${PORT_FORWARD_PID}" 2>/dev/null; then
        break
      fi

      sleep 1
    done

    if [[ -f "${PORT_FORWARD_LOG}" ]]; then
      last_error="$(cat "${PORT_FORWARD_LOG}")"
    fi

    cleanup_port_forward
  done

  fail "unable to read collector metrics for ${pod_name}: ${last_error:-port-forward did not become ready}"
}

capture_agent_metrics_snapshot() {
  local snapshot_name="$1"
  local snapshot_dir="${TMP_DIR}/${snapshot_name}"
  local pod_name

  mkdir -p "${snapshot_dir}"

  while IFS= read -r pod_name; do
    [[ -n "${pod_name}" ]] || continue
    capture_metrics_for_pod "${pod_name}" "${snapshot_dir}/${pod_name}.prom"
  done < <(list_agent_pods)

  printf '%s\n' "${snapshot_dir}"
}

detect_secondary_export_enabled() {
  local secondary_env

  case "${REQUIRE_SECONDARY_EXPORT}" in
    true)
      SECONDARY_ENABLED="1"
      return
      ;;
    false)
      SECONDARY_ENABLED="0"
      return
      ;;
    auto) ;;
    *)
      fail "REQUIRE_SECONDARY_EXPORT must be one of: auto, true, false"
      ;;
  esac

  secondary_env="$("${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get daemonset "${AGENT_DAEMONSET}" \
    -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}')"

  if printf '%s\n' "${secondary_env}" | grep -Eq '^SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN=.+$'; then
    SECONDARY_ENABLED="1"
  else
    SECONDARY_ENABLED="0"
  fi
}

prepare_request_pod() {
  log "Creating short-lived curl pod ${REQUEST_POD} in namespace ${APP_NAMESPACE}"
  "${KUBECTL_BIN}" -n "${APP_NAMESPACE}" delete pod "${REQUEST_POD}" --ignore-not-found >/dev/null 2>&1 || true
  "${KUBECTL_BIN}" -n "${APP_NAMESPACE}" run "${REQUEST_POD}" \
    --image="${REQUEST_IMAGE}" \
    --restart=Never \
    --command -- sh -c 'sleep 600' >/dev/null
  "${KUBECTL_BIN}" -n "${APP_NAMESPACE}" wait --for=condition=Ready "pod/${REQUEST_POD}" --timeout="${REQUEST_POD_READY_TIMEOUT}" >/dev/null
}

generate_trace_requests() {
  log "Generating ${REQUEST_COUNT} trace-map requests against ${TRACE_URL}"
  "${KUBECTL_BIN}" -n "${APP_NAMESPACE}" exec "${REQUEST_POD}" -- sh -c \
    "i=0; while [ \$i -lt ${REQUEST_COUNT} ]; do curl -fsS '${TRACE_URL}' >/dev/null; i=\$((i+1)); sleep ${REQUEST_INTERVAL_SECONDS}; done"
}

collector_preflight() {
  local service_policy instrumentation_yaml agent_yaml

  log "Waiting for ${AGENT_DAEMONSET} and ${FRONTEND_DEPLOYMENT} rollouts"
  "${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" rollout status "daemonset/${AGENT_DAEMONSET}" --timeout="${ROLLOUT_TIMEOUT}" >/dev/null
  "${KUBECTL_BIN}" -n "${APP_NAMESPACE}" rollout status "deployment/${FRONTEND_DEPLOYMENT}" --timeout="${ROLLOUT_TIMEOUT}" >/dev/null

  service_policy="$("${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get service "${AGENT_SERVICE}" -o jsonpath='{.spec.internalTrafficPolicy}')"
  [[ "${service_policy}" == "Cluster" ]] || fail "expected ${AGENT_SERVICE} internalTrafficPolicy=Cluster, got ${service_policy:-unset}"

  instrumentation_yaml="$("${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get instrumentations.opentelemetry.io "${INSTRUMENTATION_NAME}" -o yaml)"
  assert_text_contains "${instrumentation_yaml}" "endpoint: http://${AGENT_SERVICE}.${OTEL_NAMESPACE}.svc.cluster.local:4317"
  assert_text_contains "${instrumentation_yaml}" "- baggage"
  assert_text_contains "${instrumentation_yaml}" "- b3"
  assert_text_contains "${instrumentation_yaml}" "- tracecontext"

  agent_yaml="$("${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get daemonset "${AGENT_DAEMONSET}" -o yaml)"
  assert_text_contains "${agent_yaml}" "eks.amazonaws.com/nodegroup: private"
  assert_text_contains "${agent_yaml}" "value: otel"
}

capture_frontend_environment() {
  local resource_attributes

  resource_attributes="$("${KUBECTL_BIN}" -n "${APP_NAMESPACE}" exec "deployment/${FRONTEND_DEPLOYMENT}" -- \
    sh -c 'printenv OTEL_RESOURCE_ATTRIBUTES 2>/dev/null || true' 2>/dev/null || true)"

  if [[ -n "${resource_attributes}" ]]; then
    FRONTEND_DEPLOYMENT_ENVIRONMENT="$(printf '%s\n' "${resource_attributes}" | sed -n 's/.*deployment.environment=\([^,]*\).*/\1/p')"
  fi

  : "${FRONTEND_DEPLOYMENT_ENVIRONMENT:=unknown}"
}

assert_no_recent_exporter_errors() {
  local since_time="$1"
  local pod_name
  local pod_errors
  local all_errors=""

  while IFS= read -r pod_name; do
    [[ -n "${pod_name}" ]] || continue
    pod_errors="$("${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" logs "pod/${pod_name}" --since-time="${since_time}" 2>/dev/null | rg -n "${EXPORTER_ERROR_REGEX}" || true)"
    if [[ -n "${pod_errors}" ]]; then
      all_errors+=$'\n'"== ${pod_name} ==\n${pod_errors}"
    fi
  done < <({ list_agent_pods; list_cluster_receiver_pods; } | sort -u)

  [[ -z "${all_errors}" ]] || fail "observed recent collector exporter errors:${all_errors}"
}

detect_secondary_export_enabled
collector_preflight
capture_frontend_environment
prepare_request_pod

baseline_metrics_dir="$(capture_agent_metrics_snapshot baseline)"
baseline_accepted_spans="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_receiver_accepted_spans" 'receiver="otlp",transport="grpc"')"
baseline_refused_spans="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_receiver_refused_spans" 'receiver="otlp",transport="grpc"' 1)"
baseline_sent_otlp_http="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="otlp_http"')"
baseline_sent_signalfx="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="signalfx"')"
baseline_failed_otlp_http="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="otlp_http"' 1)"
baseline_failed_signalfx="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="signalfx"' 1)"

baseline_sent_otlp_http_secondary="0"
baseline_sent_signalfx_secondary="0"
baseline_failed_otlp_http_secondary="0"
baseline_failed_signalfx_secondary="0"

if [[ "${SECONDARY_ENABLED}" == "1" ]]; then
  baseline_sent_otlp_http_secondary="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="otlp_http/secondary"')"
  baseline_sent_signalfx_secondary="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="signalfx/secondary"')"
  baseline_failed_otlp_http_secondary="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="otlp_http/secondary"' 1)"
  baseline_failed_signalfx_secondary="$(extract_metric_total "${baseline_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="signalfx/secondary"' 1)"
fi

smoke_start_time="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
generate_trace_requests

accepted_spans_delta=""
refused_spans_delta=""
sent_otlp_http_delta=""
sent_signalfx_delta=""
failed_otlp_http_delta=""
failed_signalfx_delta=""
sent_otlp_http_secondary_delta="0"
sent_signalfx_secondary_delta="0"
failed_otlp_http_secondary_delta="0"
failed_signalfx_secondary_delta="0"
fresh_activity=0
deadline=$((SECONDS + ACTIVITY_TIMEOUT_SECONDS))

while (( SECONDS < deadline )); do
  sleep 5

  current_metrics_dir="$(capture_agent_metrics_snapshot "current-${SECONDS}")"
  current_accepted_spans="$(extract_metric_total "${current_metrics_dir}" "otelcol_receiver_accepted_spans" 'receiver="otlp",transport="grpc"')"
  current_refused_spans="$(extract_metric_total "${current_metrics_dir}" "otelcol_receiver_refused_spans" 'receiver="otlp",transport="grpc"' 1)"
  current_sent_otlp_http="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="otlp_http"')"
  current_sent_signalfx="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="signalfx"')"
  current_failed_otlp_http="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="otlp_http"' 1)"
  current_failed_signalfx="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="signalfx"' 1)"

  accepted_spans_delta="$(metric_delta "${baseline_accepted_spans}" "${current_accepted_spans}")"
  refused_spans_delta="$(metric_delta "${baseline_refused_spans}" "${current_refused_spans}")"
  sent_otlp_http_delta="$(metric_delta "${baseline_sent_otlp_http}" "${current_sent_otlp_http}")"
  sent_signalfx_delta="$(metric_delta "${baseline_sent_signalfx}" "${current_sent_signalfx}")"
  failed_otlp_http_delta="$(metric_delta "${baseline_failed_otlp_http}" "${current_failed_otlp_http}")"
  failed_signalfx_delta="$(metric_delta "${baseline_failed_signalfx}" "${current_failed_signalfx}")"

  if [[ "${SECONDARY_ENABLED}" == "1" ]]; then
    current_sent_otlp_http_secondary="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="otlp_http/secondary"')"
    current_sent_signalfx_secondary="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_sent_spans" 'exporter="signalfx/secondary"')"
    current_failed_otlp_http_secondary="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="otlp_http/secondary"' 1)"
    current_failed_signalfx_secondary="$(extract_metric_total "${current_metrics_dir}" "otelcol_exporter_send_failed_spans" 'exporter="signalfx/secondary"' 1)"

    sent_otlp_http_secondary_delta="$(metric_delta "${baseline_sent_otlp_http_secondary}" "${current_sent_otlp_http_secondary}")"
    sent_signalfx_secondary_delta="$(metric_delta "${baseline_sent_signalfx_secondary}" "${current_sent_signalfx_secondary}")"
    failed_otlp_http_secondary_delta="$(metric_delta "${baseline_failed_otlp_http_secondary}" "${current_failed_otlp_http_secondary}")"
    failed_signalfx_secondary_delta="$(metric_delta "${baseline_failed_signalfx_secondary}" "${current_failed_signalfx_secondary}")"
  fi

  if numeric_gt_zero "${accepted_spans_delta}" &&
    numeric_gt_zero "${sent_otlp_http_delta}" &&
    numeric_gt_zero "${sent_signalfx_delta}" &&
    numeric_zero "${refused_spans_delta}" &&
    numeric_zero "${failed_otlp_http_delta}" &&
    numeric_zero "${failed_signalfx_delta}"; then
    if [[ "${SECONDARY_ENABLED}" == "1" ]]; then
      if numeric_gt_zero "${sent_otlp_http_secondary_delta}" &&
        numeric_gt_zero "${sent_signalfx_secondary_delta}" &&
        numeric_zero "${failed_otlp_http_secondary_delta}" &&
        numeric_zero "${failed_signalfx_secondary_delta}"; then
        fresh_activity=1
        break
      fi
    else
      fresh_activity=1
      break
    fi
  fi
done

[[ "${fresh_activity}" == "1" ]] || fail "did not observe fresh trace export activity. accepted_spans_delta=${accepted_spans_delta:-missing} sent_otlp_http_delta=${sent_otlp_http_delta:-missing} sent_signalfx_delta=${sent_signalfx_delta:-missing} sent_otlp_http_secondary_delta=${sent_otlp_http_secondary_delta:-missing} sent_signalfx_secondary_delta=${sent_signalfx_secondary_delta:-missing} refused_spans_delta=${refused_spans_delta:-missing} failed_otlp_http_delta=${failed_otlp_http_delta:-missing} failed_signalfx_delta=${failed_signalfx_delta:-missing} failed_otlp_http_secondary_delta=${failed_otlp_http_secondary_delta:-missing} failed_signalfx_secondary_delta=${failed_signalfx_secondary_delta:-missing}"

assert_no_recent_exporter_errors "${smoke_start_time}"

log "Frontend deployment.environment=${FRONTEND_DEPLOYMENT_ENVIRONMENT}"

if [[ "${SECONDARY_ENABLED}" == "1" ]]; then
  printf 'PASS: Splunk OTel live trace export is active. environment=%s accepted_spans_delta=%s sent_otlp_http_delta=%s sent_signalfx_delta=%s sent_otlp_http_secondary_delta=%s sent_signalfx_secondary_delta=%s\n' \
    "${FRONTEND_DEPLOYMENT_ENVIRONMENT}" \
    "${accepted_spans_delta}" \
    "${sent_otlp_http_delta}" \
    "${sent_signalfx_delta}" \
    "${sent_otlp_http_secondary_delta}" \
    "${sent_signalfx_secondary_delta}"
else
  printf 'PASS: Splunk OTel live trace export is active. environment=%s accepted_spans_delta=%s sent_otlp_http_delta=%s sent_signalfx_delta=%s\n' \
    "${FRONTEND_DEPLOYMENT_ENVIRONMENT}" \
    "${accepted_spans_delta}" \
    "${sent_otlp_http_delta}" \
    "${sent_signalfx_delta}"
fi
