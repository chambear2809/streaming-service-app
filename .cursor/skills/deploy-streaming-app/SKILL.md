---
name: deploy-streaming-app
description: Deploy the streaming-service-app demo stack to Kubernetes or OpenShift when a user wants the repo running in a cluster, needs namespace-safe manifest rendering, or wants the frontend exposed without hand-editing the checked-in YAML.
---

# Deploy Streaming App

## Overview

Use this skill when the task is to deploy this repository's demo stack into a Kubernetes or OpenShift cluster. It wraps the repo's existing manifests and source-packaging flow with namespace-aware rendering, frontend runtime labeling, OpenShift route support, and the repo's ThousandEyes synthetic-test setup for both the deployed RTSP service and the Demo Monkey-sensitive public HTTP paths.

## Workflow

1. Confirm the target platform and namespace.
   - Use `--platform kubernetes` for standard clusters.
   - Use `--platform openshift` when `oc` is available and the UI should be exposed with a Route.
   - Read [references/kubernetes.md](references/kubernetes.md) or [references/openshift.md](references/openshift.md) only when platform-specific behavior matters.

2. Run the bundled deploy script.
   - Entry point: `.cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh`
   - The wrapper script delegates to the repo's canonical deploy flow in `skills/deploy-streaming-app/scripts/deploy-demo.sh`.
   - The script validates the local repo layout, required CLIs, namespace format, and exposure settings before it touches the cluster.
   - It creates the namespace or project, stages service source archives into ConfigMaps, deploys PostgreSQL plus the demo services, builds the frontend, and exposes the UI.
   - It renders manifest content at apply time with escaped namespace substitution. Do not edit the checked-in YAML just to switch namespaces.

3. Set frontend labels only when they help the operator surface.
   - Supported flags: `--cluster-label`, `--environment-label`, `--region-label`, `--control-room-label`, `--public-rtsp-url`
   - `namespace` is always overridden so the frontend points at the deployed namespace instead of the hardcoded demo one.

4. Validate the rollout and report the access path.
   - Check `streaming-postgres`, `content-service-demo`, `media-service-demo`, `user-service-demo`, `billing-service`, `ad-service-demo`, and `streaming-frontend`.
   - On OpenShift, report the Route host.
   - On Kubernetes LoadBalancer services, let the script wait for an external address before giving up.
   - If RTSP is not externally exposed, say that explicitly instead of leaving the old demo URL implied.

5. When the user wants ThousandEyes coverage, set up and validate the ThousandEyes inputs before creating tests.
   - Read `docs/thousandeyes-rtsp-api.md` for the supported test model and the repo scripts.
   - Ensure the repo-root `.env` exists. If it does not, create it from `example.env`.
   - Before calling the ThousandEyes API, check whether `.env` or the current shell already defines `THOUSANDEYES_BEARER_TOKEN`, `THOUSANDEYES_ACCOUNT_GROUP_ID`, `TE_SOURCE_AGENT_IDS`, and `TE_TARGET_AGENT_ID`.
   - If any required token or object ID is missing, stop and prompt the user for the exact variable names. Tell them they can either edit the repo-root `.env` or export the variables in their shell for the current session.
   - Use `THOUSANDEYES_BEARER_TOKEN` as the primary auth variable. `THOUSANDEYES_TOKEN` is only a compatibility fallback.
   - If the user is not sure which ThousandEyes org to configure, start with `scripts/thousandeyes/create-rtsp-tests.sh list-orgs`. In this repo, the ThousandEyes org choice maps to an account group.
   - Discover visible account groups with `scripts/thousandeyes/create-rtsp-tests.sh list-account-groups`.
   - Discover visible agents with `scripts/thousandeyes/create-rtsp-tests.sh list-agents`.
   - Summarize the returned org or account-group names and IDs, then use the selected ID for `THOUSANDEYES_ACCOUNT_GROUP_ID`.
   - Make the user-facing mapping explicit: `THOUSANDEYES_ACCOUNT_GROUP_ID` comes from `list-orgs`, while `TE_SOURCE_AGENT_IDS` and `TE_TARGET_AGENT_ID` come from `list-agents`.
   - If the user asks for a specific Enterprise Agent, search the visible agents and, if needed, query `/v7/agents?aid=<account-group-id>` for each visible account group until the named agent is found.
   - Write the chosen `THOUSANDEYES_ACCOUNT_GROUP_ID`, `TE_SOURCE_AGENT_IDS`, and `TE_TARGET_AGENT_ID` into the repo `.env` when the user wants the setup persisted.
   - If the RTSP endpoint cannot be discovered automatically, prompt the user for `TE_RTSP_SERVER` and `TE_RTSP_PORT` before creating tests.
   - Prefer a far-away Cloud Agent as the target when the user wants geographic separation from `us-east-1`. A valid example already confirmed in this repo is Cloud Agent `3` `Singapore`.
   - If either side of the UDP test is a Cloud Agent, set `TE_A2A_THROUGHPUT_MEASUREMENTS=false` before running the create flow. ThousandEyes rejects throughput measurements when a Cloud Agent participates.
   - The RTSP control-path test is agent-to-server and can run with only one Enterprise Agent. The UDP and RTP proxy tests still need a valid target agent.
   - For Demo Monkey-driven demos, prefer the `http-server` tests for `/api/v1/demo/public/trace-map` and `/api/v1/demo/public/broadcast/live/index.m3u8`. Those are the endpoints Demo Monkey actually degrades.

