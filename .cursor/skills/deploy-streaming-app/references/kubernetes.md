# Kubernetes Notes

Use this reference when the target cluster is standard Kubernetes rather than OpenShift.

## Defaults

- The deploy script defaults to `--platform kubernetes`.
- The frontend service defaults to `LoadBalancer`.
- The RTSP service defaults to `LoadBalancer`.
- The script waits up to 60 seconds for external addresses by default, then falls back to a `port-forward` hint if none appear.
- Namespaces must be lowercase RFC 1123 labels.

## Common Commands

Deploy into a dedicated namespace with the current kube context shown in the UI:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --cluster-label "$(kubectl config current-context)"
```

Install the repo-compatible Splunk OTel collector during the deploy when the cluster does not already have it:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --splunk-otel-mode install-if-missing
```

Use an internal frontend service and access it with `port-forward`:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --frontend-service-type ClusterIP \
  --rtsp-service-type LoadBalancer
```

Skip waiting for a cloud load balancer address during repeated inner-loop testing:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --external-url-timeout 0
```

Stamp operator-facing labels into the frontend shell:

```bash
bash .cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo \
  --environment-label "West Coast Operations" \
  --region-label "US West Distribution Region" \
  --control-room-label "Acme West NOC"
```

## Notes

- The script fails early if required repo files or local CLIs are missing.
- The script renders manifest content at apply time, so the checked-in YAML stays unchanged.
- The checked-in app manifests expect `otel-splunk/splunk-otel-collector` for Java and Node.js auto-instrumentation. Use `--splunk-otel-mode install-if-missing` if that repo-compatible collector is not already present.
- PostgreSQL is included automatically because `billing-service` depends on it.
- If the cluster blocks public image pulls, mirror the upstream container images before using the skill.
