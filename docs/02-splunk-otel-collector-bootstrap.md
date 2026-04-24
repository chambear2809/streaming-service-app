# 02. Splunk OTel Collector Bootstrap

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
- `SPLUNK_OTEL_SECONDARY_REALM`
- `SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN`
- `SPLUNK_OTEL_SECONDARY_INGEST_URL`
- `SPLUNK_OTEL_SECONDARY_API_URL`

If `SPLUNK_OTEL_CLUSTER_NAME` is unset, the helper falls back to the current kube context name.

If both `SPLUNK_OTEL_SECONDARY_REALM` and `SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN` are set, the helper layers [`k8s/otel-splunk/collector.secondary-o11y.values.yaml`](../k8s/otel-splunk/collector.secondary-o11y.values.yaml) on top of the base install and dual-ships the collector's `agent` and `clusterReceiver` telemetry to the second Splunk Observability Cloud org without removing the primary destination.

For standard realms, leave `SPLUNK_OTEL_SECONDARY_INGEST_URL` and `SPLUNK_OTEL_SECONDARY_API_URL` unset and the helper derives the normal `https://ingest.<realm>.signalfx.com` and `https://api.<realm>.signalfx.com` endpoints. Set those overrides only when the secondary tenant uses custom hosts such as `https://external-ingest.rc0.signalfx.com`.

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
When you upgrade an existing collector release and leave `SPLUNK_OTEL_HELM_CHART_VERSION` unset, the helper reuses the currently installed chart version so a config-only change does not downgrade the cluster.

## Topology Assumptions In This Repo

The checked-in collector values are opinionated. A repo-compatible collector is not just any working Splunk OTel install.

This repo expects:

- collector `agent` and `clusterReceiver` pods pinned to the private nodegroup
- toleration `dedicated=otel:NoSchedule` on those collector workloads
- app auto-instrumentation exporting OTLP gRPC to `http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317`
- the agent Service patched to `internalTrafficPolicy=Cluster`

That last item matters because chart `0.149.0` hardcodes `internalTrafficPolicy=Local`, but this repo intentionally keeps the agents only on the private `otel` nodes. The helper now uses [`skills/deploy-streaming-app/scripts/post-render-splunk-otel-manifests.sh`](../skills/deploy-streaming-app/scripts/post-render-splunk-otel-manifests.sh) as a Helm post-renderer so the release manifest itself carries `internalTrafficPolicy=Cluster`, then rechecks the live Service after install or upgrade.

The collector egress path is also part of the design:

- collector outbound traffic leaves through the private nodegroup
- the stable router EIP is `44.208.125.119`
- whitelist that IP when Splunk needs a fixed source address for this cluster

For the full traffic diagram and troubleshooting model, read [`docs/09-splunk-otel-traffic-architecture.md`](09-splunk-otel-traffic-architecture.md).

## OpenShift Notes

- The repo layers `k8s/otel-splunk/collector.openshift.values.yaml` on top of the base values.
- That overlay sets `distribution=openshift` and keeps SCC creation enabled for the agent.
- The chart's self-signed webhook certificate path remains enabled by default.

## PostgreSQL DB Monitoring

This bootstrap flow installs or verifies the base collector only. PostgreSQL Database Monitoring remains a separate overlay step:

- Start with the base collector bootstrap described here.
- Then layer [`k8s/otel-splunk/postgresql-dbmon.values.yaml`](../k8s/otel-splunk/postgresql-dbmon.values.yaml) onto the same Helm release.
- Follow [`docs/04-postgresql-db-monitoring.md`](04-postgresql-db-monitoring.md) for the DBMON-specific secret, endpoint, and validation steps.

## Operational Notes

- If you add the collector after the app workloads already exist, rerun the canonical deploy or restart the affected deployments so the mutating webhook can inject instrumentation.
- If you are only adding the optional secondary Splunk Observability export, rerun the helper in `install-if-missing` mode with `SPLUNK_OTEL_SECONDARY_REALM` and `SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN` set. It will reuse the existing release name and upgrade the collector in place.
- When the secondary tenant uses nonstandard endpoints such as rc0, set `SPLUNK_OTEL_SECONDARY_INGEST_URL` and `SPLUNK_OTEL_SECONDARY_API_URL` explicitly to hosts such as `https://external-ingest.rc0.signalfx.com` and `https://external-api.rc0.signalfx.com`.
- `duo-sso` refreshes AWS credentials, but it does not bypass local VPN or TLS interception issues on the EKS API path. If `kubectl` still fails after a clean login, check the cluster `/32` allowlist and any corporate VPN or Cisco Secure Access path separately.
- The install path needs local `helm` plus outbound access to the Splunk chart repository.
