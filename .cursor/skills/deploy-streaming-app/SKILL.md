---
name: deploy-streaming-app
description: Deploy the streaming-service-app demo stack to Kubernetes or OpenShift when a user wants the repo running in a cluster, needs namespace-safe manifest rendering, or wants the frontend exposed without hand-editing the checked-in YAML.
---

# Deploy Streaming App

## Overview

Use this skill when the task is to deploy this repository's demo stack into a Kubernetes or OpenShift cluster. It wraps the repo's existing manifests and source-packaging flow with namespace-aware rendering, optional repo-compatible Splunk OTel collector bootstrap, frontend runtime labeling, OpenShift route support, and the repo's ThousandEyes synthetic-test setup for both the deployed RTSP service and the Demo Monkey-sensitive public HTTP paths.

## Workflow

1. Confirm the target platform and namespace.
   - Use `--platform kubernetes` for standard clusters.
   - Use `--platform openshift` when `oc` is available and the UI should be exposed with a Route.
   - Read [references/kubernetes.md](references/kubernetes.md) or [references/openshift.md](references/openshift.md) only when platform-specific behavior matters.

2. Ask explicitly whether the user already has the Splunk Observability Cloud collector installed in the target cluster.
   - Make this yes or no answer explicit before you rely on Java or Node.js auto-instrumentation.
   - If the answer is `yes`, verify the repo-compatible install exists in namespace `otel-splunk` with instrumentation `splunk-otel-collector`.
   - Compatibility also includes propagators in this order: `baggage,b3,tracecontext`.
   - If the answer is `no`, use the repo bootstrap path instead of asking the user to hand-install the collector.
   - The repo-compatible install is `otel-splunk/splunk-otel-collector`. The checked-in app manifests already point at that exact namespace and instrumentation name.
   - The collector bootstrap needs `SPLUNK_REALM`, `SPLUNK_ACCESS_TOKEN`, and a stable cluster name. Reuse `SPLUNK_OTEL_CLUSTER_NAME` when it is already set, otherwise derive it from the current kube context or the chosen cluster label.
   - Use `.cursor/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh --mode reuse` when the user says the collector is already installed.
   - Use `.cursor/skills/deploy-streaming-app/scripts/ensure-splunk-otel-collector.sh --mode install-if-missing` when the user says the collector is not installed or the existing install is incompatible with the repo target.

3. Run the bundled deploy script.
   - Entry point: `.cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh`
   - The wrapper script delegates to the repo's canonical deploy flow in `skills/deploy-streaming-app/scripts/deploy-demo.sh`.
   - The script validates the local repo layout, required CLIs, namespace format, and exposure settings before it touches the cluster.
   - It creates the namespace or project, stages service source archives into ConfigMaps, deploys PostgreSQL plus the demo services, builds the frontend, and exposes the UI.
   - Supported collector flags: `--splunk-otel-mode`, `--splunk-otel-cluster-name`
   - Keep the script non-interactive. The skill should decide the collector mode from the user's explicit yes or no answer, then pass the flag.
   - It renders manifest content at apply time with escaped namespace substitution. Do not edit the checked-in YAML just to switch namespaces.

4. Set frontend labels only when they help the operator surface.
   - Supported flags: `--cluster-label`, `--environment-label`, `--region-label`, `--control-room-label`, `--public-rtsp-url`
   - `namespace` is always overridden so the frontend points at the deployed namespace instead of the hardcoded demo one.

5. Validate the rollout and report the access path.
   - Check `streaming-postgres`, `content-service-demo`, `media-service-demo`, `user-service-demo`, `billing-service`, `ad-service-demo`, and `streaming-frontend`.
   - On OpenShift, report the Route host.
   - On Kubernetes LoadBalancer services, let the script wait for an external address before giving up.
   - If RTSP is not externally exposed, say that explicitly instead of leaving the old demo URL implied.

