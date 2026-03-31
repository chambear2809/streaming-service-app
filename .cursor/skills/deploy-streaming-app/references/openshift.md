# OpenShift Notes

Use this reference when the target cluster is OpenShift and `oc` is available.

## Defaults

- The deploy script creates or reuses an OpenShift project with `oc new-project`.
- The frontend service defaults to `ClusterIP`.
- The script exposes the frontend with an OpenShift `Route`.
- The RTSP service defaults to `ClusterIP` because Routes are HTTP(S)-only.
- The script waits up to 60 seconds for the Route host by default.

## Common Commands

Deploy to OpenShift and let the skill create the UI Route:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform openshift \
  --namespace streaming-demo
```

Install the repo-compatible Splunk OTel collector during the deploy when the cluster does not already have it:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform openshift \
  --namespace streaming-demo \
  --splunk-otel-mode install-if-missing
```

Deploy with custom frontend labels:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform openshift \
  --namespace streaming-demo \
  --cluster-label "$(oc config current-context)" \
  --environment-label "OpenShift Operations"
```

Provide an externally managed RTSP endpoint so the frontend does not show a stale demo URL:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform openshift \
  --namespace streaming-demo \
  --public-rtsp-url "rtsp://rtsp.example.com/live"
```

Skip waiting for the Route host if the platform provisions it asynchronously and you only need the rollout to finish:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform openshift \
  --namespace streaming-demo \
  --external-url-timeout 0
```

## Notes

- The script fails early if required repo files or local CLIs are missing.
- The deploy flow rewrites Maven cache paths to `/tmp/.m2` so the in-cluster build containers are friendlier to OpenShift's random UID model.
- The checked-in app manifests expect `otel-splunk/splunk-otel-collector` for Java and Node.js auto-instrumentation. Use `--splunk-otel-mode install-if-missing` if that repo-compatible collector is not already present.
- If you need external RTSP on OpenShift, use a platform-specific LoadBalancer or another TCP exposure mechanism outside the default Route model.
- If the cluster enforces image allowlists, mirror the required upstream images before deployment.
