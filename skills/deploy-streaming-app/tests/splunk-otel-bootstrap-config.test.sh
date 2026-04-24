#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
HELPER_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"
POST_RENDERER_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/post-render-splunk-otel-manifests.sh"
CURSOR_HELPER_SCRIPT="${REPO_ROOT}/.cursor/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"
DEPLOY_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/deploy-demo.sh"
VALUES_FILE="${REPO_ROOT}/k8s/otel-splunk/collector.values.yaml"
OPENSHIFT_VALUES_FILE="${REPO_ROOT}/k8s/otel-splunk/collector.openshift.values.yaml"
SECONDARY_VALUES_FILE="${REPO_ROOT}/k8s/otel-splunk/collector.secondary-o11y.values.yaml"
TRACE_LIVE_SMOKE_TEST="${REPO_ROOT}/skills/deploy-streaming-app/tests/splunk-otel-tracing-live-smoke.test.sh"
BOOTSTRAP_DOC="${REPO_ROOT}/docs/02-splunk-otel-collector-bootstrap.md"
TRACING_DOC="${REPO_ROOT}/docs/03-distributed-tracing.md"
TRAFFIC_ARCH_DOC="${REPO_ROOT}/docs/09-splunk-otel-traffic-architecture.md"
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
assert_file_exists "${POST_RENDERER_SCRIPT}"
assert_file_exists "${CURSOR_HELPER_SCRIPT}"
assert_file_exists "${DEPLOY_SCRIPT}"
assert_file_exists "${VALUES_FILE}"
assert_file_exists "${OPENSHIFT_VALUES_FILE}"
assert_file_exists "${SECONDARY_VALUES_FILE}"
assert_file_exists "${TRACE_LIVE_SMOKE_TEST}"
assert_file_exists "${BOOTSTRAP_DOC}"
assert_file_exists "${TRACING_DOC}"
assert_file_exists "${TRAFFIC_ARCH_DOC}"
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
assert_file_contains "${HELPER_SCRIPT}" 'REQUIRED_AGENT_SERVICE_TRAFFIC_POLICY="Cluster"'
assert_file_contains "${HELPER_SCRIPT}" "collector.private-egress-path=nodegroup/private+dedicated=otel"
assert_file_contains "${HELPER_SCRIPT}" 'REQUIRED_OTLP_GRPC_ENDPOINT="http://${AGENT_SERVICE}.${COLLECTOR_NAMESPACE}.svc.cluster.local:4317"'
assert_file_contains "${HELPER_SCRIPT}" "--post-renderer"
assert_file_contains "${HELPER_SCRIPT}" "post-render-splunk-otel-manifests.sh"

assert_file_contains "${POST_RENDERER_SCRIPT}" "internalTrafficPolicy: Cluster"
assert_file_contains "${POST_RENDERER_SCRIPT}" "splunk-otel-collector-agent"

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
assert_file_contains "${VALUES_FILE}" 'eks.amazonaws.com/nodegroup": private'
assert_file_contains "${VALUES_FILE}" "value: http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317"

assert_file_contains "${OPENSHIFT_VALUES_FILE}" "distribution: openshift"
assert_file_contains "${OPENSHIFT_VALUES_FILE}" "securityContextConstraints:"
assert_file_contains "${OPENSHIFT_VALUES_FILE}" "create: true"

assert_file_contains "${SECONDARY_VALUES_FILE}" "signalfx/secondary"
assert_file_contains "${SECONDARY_VALUES_FILE}" "otlp_http/secondary"
assert_file_contains "${SECONDARY_VALUES_FILE}" "SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN"
assert_file_contains "${SECONDARY_VALUES_FILE}" "SPLUNK_OTEL_SECONDARY_INGEST_URL"
assert_file_contains "${SECONDARY_VALUES_FILE}" "SPLUNK_OTEL_SECONDARY_API_URL"

assert_file_contains "${BOOTSTRAP_DOC}" "collector is already installed"
assert_file_contains "${BOOTSTRAP_DOC}" "otel-splunk/splunk-otel-collector"
assert_file_contains "${BOOTSTRAP_DOC}" "--splunk-otel-mode install-if-missing"
assert_file_contains "${BOOTSTRAP_DOC}" "SPLUNK_OTEL_SECONDARY_REALM"
assert_file_contains "${BOOTSTRAP_DOC}" "collector.secondary-o11y.values.yaml"
assert_file_contains "${BOOTSTRAP_DOC}" "SPLUNK_OTEL_SECONDARY_INGEST_URL"
assert_file_contains "${BOOTSTRAP_DOC}" "SPLUNK_OTEL_SECONDARY_API_URL"
assert_file_contains "${BOOTSTRAP_DOC}" "internalTrafficPolicy=Cluster"
assert_file_contains "${BOOTSTRAP_DOC}" "44.208.125.119"

