# Splunk OTel Collector Bootstrap

This repo's Kubernetes manifests already assume a Splunk OpenTelemetry Operator and `Instrumentation` object exist at `otel-splunk/splunk-otel-collector`.

If the cluster does not already have that exact install, use the repo-managed bootstrap path before or during the app deploy.

## What The Repo Expects

- Namespace: `otel-splunk`
- Helm release: `splunk-otel-collector`
- Instrumentation object: `splunk-otel-collector`
- Auto-instrumentation path: Splunk OTel Collector Helm chart with the OpenTelemetry Operator enabled

The checked-in backend and frontend manifests annotate pods with:

- `instrumentation.opentelemetry.io/inject-java: otel-splunk/splunk-otel-collector`
- `instrumentation.opentelemetry.io/inject-nodejs: otel-splunk/splunk-otel-collector`

If you reuse an existing collector instead of installing a new one, verify that it preserves those names. Otherwise use the repo bootstrap path so the app manifests do not need to change.

Compatibility in this repo also includes the propagator order `baggage,b3,tracecontext`. That order is required so ThousandEyes metadata survives the trace handoff into Splunk Observability Cloud.

## Required Settings

The base collector install uses the same Splunk Observability settings already used elsewhere in this repo:

- `SPLUNK_REALM`
- `SPLUNK_ACCESS_TOKEN`
- `SPLUNK_DEPLOYMENT_ENVIRONMENT`

Optional overrides:

- `SPLUNK_OTEL_CLUSTER_NAME`
- `SPLUNK_OTEL_HELM_CHART_VERSION`

If `SPLUNK_OTEL_CLUSTER_NAME` is unset, the helper falls back to the current kube context name.

## Canonical Deploy Path

During the full app deploy, ask explicitly whether the collector is already installed.

- If yes, use `--splunk-otel-mode reuse`.
- If no, use `--splunk-otel-mode install-if-missing`.

Example:

```bash
bash skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --splunk-otel-mode install-if-missing
```

OpenShift example:

```bash
bash skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform openshift \
  --namespace streaming-demo \
  --splunk-otel-mode install-if-missing
```

## Collector-Only Helper

Use the helper directly when you want to bootstrap or verify the collector without deploying the app:

```bash
bash skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh \
  --mode install-if-missing \
  --platform kubernetes
```

Reuse-only verification:

```bash
bash skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh \
  --mode reuse \
  --platform kubernetes
```

The helper pins the upstream chart version to the repo default unless `SPLUNK_OTEL_HELM_CHART_VERSION` overrides it.

## OpenShift Notes

- The repo layers `k8s/otel-splunk/collector.openshift.values.yaml` on top of the base values.
- That overlay sets `distribution=openshift` and keeps SCC creation enabled for the agent.
- The chart's self-signed webhook certificate path remains enabled by default.

## PostgreSQL DB Monitoring

This bootstrap flow installs or verifies the base collector only. PostgreSQL Database Monitoring remains a separate overlay step:

- Start with the base collector bootstrap described here.
- Then layer [`k8s/otel-splunk/postgresql-dbmon.values.yaml`](../k8s/otel-splunk/postgresql-dbmon.values.yaml) onto the same Helm release.
- Follow [`docs/postgresql-db-monitoring.md`](postgresql-db-monitoring.md) for the DBMON-specific secret, endpoint, and validation steps.

## Operational Notes

- If you add the collector after the app workloads already exist, rerun the canonical deploy or restart the affected deployments so the mutating webhook can inject instrumentation.
- The install path needs local `helm` plus outbound access to the Splunk chart repository.