6. When the user wants PostgreSQL DB monitoring in Splunk Observability Cloud, treat it as a follow-on collector change instead of part of the base collector bootstrap.
   - Read `docs/postgresql-db-monitoring.md`.
   - Use `k8s/otel-splunk/postgresql-dbmon.values.yaml` as the repo's checked-in override fragment for the collector Helm release.
   - Ask explicitly whether the user wants PostgreSQL DB monitoring configured.
   - Ask explicitly whether the user also wants PostgreSQL server logs forwarded to Splunk Platform, whether Splunk Cloud Platform or Splunk Enterprise.
   - In this repo environment, default the DB log answer to `no` unless the user confirms they have access to Splunk Platform and wants that extra path.
   - The wrapper script can now install or reuse the base Splunk OTel Collector when `--splunk-otel-mode` is set, but PostgreSQL DB monitoring is still a separate Helm values overlay on that collector release.
   - Check whether `.env` or the current shell already defines `SPLUNK_DBMON_POSTGRES_ENDPOINT`, `SPLUNK_DBMON_POSTGRES_DATABASES`, `SPLUNK_DBMON_POSTGRES_USERNAME`, `SPLUNK_DBMON_POSTGRES_PASSWORD`, `SPLUNK_DBMON_ACCESS_TOKEN`, `SPLUNK_DBMON_EVENT_ENDPOINT`, `SPLUNK_DBMON_TLS_INSECURE`, `SPLUNK_DBMON_ENABLE_QUERY_SAMPLES`, and `SPLUNK_DBMON_ENABLE_TOP_QUERIES`.
   - In this repo, the PostgreSQL receiver `databases` list should target `streaming`, not schema names such as `demo_content`, `demo_media`, or `billing`.
   - If the app was deployed into a non-default namespace, update the PostgreSQL endpoint away from the checked-in default `streaming-postgres.streaming-service-app.svc.cluster.local:5432`.
   - On Kubernetes, put the `postgresql` receiver under `clusterReceiver.config`, not `agent.config`, so the shared database is scraped once instead of once per node.
   - Add dedicated `metrics/dbmon` and `logs/dbmon` pipelines for PostgreSQL DB monitoring, and keep their processors identical in the same order.
   - Do not try to append `postgresql` to the chart's default `metrics` receiver list through a thin overlay unless you intentionally want to take ownership of that whole list.
   - Verify that PostgreSQL itself is ready for query-level DB monitoring. In this repo that means `shared_preload_libraries=pg_stat_statements` and `CREATE EXTENSION IF NOT EXISTS pg_stat_statements` in both the `streaming` and `postgres` databases.
   - If the PostgreSQL pod predates the manifest change in `k8s/backend-demo/postgres.yaml`, call out that a controlled PostgreSQL restart or one-time in-place enablement is still required before query samples and top queries can succeed.
   - If required DB monitoring values are missing, stop and prompt for the exact variable names instead of guessing them.
   - After the collector Helm upgrade, validate the cluster receiver logs for PostgreSQL receiver startup or auth failures and state clearly whether query samples and top queries were enabled.
   - For repo live validation, use `skills/deploy-streaming-app/tests/postgresql-db-monitoring-live-smoke.test.sh` and override `POSTGRES_ENDPOINT` when the PostgreSQL service FQDN differs from the repo default.

