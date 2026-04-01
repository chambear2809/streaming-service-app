#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
VALUES_FILE="${REPO_ROOT}/k8s/otel-splunk/postgresql-dbmon.values.yaml"
DOC_FILE="${REPO_ROOT}/docs/04-postgresql-db-monitoring.md"
SKILL_FILE="${REPO_ROOT}/skills/deploy-streaming-app/SKILL.md"
CURSOR_SKILL_FILE="${REPO_ROOT}/.cursor/skills/deploy-streaming-app/SKILL.md"
README_FILE="${REPO_ROOT}/README.md"
ENV_FILE="${REPO_ROOT}/example.env"
POSTGRES_MANIFEST="${REPO_ROOT}/k8s/backend-demo/postgres.yaml"
LIVE_SMOKE_TEST="${REPO_ROOT}/skills/deploy-streaming-app/tests/postgresql-db-monitoring-live-smoke.test.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_file_exists() {
  local path="$1"

  [[ -f "${path}" ]] || fail "expected file to exist: ${path}"
}

assert_file_contains() {
  local path="$1"
  local needle="$2"

  grep -Fq -- "${needle}" "${path}" || fail "expected ${path} to contain: ${needle}"
}

assert_file_not_contains() {
  local path="$1"
  local needle="$2"

  if grep -Fq -- "${needle}" "${path}"; then
    fail "did not expect ${path} to contain: ${needle}"
  fi
}

extract_pipeline_processors() {
  local path="$1"
  local pipeline_name="$2"

  awk -v pipeline_name="${pipeline_name}" '
    $1 == pipeline_name ":" {
      in_pipeline = 1
      next
    }

    in_pipeline && $1 == "processors:" {
      in_processors = 1
      next
    }

    in_pipeline && in_processors && $1 == "-" {
      print $2
      next
    }

    in_pipeline && in_processors && $1 ~ /:$/ {
      exit
    }
  ' "${path}"
}

assert_file_exists "${VALUES_FILE}"
assert_file_exists "${DOC_FILE}"
assert_file_exists "${SKILL_FILE}"
assert_file_exists "${CURSOR_SKILL_FILE}"
assert_file_exists "${README_FILE}"
assert_file_exists "${ENV_FILE}"
assert_file_exists "${POSTGRES_MANIFEST}"
assert_file_exists "${LIVE_SMOKE_TEST}"

assert_file_contains "${VALUES_FILE}" "clusterReceiver:"
assert_file_not_contains "${VALUES_FILE}" "agent:"
assert_file_contains "${VALUES_FILE}" "postgresql:"
assert_file_contains "${VALUES_FILE}" "streaming-postgres.streaming-service-app.svc.cluster.local:5432"
assert_file_contains "${VALUES_FILE}" "- streaming"
assert_file_contains "${VALUES_FILE}" "otlphttp/dbmon:"
assert_file_contains "${VALUES_FILE}" "X-splunk-instrumentation-library: dbmon"
assert_file_contains "${VALUES_FILE}" "metrics/dbmon:"
assert_file_contains "${VALUES_FILE}" "logs/dbmon:"
assert_file_contains "${VALUES_FILE}" "SPLUNK_DBMON_POSTGRES_USERNAME"
assert_file_contains "${VALUES_FILE}" "SPLUNK_DBMON_POSTGRES_PASSWORD"
assert_file_contains "${VALUES_FILE}" "SPLUNK_DBMON_ACCESS_TOKEN"
assert_file_contains "${VALUES_FILE}" "SPLUNK_DBMON_POSTGRES_ENDPOINT"
assert_file_contains "${VALUES_FILE}" "SPLUNK_DBMON_EVENT_ENDPOINT"

metrics_dbmon_processors="$(extract_pipeline_processors "${VALUES_FILE}" "metrics/dbmon")"
logs_dbmon_processors="$(extract_pipeline_processors "${VALUES_FILE}" "logs/dbmon")"
[[ -n "${metrics_dbmon_processors}" ]] || fail "expected metrics/dbmon processors to be defined"
[[ "${metrics_dbmon_processors}" == "${logs_dbmon_processors}" ]] || fail "expected metrics/dbmon and logs/dbmon processors to match"

