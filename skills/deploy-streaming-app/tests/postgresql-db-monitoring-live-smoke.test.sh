#!/usr/bin/env bash

set -euo pipefail

OTEL_NAMESPACE="${OTEL_NAMESPACE:-otel-splunk}"
APP_NAMESPACE="${APP_NAMESPACE:-streaming-service-app}"
COLLECTOR_DEPLOYMENT="${COLLECTOR_DEPLOYMENT:-splunk-otel-collector-k8s-cluster-receiver}"
COLLECTOR_CONFIGMAP="${COLLECTOR_CONFIGMAP:-splunk-otel-collector-otel-k8s-cluster-receiver}"
POSTGRES_DEPLOYMENT="${POSTGRES_DEPLOYMENT:-streaming-postgres}"
POSTGRES_NETWORK_POLICY="${POSTGRES_NETWORK_POLICY:-streaming-postgres-ingress}"
POSTGRES_USER="${POSTGRES_USER:-streaming}"
POSTGRES_DB="${POSTGRES_DB:-streaming}"
POSTGRES_ADMIN_DB="${POSTGRES_ADMIN_DB:-postgres}"
POSTGRES_ENDPOINT="${POSTGRES_ENDPOINT:-streaming-postgres.${APP_NAMESPACE}.svc.cluster.local:5432}"
OTEL_NAMESPACE_LABEL="${OTEL_NAMESPACE_LABEL:-${OTEL_NAMESPACE}}"
DBMON_METRICS_PORT="${DBMON_METRICS_PORT:-18899}"
QUERY_SLEEP_SECONDS="${QUERY_SLEEP_SECONDS:-20}"
ACTIVITY_TIMEOUT_SECONDS="${ACTIVITY_TIMEOUT_SECONDS:-45}"
KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
CURL_BIN="${CURL_BIN:-curl}"

BASELINE_METRICS_FILE="$(mktemp)"
CURRENT_METRICS_FILE="$(mktemp)"
PORT_FORWARD_LOG="$(mktemp)"
PORT_FORWARD_PID=""
QUERY_PID=""

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]] && kill -0 "${PORT_FORWARD_PID}" 2>/dev/null; then
    kill "${PORT_FORWARD_PID}" 2>/dev/null || true
    wait "${PORT_FORWARD_PID}" 2>/dev/null || true
  fi

  if [[ -n "${QUERY_PID}" ]] && kill -0 "${QUERY_PID}" 2>/dev/null; then
    kill "${QUERY_PID}" 2>/dev/null || true
    wait "${QUERY_PID}" 2>/dev/null || true
  fi

  rm -f "${BASELINE_METRICS_FILE}" "${CURRENT_METRICS_FILE}" "${PORT_FORWARD_LOG}"
}

trap cleanup EXIT

assert_text_contains() {
  local text="$1"
  local needle="$2"

  [[ "${text}" == *"${needle}"* ]] || fail "expected text to contain: ${needle}"
}

assert_text_contains_any() {
  local text="$1"
  local first_needle="$2"
  local second_needle="$3"

  if [[ "${text}" != *"${first_needle}"* && "${text}" != *"${second_needle}"* ]]; then
    fail "expected text to contain one of: ${first_needle} | ${second_needle}"
  fi
}

assert_gt_zero() {
  local value="$1"
  local label="$2"

  numeric_gt_zero "${value}" || fail "expected ${label} to be greater than zero, got ${value}"
}

assert_zero() {
  local value="$1"
  local label="$2"

  numeric_zero "${value}" || fail "expected ${label} to be zero, got ${value}"
}

numeric_gt_zero() {
  local value="$1"

  awk -v value="${value}" 'BEGIN { exit (value + 0 > 0 ? 0 : 1) }'
}

numeric_zero() {
  local value="$1"

  awk -v value="${value}" 'BEGIN { exit (value + 0 == 0 ? 0 : 1) }'
}