7. When the user wants ThousandEyes coverage, set up and validate the ThousandEyes inputs before creating tests.
   - Read `docs/thousandeyes-rtsp-api.md` for the supported test model and the repo scripts.
   - Ensure the repo-root `.env` exists. If it does not, create it from `example.env`.
   - Ask whether the ThousandEyes tests should target `local` cluster-private endpoints or `external` public endpoints. Make the choice explicit before you create or update any tests.
   - For `local` mode, prefer the in-cluster or private-service addresses that Enterprise Agents on the same network can reach, such as `streaming-frontend.<namespace>.svc.cluster.local` and the cluster-reachable RTSP endpoint.
   - For `external` mode, require the browser-facing or internet-reachable frontend base URL and RTSP hostname instead of silently using `svc.cluster.local` defaults.
   - Before calling the ThousandEyes API, check whether `.env` or the current shell already defines `THOUSANDEYES_BEARER_TOKEN`. For the create flows, also confirm the needed per-test inputs such as `TE_SOURCE_AGENT_IDS`, `TE_TARGET_AGENT_ID`, optional `TE_UDP_TARGET_AGENT_ID`, and the RTSP or HTTP target variables for the chosen mode.
   - `THOUSANDEYES_ACCOUNT_GROUP_ID` is the preferred repo setting for deterministic org or account-group targeting and is required later for dashboard sync, but the direct ThousandEyes create helper can still use the token's default account group when it is omitted.
   - If any required token or object ID for the chosen flow is missing, stop and prompt the user for the exact variable names. Tell them they can either edit the repo-root `.env` or export the variables in their shell for the current session.
   - Use `THOUSANDEYES_BEARER_TOKEN` as the primary auth variable. `THOUSANDEYES_TOKEN` is only a compatibility fallback.
   - If the user is not sure which ThousandEyes org to configure, start with `scripts/thousandeyes/create-rtsp-tests.sh list-orgs`. In this repo, the ThousandEyes org choice maps to an account group.
   - Discover visible account groups with `scripts/thousandeyes/create-rtsp-tests.sh list-account-groups`.
   - Discover visible agents with `scripts/thousandeyes/create-rtsp-tests.sh list-agents`.
   - Summarize the returned org or account-group names and IDs, then use the selected ID for `THOUSANDEYES_ACCOUNT_GROUP_ID`.
   - Make the user-facing mapping explicit: `THOUSANDEYES_ACCOUNT_GROUP_ID` comes from `list-orgs`, while `TE_SOURCE_AGENT_IDS`, `TE_TARGET_AGENT_ID`, and optional `TE_UDP_TARGET_AGENT_ID` come from `list-agents`.
   - If the user asks for a specific Enterprise Agent, search the visible agents and, if needed, query `/v7/agents?aid=<account-group-id>` for each visible account group until the named agent is found.
   - Write the chosen `THOUSANDEYES_ACCOUNT_GROUP_ID`, `TE_SOURCE_AGENT_IDS`, `TE_TARGET_AGENT_ID`, and, when used, `TE_UDP_TARGET_AGENT_ID` into the repo `.env` when the user wants the setup persisted.
   - In `external` mode, prompt for `TE_RTSP_SERVER`, `TE_RTSP_PORT`, and either `TE_DEMO_MONKEY_FRONTEND_BASE_URL` or the derived `TE_TRACE_MAP_TEST_URL` and `TE_BROADCAST_TEST_URL` before creating tests if they are not already set.
   - In `local` mode, if the RTSP endpoint cannot be discovered automatically, prompt the user for `TE_RTSP_SERVER` and `TE_RTSP_PORT` before creating tests.
   - Prefer a far-away Cloud Agent as the target when the user wants geographic separation from `us-east-1`. A valid example already confirmed in this repo is Cloud Agent `3` `Singapore`.
   - If either side of the UDP test is a Cloud Agent, set `TE_A2A_THROUGHPUT_MEASUREMENTS=false` before running the create flow. ThousandEyes rejects throughput measurements when a Cloud Agent participates. When RTP should stay on an Enterprise Agent, prefer `TE_UDP_TARGET_AGENT_ID` instead of changing the shared `TE_TARGET_AGENT_ID`.
   - The RTSP control-path test is agent-to-server and can run with only one Enterprise Agent. The UDP and RTP proxy tests still need a valid target agent.
   - For Demo Monkey-driven demos, prefer the `http-server` tests for `/api/v1/demo/public/trace-map` and `/api/v1/demo/public/broadcast/live/index.m3u8`. Those are the endpoints Demo Monkey actually degrades.