assert_file_contains "${TRACING_DOC}" "02-splunk-otel-collector-bootstrap.md"
assert_file_contains "${TRACING_DOC}" "otel-splunk/splunk-otel-collector"
assert_file_contains "${TRACING_DOC}" "deployment.environment=streaming-app"
assert_file_contains "${TRACING_DOC}" "4317"
assert_file_contains "${TRACING_DOC}" "HTTP ThousandEyes tests"
assert_file_contains "${TRACING_DOC}" "splunk-otel-tracing-live-smoke.test.sh"
assert_file_contains "${TRACING_DOC}" "trace-map"

assert_file_contains "${TRAFFIC_ARCH_DOC}" "44.208.125.119"
assert_file_contains "${TRAFFIC_ARCH_DOC}" "external-ingest.rc0.signalfx.com"
assert_file_contains "${TRAFFIC_ARCH_DOC}" "internalTrafficPolicy=Cluster"
assert_file_contains "${TRAFFIC_ARCH_DOC}" "OTEL_EXPORTER_OTLP_PROTOCOL=grpc"
assert_file_contains "${TRAFFIC_ARCH_DOC}" "Mermaid"
assert_file_contains "${TRAFFIC_ARCH_DOC}" "splunk-otel-tracing-live-smoke.test.sh"

assert_file_contains "${README_FILE}" "SPLUNK_OTEL_CLUSTER_NAME"
assert_file_contains "${README_FILE}" "SPLUNK_OTEL_HELM_CHART_VERSION"
assert_file_contains "${README_FILE}" "SPLUNK_OTEL_SECONDARY_REALM"
assert_file_contains "${README_FILE}" "collector.secondary-o11y.values.yaml"
assert_file_contains "${README_FILE}" "SPLUNK_OTEL_SECONDARY_INGEST_URL"
assert_file_contains "${README_FILE}" "SPLUNK_OTEL_SECONDARY_API_URL"
assert_file_contains "${README_FILE}" "ensure-splunk-otel-collector.sh"
assert_file_contains "${README_FILE}" "--splunk-otel-mode install-if-missing"
assert_file_contains "${README_FILE}" "09-splunk-otel-traffic-architecture.md"
assert_file_contains "${README_FILE}" "splunk-otel-tracing-live-smoke.test.sh"

assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_CLUSTER_NAME"
assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_HELM_CHART_VERSION=0.149.0"
assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_SECONDARY_REALM="
assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN="
assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_SECONDARY_INGEST_URL="
assert_file_contains "${ENV_FILE}" "SPLUNK_OTEL_SECONDARY_API_URL="

assert_file_contains "${SKILL_FILE}" "already has the Splunk Observability Cloud collector installed"
assert_file_contains "${SKILL_FILE}" 'namespace `otel-splunk` with instrumentation `splunk-otel-collector`'
assert_file_contains "${SKILL_FILE}" "install-if-missing"
assert_file_contains "${SKILL_FILE}" "splunk-otel-tracing-live-smoke.test.sh"

assert_file_contains "${CURSOR_SKILL_FILE}" "already has the Splunk Observability Cloud collector installed"
assert_file_contains "${CURSOR_SKILL_FILE}" ".cursor/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh"
assert_file_contains "${CURSOR_SKILL_FILE}" "install-if-missing"
assert_file_contains "${CURSOR_SKILL_FILE}" "splunk-otel-tracing-live-smoke.test.sh"

assert_file_contains "${AGENT_FILE}" "install or reuse the repo-compatible Splunk OTel collector"
assert_file_contains "${CURSOR_AGENT_FILE}" "install or reuse the repo-compatible Splunk OTel collector"
assert_file_contains "${K8S_REF}" "--splunk-otel-mode install-if-missing"
assert_file_contains "${OPENSHIFT_REF}" "--splunk-otel-mode install-if-missing"

assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'TRACE_URL="'
assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'REQUIRE_SECONDARY_EXPORT="${REQUIRE_SECONDARY_EXPORT:-auto}"'
assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'otelcol_receiver_accepted_spans'
assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'otelcol_exporter_sent_spans'
assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'otelcol_exporter_send_failed_spans'
assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'internalTrafficPolicy=Cluster'
assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'unsupported protocol scheme'
assert_file_contains "${TRACE_LIVE_SMOKE_TEST}" 'RBAC: access denied'

printf 'PASS: Splunk OTel bootstrap config and docs are aligned.\n'