extract_metric_from() {
  local metrics_file="$1"
  local metric_name="$2"
  local selector="$3"

  awk -v metric_name="${metric_name}" -v selector="${selector}" '
    index($1, metric_name "{") == 1 && index($0, selector) {
      print $2
      exit
    }
  ' "${metrics_file}"
}

collect_metrics_snapshot() {
  local output_file="$1"

  "${CURL_BIN}" -sf "http://127.0.0.1:${DBMON_METRICS_PORT}/metrics" >"${output_file}"
}

extract_metric_delta() {
  local metric_name="$1"
  local selector="$2"
  local baseline_value="$3"
  local current_value="$4"

  [[ -n "${baseline_value}" ]] || fail "missing baseline ${metric_name} value for selector ${selector}"
  [[ -n "${current_value}" ]] || fail "missing current ${metric_name} value for selector ${selector}"

  awk -v baseline_value="${baseline_value}" -v current_value="${current_value}" '
    BEGIN {
      printf "%.10g", current_value - baseline_value
    }
  '
}

collector_yaml="$("${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get configmap "${COLLECTOR_CONFIGMAP}" -o yaml)"
assert_text_contains "${collector_yaml}" "postgresql:"
assert_text_contains "${collector_yaml}" "otlphttp/dbmon:"
assert_text_contains "${collector_yaml}" "metrics/dbmon:"
assert_text_contains "${collector_yaml}" "logs/dbmon:"
assert_text_contains_any "${collector_yaml}" "${POSTGRES_ENDPOINT}" '${env:SPLUNK_DBMON_POSTGRES_ENDPOINT}'
assert_text_contains "${collector_yaml}" "db.server.query_sample:"
assert_text_contains "${collector_yaml}" "db.server.top_query:"