8. Create the ThousandEyes tests from the cluster only after the relevant endpoints are reachable.
   - For `local` mode, use `scripts/thousandeyes/deploy-k8s-rtsp-tests.sh` so the job discovers the `media-service-demo-rtsp` LoadBalancer hostname, derives the in-cluster `streaming-frontend` base URL, and creates the ThousandEyes tests from inside Kubernetes.
   - For `external` mode, either export `TE_DEMO_MONKEY_FRONTEND_BASE_URL`, `TE_TRACE_MAP_TEST_URL`, `TE_BROADCAST_TEST_URL`, `TE_RTSP_SERVER`, and `TE_RTSP_PORT` before using the direct API helper, or override those values before running the Kubernetes wrapper so it does not fall back to cluster-local targets.
   - Use `K8S_DRY_RUN=true` first when the user wants manifest verification without creating the Secret, ConfigMap, and Job for real.
   - Use `THOUSANDEYES_JOB_ACTION=create-demo-monkey-http` when the user specifically wants the Demo Monkey-sensitive HTTP tests.
   - Report whether the created tests target `local` or `external` endpoints, the resolved RTSP hostname and port, the frontend base URL or explicit HTTP test URLs, and state clearly if the agent-to-agent tests are partially constrained by Cloud Agent rules.

9. Build the Splunk demo dashboards only after the ThousandEyes tests are live.
   - Use `scripts/thousandeyes/create-demo-dashboards.py` so the dashboard group stays reproducible and ordered for the demo.
   - Before calling the Splunk API, check whether `.env` or the current shell already defines `SPLUNK_REALM`, `SPLUNK_ACCESS_TOKEN`, `THOUSANDEYES_BEARER_TOKEN`, and `THOUSANDEYES_ACCOUNT_GROUP_ID`. `SPLUNK_RUM_APP_NAME` and `SPLUNK_DEPLOYMENT_ENVIRONMENT` can fall back to repo defaults, but override them when the deployed demo uses different names.
   - If the user wants to update an existing dashboard group and the group ID is not already known, first consider `--group-name` or the script's automatic single-prefix match. Prompt for `SPLUNK_DEMO_DASHBOARD_GROUP_ID` only when multiple matching groups make the target ambiguous.
   - If the demo was deployed into a non-default namespace, either pass `--namespace <deployed-namespace>` to the script or persist that value in `STREAMING_K8S_NAMESPACE`.
   - If the dashboard-write token cannot read SignalFlow metric data, set `SPLUNK_VALIDATION_TOKEN` or pass `--skip-te-metric-validation` before you rerun the sync.
   - If the RTP dashboard should be populated, verify that an enabled ThousandEyes OTel metric stream includes the RTP test in `testMatch` or exports the `voice` test type. The dashboard helper now warns when the repo RTP test exists but no enabled metric stream appears to cover it.
   - When the repo ThousandEyes tests were created with non-default names or duplicate names exist, set the matching `TE_*_TEST_NAME` or `TE_*_TEST_ID` overrides before syncing dashboards.
   - If any required Splunk token or object ID is missing, stop and prompt the user for the exact variable names. Tell them they can either edit the repo-root `.env` or export the variables in their shell for the current session.
   - Keep the dashboard group easy to follow for the demo: `01 Start Here: Network Symptoms`, `02 Pivot: User Impact To Root Cause`, `03 Backend Critical Path`, then the protocol deep dives.
   - State clearly that the dashboard sync script only creates detail dashboards for repo ThousandEyes tests that actually exist in the selected account group. It should skip missing tests instead of creating empty placeholders.

## Quick Start

Standard Kubernetes deployment:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
```

Install the repo-compatible Splunk OTel collector when the cluster does not already have it:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --splunk-otel-mode install-if-missing
```

OpenShift deployment:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform openshift \
  --namespace streaming-demo
```

Custom operator-facing labels:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --cluster-label "$(kubectl config current-context)" \
  --environment-label "West Coast Operations" \
  --region-label "US West Distribution Region"
```

Skip waiting for external Route or LoadBalancer addresses:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --external-url-timeout 0
```

Create ThousandEyes tests after deploy:

```bash
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

