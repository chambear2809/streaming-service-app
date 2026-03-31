#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
HELPER_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"
CURSOR_HELPER_SCRIPT="${REPO_ROOT}/.cursor/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"
DEPLOY_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/deploy-demo.sh"
VALUES_FILE="${REPO_ROOT}/k8s/otel-splunk/collector.values.yaml"
OPENSHIFT_VALUES_FILE="${REPO_ROOT}/k8s/otel-splunk/collector.openshift.values.yaml"
BOOTSTRAP_DOC="${REPO_ROOT}/docs/splunk-otel-collector-bootstrap.md"
TRACING_DOC="${REPO_ROOT}/docs/distributed-tracing.md"
README_FILE="${REPO_ROOT}/README.md"
ENV_FILE="${REPO_ROOT}/example.env"
SKILL_FILE="${REPO_ROOT}/skills/deploy-streaming-app/SKILL.md"
CURSOR_SKILL_FILE="${REPO_ROOT}/.cursor/skills/deploy-streaming-app/SKILL.md"
AGENT_FILE="${REPO_ROOT}/skills/deploy-streaming-app/agents/openai.yaml"
CURSOR_AGENT_FILE="${REPO_ROOT}/.cursor/skills/deploy-streaming-app/agents/openai.yaml"
K8S_REF="${REPO_ROOT}/skills/deploy-streaming-app/references/kubernetes.md"
OPENSHIFT_REF="${REPO_ROOT}/skills/deploy-streaming-app/references/openshift.md"

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

assert_file_exists "${HELPER_SCRIPT}"
assert_file_exists "${CURSOR_HELPER_SCRIPT}"
assert_file_exists "${DEPLOY_SCRIPT}"
assert_file_exists "${VALUES_FILE}"
assert_file_exists "${OPENSHIFT_VALUES_FILE}"
assert_file_exists "${BOOTSTRAP_DOC}"
assert_file_exists "${TRACING_DOC}"
assert_file_exists "${README_FILE}"
assert_file_exists "${ENV_FILE}"
assert_file_exists "${SKILL_FILE}"
assert_file_exists "${CURSOR_SKILL_FILE}"
assert_file_exists "${AGENT_FILE}"
assert_file_exists "${CURSOR_AGENT_FILE}"
assert_file_exists "${K8S_REF}"
assert_file_exists "${OPENSHIFT_REF}"

assert_file_contains "${HELPER_SCRIPT}" "--mode <reuse|install-if-missing>"
assert_file_contains "${HELPER_SCRIPT}" "CHART_VERSION"
assert_file_contains "${HELPER_SCRIPT}" "collector.values.yaml"
assert_file_contains "${HELPER_SCRIPT}" "collector.openshift.values.yaml"
assert_file_contains "${HELPER_SCRIPT}" "instrumentations.opentelemetry.io"

assert_file_contains "${DEPLOY_SCRIPT}" "--splunk-otel-mode <skip|reuse|install-if-missing>"
assert_file_contains "${DEPLOY_SCRIPT}" "--splunk-otel-cluster-name <name>"
assert_file_contains "${DEPLOY_SCRIPT}" "Ensuring Splunk OTel collector"

assert_file_contains "${VALUES_FILE}" "operatorcrds:"
assert_file_contains "${VALUES_FILE}" "install: true"
assert_file_contains "${VALUES_FILE}" "operator:"
assert_file_contains "${VALUES_FILE}" "enabled: true"
assert_file_contains "${VALUES_FILE}" "instrumentation:"
assert_file_contains "${VALUES_FILE}" "installationJob:"
assert_file_contains "${VALUES_FILE}" "baggage"
assert_file_contains "${VALUES_FILE}" "b3"
assert_file_contains "${VALUES_FILE}" "tracecontext"

assert_file_contains "${OPENSHIFT_VALUES_FILE}" "distribution: openshift"
assert_file_contains "${OPENSHIFT_VALUES_FILE}" "securityContextConstraints:"
assert_file_contains "${OPENSHIFT_VALUES_FILE}" "create: true"

assert_file_contains "${BOOTSTRAP_DOC}" "collector is already installed"
assert_file_contains "${BOOTSTRAP_DOC}" "otel-splunk/splunk-otel-collector"
assert_file_contains "${BOOTSTRAP_DOC}" "--splunk-otel-mode install-if-missing"

assert_file_contains "${TRACING_DOC}" "splunk-otel-collector-bootstrap.md"
assert_file_contains "${TRACING_DOC}" "otel-splunk/splunk-otel-collector"

assert_file_contains "${README_FILE}" "SPLUNK_OTEL_CLUSTER_NAME"
assert_file_contains "${README_FILE}" "SPLUNK_OTEL_HELM_CHART_VERSION"
assert_file_contains "${README_FILE}" "ensure-splunk-otel-collector.sh"
assert_file_contains "${README_FILE}" "--splunk-otel-mode install-if-missing"

assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_CLUSTER_NAME"
assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_HELM_CHART_VERSION=0.148.0"

assert_file_contains "${SKILL_FILE}" "already has the Splunk Observability Cloud collector installed"
assert_file_contains "${SKILL_FILE}" 'namespace `otel-splunk` with instrumentation `splunk-otel-collector`'
assert_file_contains "${SKILL_FILE}" "install-if-missing"

assert_file_contains "${CURSOR_SKILL_FILE}" "already has the Splunk Observability Cloud collector installed"
assert_file_contains "${CURSOR_SKILL_FILE}" ".cursor/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"
assert_file_contains "${CURSOR_SKILL_FILE}" "install-if-missing"

assert_file_contains "${AGENT_FILE}" "install or reuse the repo-compatible Splunk OTel collector"
assert_file_contains "${CURSOR_AGENT_FILE}" "install or reuse the repo-compatible Splunk OTel collector"
assert_file_contains "${K8S_REF}" "--splunk-otel-mode install-if-missing"
assert_file_contains "${OPENSHIFT_REF}" "--splunk-otel-mode install-if-missing"

printf 'PASS: Splunk OTel bootstrap config and docs are aligned.\n'