collector_deployment_yaml="$("${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" get deployment "${COLLECTOR_DEPLOYMENT}" -o yaml)"
assert_text_contains "${collector_deployment_yaml}" "name: SPLUNK_DBMON_POSTGRES_ENDPOINT"
assert_text_contains "${collector_deployment_yaml}" "value: ${POSTGRES_ENDPOINT}"

policy_yaml="$("${KUBECTL_BIN}" -n "${APP_NAMESPACE}" get networkpolicy "${POSTGRES_NETWORK_POLICY}" -o yaml)"
assert_text_contains "${policy_yaml}" "kubernetes.io/metadata.name: ${OTEL_NAMESPACE_LABEL}"

shared_preload_libraries="$("${KUBECTL_BIN}" -n "${APP_NAMESPACE}" exec "deploy/${POSTGRES_DEPLOYMENT}" -- \
  psql -U "${POSTGRES_USER}" -d "${POSTGRES_ADMIN_DB}" -Atqc "SHOW shared_preload_libraries;")"
assert_text_contains "${shared_preload_libraries}" "pg_stat_statements"

for database_name in "${POSTGRES_DB}" "${POSTGRES_ADMIN_DB}"; do
  extension_name="$("${KUBECTL_BIN}" -n "${APP_NAMESPACE}" exec "deploy/${POSTGRES_DEPLOYMENT}" -- \
    psql -U "${POSTGRES_USER}" -d "${database_name}" -Atqc "SELECT extname FROM pg_extension WHERE extname = 'pg_stat_statements';")"
  [[ "${extension_name}" == "pg_stat_statements" ]] || fail "expected pg_stat_statements extension in database ${database_name}"

  "${KUBECTL_BIN}" -n "${APP_NAMESPACE}" exec "deploy/${POSTGRES_DEPLOYMENT}" -- \
    psql -U "${POSTGRES_USER}" -d "${database_name}" -Atqc "SELECT count(*) FROM pg_stat_statements;" >/dev/null
done

"${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" rollout status "deployment/${COLLECTOR_DEPLOYMENT}" --timeout=3m >/dev/null
"${KUBECTL_BIN}" -n "${OTEL_NAMESPACE}" port-forward "deployment/${COLLECTOR_DEPLOYMENT}" "${DBMON_METRICS_PORT}:8899" >"${PORT_FORWARD_LOG}" 2>&1 &
PORT_FORWARD_PID=$!

metrics_ready=0
for _ in $(seq 1 30); do
  if collect_metrics_snapshot "${BASELINE_METRICS_FILE}"; then
    metrics_ready=1
    break
  fi

  if ! kill -0 "${PORT_FORWARD_PID}" 2>/dev/null; then
    break
  fi

  sleep 1
done

[[ "${metrics_ready}" == "1" ]] || fail "unable to read collector metrics via port-forward: $(cat "${PORT_FORWARD_LOG}")"

baseline_accepted_metric_points="$(extract_metric_from "${BASELINE_METRICS_FILE}" "otelcol_receiver_accepted_metric_points" 'receiver="postgresql"')"
baseline_accepted_log_records="$(extract_metric_from "${BASELINE_METRICS_FILE}" "otelcol_receiver_accepted_log_records" 'receiver="postgresql"')"
baseline_failed_metric_points="$(extract_metric_from "${BASELINE_METRICS_FILE}" "otelcol_receiver_failed_metric_points" 'receiver="postgresql"')"
baseline_failed_log_records="$(extract_metric_from "${BASELINE_METRICS_FILE}" "otelcol_receiver_failed_log_records" 'receiver="postgresql"')"
baseline_dbmon_log_records="$(extract_metric_from "${BASELINE_METRICS_FILE}" "otelcol_exporter_sent_log_records" 'exporter="otlphttp/dbmon"')"
baseline_scraper_errored_metric_points="$(extract_metric_from "${BASELINE_METRICS_FILE}" "otelcol_scraper_errored_metric_points" 'receiver="postgresql"')"

[[ -n "${baseline_accepted_metric_points}" ]] || fail "missing baseline accepted metric points for the postgresql receiver"
[[ -n "${baseline_accepted_log_records}" ]] || fail "missing baseline accepted log records for the postgresql receiver"
[[ -n "${baseline_failed_metric_points}" ]] || fail "missing baseline failed metric points for the postgresql receiver"
[[ -n "${baseline_failed_log_records}" ]] || fail "missing baseline failed log records for the postgresql receiver"
[[ -n "${baseline_dbmon_log_records}" ]] || fail "missing baseline sent log records for the otlphttp/dbmon exporter"

: "${baseline_scraper_errored_metric_points:=0}"

"${KUBECTL_BIN}" -n "${APP_NAMESPACE}" exec "deploy/${POSTGRES_DEPLOYMENT}" -- \
  psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atqc "SELECT pg_sleep(${QUERY_SLEEP_SECONDS});" >/dev/null 2>&1 &
QUERY_PID=$!

accepted_metric_points_delta=""
accepted_log_records_delta=""
failed_metric_points_delta=""
failed_log_records_delta=""
dbmon_log_records_delta=""
scraper_errored_metric_points_delta="0"
fresh_activity=0
deadline=$((SECONDS + ACTIVITY_TIMEOUT_SECONDS))

while (( SECONDS < deadline )); do
  sleep 5
  collect_metrics_snapshot "${CURRENT_METRICS_FILE}"

  current_accepted_metric_points="$(extract_metric_from "${CURRENT_METRICS_FILE}" "otelcol_receiver_accepted_metric_points" 'receiver="postgresql"')"
  current_accepted_log_records="$(extract_metric_from "${CURRENT_METRICS_FILE}" "otelcol_receiver_accepted_log_records" 'receiver="postgresql"')"
  current_failed_metric_points="$(extract_metric_from "${CURRENT_METRICS_FILE}" "otelcol_receiver_failed_metric_points" 'receiver="postgresql"')"
  current_failed_log_records="$(extract_metric_from "${CURRENT_METRICS_FILE}" "otelcol_receiver_failed_log_records" 'receiver="postgresql"')"
  current_dbmon_log_records="$(extract_metric_from "${CURRENT_METRICS_FILE}" "otelcol_exporter_sent_log_records" 'exporter="otlphttp/dbmon"')"
  current_scraper_errored_metric_points="$(extract_metric_from "${CURRENT_METRICS_FILE}" "otelcol_scraper_errored_metric_points" 'receiver="postgresql"')"

  : "${current_scraper_errored_metric_points:=0}"

  accepted_metric_points_delta="$(extract_metric_delta "otelcol_receiver_accepted_metric_points" 'receiver="postgresql"' "${baseline_accepted_metric_points}" "${current_accepted_metric_points}")"
  accepted_log_records_delta="$(extract_metric_delta "otelcol_receiver_accepted_log_records" 'receiver="postgresql"' "${baseline_accepted_log_records}" "${current_accepted_log_records}")"
  failed_metric_points_delta="$(extract_metric_delta "otelcol_receiver_failed_metric_points" 'receiver="postgresql"' "${baseline_failed_metric_points}" "${current_failed_metric_points}")"
  failed_log_records_delta="$(extract_metric_delta "otelcol_receiver_failed_log_records" 'receiver="postgresql"' "${baseline_failed_log_records}" "${current_failed_log_records}")"
  dbmon_log_records_delta="$(extract_metric_delta "otelcol_exporter_sent_log_records" 'exporter="otlphttp/dbmon"' "${baseline_dbmon_log_records}" "${current_dbmon_log_records}")"
  scraper_errored_metric_points_delta="$(extract_metric_delta "otelcol_scraper_errored_metric_points" 'receiver="postgresql"' "${baseline_scraper_errored_metric_points}" "${current_scraper_errored_metric_points}")"

  if numeric_gt_zero "${accepted_metric_points_delta}" &&
    numeric_gt_zero "${accepted_log_records_delta}" &&
    numeric_gt_zero "${dbmon_log_records_delta}" &&
    numeric_zero "${failed_metric_points_delta}" &&
    numeric_zero "${failed_log_records_delta}" &&
    numeric_zero "${scraper_errored_metric_points_delta}"; then
    fresh_activity=1
    break
  fi
done

wait "${QUERY_PID}" >/dev/null 2>&1 || fail "failed to generate a long-running PostgreSQL query for DBMON validation"
QUERY_PID=""

[[ "${fresh_activity}" == "1" ]] || fail "did not observe fresh DBMON activity without new errors. accepted_metric_points_delta=${accepted_metric_points_delta:-missing} accepted_log_records_delta=${accepted_log_records_delta:-missing} dbmon_log_records_delta=${dbmon_log_records_delta:-missing} failed_metric_points_delta=${failed_metric_points_delta:-missing} failed_log_records_delta=${failed_log_records_delta:-missing} scraper_errored_metric_points_delta=${scraper_errored_metric_points_delta:-missing}"

assert_gt_zero "${accepted_metric_points_delta}" "postgresql accepted metric points delta"
assert_gt_zero "${accepted_log_records_delta}" "postgresql accepted log records delta"
assert_gt_zero "${dbmon_log_records_delta}" "dbmon exported log records delta"
assert_zero "${failed_metric_points_delta}" "postgresql failed metric points delta"
assert_zero "${failed_log_records_delta}" "postgresql failed log records delta"
assert_zero "${scraper_errored_metric_points_delta}" "postgresql scraper errored metric points delta"

printf 'PASS: Live PostgreSQL DB monitoring is active. accepted_metric_points_delta=%s accepted_log_records_delta=%s dbmon_log_records_delta=%s\n' \
  "${accepted_metric_points_delta}" \
  "${accepted_log_records_delta}" \
  "${dbmon_log_records_delta}"