Create ThousandEyes tests against externally reachable endpoints:

```bash
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
export TE_RTSP_SERVER='rtsp.example.com'
export TE_RTSP_PORT='8554'
export TE_DEMO_MONKEY_FRONTEND_BASE_URL='https://demo.example.com'
scripts/thousandeyes/create-rtsp-tests.sh create-all
```

Create only the Demo Monkey-sensitive HTTP tests after deploy:

```bash
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
export THOUSANDEYES_JOB_ACTION='create-demo-monkey-http'
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

Sync the Splunk demo dashboards after the tests are live:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py
```

Sync a specific existing dashboard group:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py \
  --group-id "$SPLUNK_DEMO_DASHBOARD_GROUP_ID"
```

## Requirements

- `kubectl` for Kubernetes or `oc` for OpenShift
- `helm` on the workstation when the task includes repo-managed collector install
- cluster access to create namespaces, deployments, services, configmaps, and optionally routes
- `node`, `npm`, `tar`, and `git` on the local workstation running the skill
- outbound image pulls from the cluster, unless the required images are mirrored internally
- outbound access from the workstation to `https://signalfx.github.io/splunk-otel-collector-chart` when the task includes repo-managed collector install
- outbound API access to `api.thousandeyes.com` when the task includes ThousandEyes setup or agent discovery
- outbound API access to `api.<realm>.signalfx.com` when the task includes Splunk dashboard creation

## Notes

- The deploy flow includes `k8s/backend-demo/postgres.yaml` because `billing-service` needs it.
- The canonical deploy flow can install or reuse the base Splunk OTel Collector when `--splunk-otel-mode` is set, but PostgreSQL DB monitoring remains a separate Helm values overlay after the base collector is ready.
- For OpenShift, the script rewrites Maven cache paths to `/tmp/.m2` so in-cluster builds are less dependent on root-owned paths.
- The frontend build picks up deployment-specific labels at build time through environment overrides in `frontend/scripts/build.mjs`.
- `namespace` and `FRONTEND_ROUTE_NAME` must be lowercase RFC 1123 labels.
- External frontend and RTSP URLs are best-effort discoveries. Tune `--external-url-timeout` or `EXTERNAL_URL_TIMEOUT_SECONDS` when the cluster provisions addresses slowly.
- ThousandEyes automation in this repo is split between `scripts/thousandeyes/create-rtsp-tests.sh` for direct API use and `scripts/thousandeyes/deploy-k8s-rtsp-tests.sh` for the in-cluster Job path.
- The ThousandEyes workflow now has two target modes: `local` for cluster-private or same-network endpoints, and `external` for publicly reachable endpoints. Make the user choose one before you derive test URLs.
- Splunk dashboard automation in this repo uses `scripts/thousandeyes/create-demo-dashboards.py`.
- PostgreSQL server log forwarding is optional and should be treated as a separate yes or no prompt. In this repo environment, default that answer to `no` unless the user confirms they have Splunk Platform access.
- User-facing ThousandEyes "org" selection in this repo maps to `THOUSANDEYES_ACCOUNT_GROUP_ID`, and the helper script exposes that through `list-orgs`.
- The full test-plus-dashboard flow needs the ThousandEyes bearer token, source agent IDs, target agent ID, and usually an account-group ID. The direct test-creation helper can fall back to the default account group, but dashboard sync requires `THOUSANDEYES_ACCOUNT_GROUP_ID`.
- Dashboard sync always needs `SPLUNK_REALM` and `SPLUNK_ACCESS_TOKEN`, plus the ThousandEyes bearer token and account-group ID so it can resolve tests. `SPLUNK_RUM_APP_NAME`, `SPLUNK_DEPLOYMENT_ENVIRONMENT`, `SPLUNK_VALIDATION_TOKEN`, namespace, group selection, and custom test-name or test-id overrides are situational. Prompt for missing values instead of guessing them.
- A named Enterprise Agent may only be visible in one ThousandEyes account group. Search account groups explicitly before assuming the token cannot see it.