6. Create the ThousandEyes tests from the cluster only after the relevant endpoints are reachable.
   - Use `scripts/thousandeyes/deploy-k8s-rtsp-tests.sh` so the job discovers the `media-service-demo-rtsp` LoadBalancer hostname, derives the in-cluster `streaming-frontend` base URL, and creates the ThousandEyes tests from inside Kubernetes.
   - Use `K8S_DRY_RUN=true` first when the user wants manifest verification without creating the Secret, ConfigMap, and Job for real.
   - Use `THOUSANDEYES_JOB_ACTION=create-demo-monkey-http` when the user specifically wants the Demo Monkey-sensitive HTTP tests.
   - Report the resolved RTSP hostname and port, the derived frontend base URL, and state clearly if the agent-to-agent tests are partially constrained by Cloud Agent rules.

## Quick Start

Standard Kubernetes deployment:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
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

Create only the Demo Monkey-sensitive HTTP tests after deploy:

```bash
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
export THOUSANDEYES_JOB_ACTION='create-demo-monkey-http'
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

## Requirements

- `kubectl` for Kubernetes or `oc` for OpenShift
- cluster access to create namespaces, deployments, services, configmaps, and optionally routes
- `npm`, `tar`, and `git` on the local workstation running the skill
- outbound image pulls from the cluster, unless the required images are mirrored internally
- outbound API access to `api.thousandeyes.com` when the task includes ThousandEyes setup or agent discovery

## Notes

- The deploy flow includes `k8s/backend-demo/postgres.yaml` because `billing-service` needs it.
- For OpenShift, the script rewrites Maven cache paths to `/tmp/.m2` so in-cluster builds are less dependent on root-owned paths.
- The frontend build picks up deployment-specific labels at build time through environment overrides in `frontend/scripts/build.mjs`.
- `namespace` and `FRONTEND_ROUTE_NAME` must be lowercase RFC 1123 labels.
- External frontend and RTSP URLs are best-effort discoveries. Tune `--external-url-timeout` or `EXTERNAL_URL_TIMEOUT_SECONDS` when the cluster provisions addresses slowly.
- ThousandEyes automation in this repo is split between `scripts/thousandeyes/create-rtsp-tests.sh` for direct API use and `scripts/thousandeyes/deploy-k8s-rtsp-tests.sh` for the in-cluster Job path.
- User-facing ThousandEyes "org" selection in this repo maps to `THOUSANDEYES_ACCOUNT_GROUP_ID`, and the helper script exposes that through `list-orgs`.
- The required user-provided ThousandEyes inputs are the bearer token, account-group ID, source agent IDs, and target agent ID. Prompt for missing values instead of guessing them.
- A named Enterprise Agent may only be visible in one ThousandEyes account group. Search account groups explicitly before assuming the token cannot see it.
