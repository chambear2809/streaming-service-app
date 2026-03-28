---
name: deploy-streaming-app
description: Deploy the streaming-service-app demo stack to Kubernetes or OpenShift when a user wants the repo running in a cluster, needs namespace-safe manifest rendering, or wants the frontend exposed without hand-editing the checked-in YAML.
---

# Deploy Streaming App

## Overview

Use this skill when the task is to deploy this repository's demo stack into a Kubernetes or OpenShift cluster. It wraps the repo's existing manifests and source-packaging flow with namespace-aware rendering, frontend runtime labeling, and OpenShift route support.

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

## Requirements

- `kubectl` for Kubernetes or `oc` for OpenShift
- cluster access to create namespaces, deployments, services, configmaps, and optionally routes
- `npm`, `tar`, and `git` on the local workstation running the skill
- outbound image pulls from the cluster, unless the required images are mirrored internally

## Notes

- The deploy flow includes `k8s/backend-demo/postgres.yaml` because `billing-service` needs it.
- For OpenShift, the script rewrites Maven cache paths to `/tmp/.m2` so in-cluster builds are less dependent on root-owned paths.
- The frontend build picks up deployment-specific labels at build time through environment overrides in `frontend/scripts/build.mjs`.
- `namespace` and `FRONTEND_ROUTE_NAME` must be lowercase RFC 1123 labels.
- External frontend and RTSP URLs are best-effort discoveries. Tune `--external-url-timeout` or `EXTERNAL_URL_TIMEOUT_SECONDS` when the cluster provisions addresses slowly.