assert_file_contains "${DOC_FILE}" "k8s/otel-splunk/postgresql-dbmon.values.yaml"
assert_file_contains "${DOC_FILE}" "streaming"
assert_file_contains "${DOC_FILE}" "clusterReceiver.config"
assert_file_contains "${DOC_FILE}" "PostgreSQL server log forwarding is a separate decision"
assert_file_contains "${DOC_FILE}" "pg_stat_statements"
assert_file_contains "${DOC_FILE}" "postgresql-db-monitoring-live-smoke.test.sh"
assert_file_contains "${DOC_FILE}" "metrics/dbmon"
assert_file_contains "${DOC_FILE}" "logs/dbmon"

assert_file_contains "${SKILL_FILE}" "docs/04-postgresql-db-monitoring.md"
assert_file_contains "${SKILL_FILE}" "whether the user also wants PostgreSQL server logs forwarded"
assert_file_contains "${SKILL_FILE}" "shared_preload_libraries=pg_stat_statements"
assert_file_contains "${SKILL_FILE}" "metrics/dbmon"
assert_file_contains "${SKILL_FILE}" "POSTGRES_ENDPOINT"

assert_file_contains "${CURSOR_SKILL_FILE}" "docs/04-postgresql-db-monitoring.md"
assert_file_contains "${CURSOR_SKILL_FILE}" "whether the user also wants PostgreSQL server logs forwarded"
assert_file_contains "${CURSOR_SKILL_FILE}" "shared_preload_libraries=pg_stat_statements"
assert_file_contains "${CURSOR_SKILL_FILE}" "metrics/dbmon"
assert_file_contains "${CURSOR_SKILL_FILE}" "POSTGRES_ENDPOINT"

assert_file_contains "${README_FILE}" "postgresql-db-monitoring-live-smoke.test.sh"
assert_file_contains "${README_FILE}" "clusterReceiver"
assert_file_contains "${README_FILE}" "PostgreSQL server logs are optional"
assert_file_contains "${README_FILE}" "metrics/dbmon"

assert_file_contains "${ENV_FILE}" "SPLUNK_DBMON_POSTGRES_USERNAME"
assert_file_contains "${ENV_FILE}" "SPLUNK_DBMON_POSTGRES_PASSWORD"
assert_file_contains "${ENV_FILE}" "SPLUNK_DBMON_ACCESS_TOKEN"
assert_file_contains "${ENV_FILE}" "SPLUNK_DB_LOGS_ENABLED=false"

assert_file_contains "${LIVE_SMOKE_TEST}" "POSTGRES_ENDPOINT="
assert_file_contains "${LIVE_SMOKE_TEST}" "ACTIVITY_TIMEOUT_SECONDS="
assert_file_contains "${LIVE_SMOKE_TEST}" "accepted_metric_points_delta"
assert_file_contains "${LIVE_SMOKE_TEST}" "scraper_errored_metric_points_delta"

assert_file_contains "${POSTGRES_MANIFEST}" "shared_preload_libraries=pg_stat_statements"
assert_file_contains "${POSTGRES_MANIFEST}" "10-pg-stat-statements.sh"
assert_file_contains "${POSTGRES_MANIFEST}" "--dbname \"\$POSTGRES_DB\""
assert_file_contains "${POSTGRES_MANIFEST}" "--dbname postgres"
assert_file_contains "${POSTGRES_MANIFEST}" "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"
assert_file_contains "${POSTGRES_MANIFEST}" "kind: NetworkPolicy"
assert_file_contains "${POSTGRES_MANIFEST}" "kubernetes.io/metadata.name: otel-splunk"

printf 'PASS: PostgreSQL DB monitoring config, docs, skills, and env wiring are aligned.\n'
