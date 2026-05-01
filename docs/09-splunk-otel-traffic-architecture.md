# 09. Splunk OTel Traffic Architecture

This guide documents the traffic model that actually worked in the `streaming-eks-delay-demo` environment, including the rc0 secondary export path.

The key design choice is that public application ingress and collector egress
are both router-controlled, while service-to-service application calls remain
inside the cluster:

- app pods can run on the normal worker nodes
- the Splunk OTel Collector runs on the private `otel` nodegroup
- collector egress leaves through the router Elastic IP `44.208.125.119`

That split is intentional. It gives the collector one stable source IP for Splunk allowlisting without forcing the whole app onto the private nodegroup.

For the full routing contract, including browser, loadgen, ThousandEyes, RTSP,
collector, and EKS API paths, see
[`10-traffic-flow-contract.md`](10-traffic-flow-contract.md).

## Architecture Diagram

The Mermaid diagram below focuses on traffic direction, not Kubernetes object ownership.

```mermaid
flowchart LR
    subgraph Producers["Traffic Producers"]
        Browser["Browsers"]
        TEHTTP["ThousandEyes HTTP tests"]
        Loadgen["Broadcast + operator loadgen"]
        TERTSP["ThousandEyes RTSP TCP test"]
        TEA2A["ThousandEyes UDP / RTP<br/>agent-to-agent tests"]
    end

    subgraph App["Application Workloads"]
        Frontend["streaming-frontend"]
        Services["Java demo services"]
        Rtsp["media-service-demo-rtsp"]
    end

    subgraph OTel["Collector Plane"]
        AgentSvc["splunk-otel-collector-agent Service<br/>OTLP gRPC 4317<br/>internalTrafficPolicy=Cluster"]
        Agents["splunk-otel-collector-agent DaemonSet<br/>private nodegroup + dedicated=otel"]
        ClusterRx["splunk-otel-collector-k8s-cluster-receiver<br/>private nodegroup + dedicated=otel"]
    end

    RouterIngress["Router ingress<br/>public app path<br/>80 + 8554"]
    RouterEgress["Router egress / NAT<br/>44.208.125.119"]
    TEA2ATarget["Selected ThousandEyes<br/>target agent"]

    subgraph Splunk["Splunk Observability Destinations"]
        Primary["Primary Splunk O11y org"]
        RC0["Secondary rc0 org<br/>external-ingest.rc0.signalfx.com<br/>external-api.rc0.signalfx.com"]
    end

    Browser --> RouterIngress
    TEHTTP --> RouterIngress
    Loadgen --> RouterIngress
    TERTSP --> RouterIngress
    TEA2A --> TEA2ATarget

    RouterIngress --> Frontend
    RouterIngress --> Rtsp

    Frontend --> Services
    Services --> AgentSvc
    Frontend --> AgentSvc
    AgentSvc --> Agents

    Agents --> RouterEgress
    ClusterRx --> RouterEgress

    RouterEgress --> Primary
    RouterEgress --> RC0
```

## What Each Path Does

### 1. User, loadgen, and ThousandEyes request traffic

- Browsers, the broadcast loadgen, and the operator loadgen must hit
  `streaming-frontend` through the router-backed public URL.
- The HTTP ThousandEyes tests must also hit router-backed frontend URLs.
- The RTSP ThousandEyes TCP test must hit the router-backed RTSP endpoint on
  `8554`.
- The UDP and RTP ThousandEyes tests are agent-to-agent tests. They represent
  media-path quality between the selected agents and do not prove frontend or
  RTSP endpoint reachability by themselves.

Only the HTTP ThousandEyes tests create APM traces. The RTSP, UDP, and RTP tests are still useful for network visibility, but they do not create application spans by themselves.

### 2. App traces into the collector

The repo's Java and Node.js auto-instrumentation path expects:

- `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317`

`4317` matters here. The agent service is used as a cluster-wide OTLP gRPC entry point for app pods.

### 3. Why `internalTrafficPolicy=Cluster` matters

The collector agents run only on the private `otel` nodes so their outbound traffic stays on the router path.

That means app pods on the normal worker nodes are often talking to a Service whose backing pods are on different nodes. With the chart default `internalTrafficPolicy=Local`, those app pods can fail to reach the collector even though the collector itself is healthy.

The repo therefore treats this as part of the required collector shape:

- `splunk-otel-collector-agent` must use `internalTrafficPolicy=Cluster`
- the helper enforces that with a Helm post-renderer and then rechecks the live Service because chart `0.149.0` hardcodes `Local`

### 4. Collector egress to Splunk

Collector outbound traffic is intentionally centralized:

- `splunk-otel-collector-agent` exports traces, metrics, and entities
- `splunk-otel-collector-k8s-cluster-receiver` exports cluster metrics and metadata
- both leave from the private nodegroup
- private subnet egress goes through the router EIP `44.208.125.119`

That is the IP to allowlist when Splunk needs one stable source address from this cluster.

### 5. Secondary rc0 export

The secondary org is not a normal realm-only configuration.

The working rc0 path used:

- `SPLUNK_OTEL_SECONDARY_REALM=rc0`
- `SPLUNK_OTEL_SECONDARY_INGEST_URL=https://external-ingest.rc0.signalfx.com`
- `SPLUNK_OTEL_SECONDARY_API_URL=https://external-api.rc0.signalfx.com`

The repo overlays [`k8s/otel-splunk/collector.secondary-o11y.values.yaml`](../k8s/otel-splunk/collector.secondary-o11y.values.yaml) when both `SPLUNK_OTEL_SECONDARY_REALM` and `SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN` are set.

Secondary metrics export through both the collector's SignalFx exporter and the
OTLP HTTP exporter. The OTLP path is:

- `${SPLUNK_OTEL_SECONDARY_INGEST_URL}/v2/datapoint/otlp`

Frontend Browser RUM dual-send is a separate path from collector dual-export.
The browser does not use `SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN`; it needs a
dedicated browser-visible token in `SPLUNK_RUM_SECONDARY_ACCESS_TOKEN`. When
both primary and secondary RUM tokens are set, the frontend sends spans to the
primary RUM endpoint and adds a secondary span processor for the second org.
Session replay follows that secondary destination too, but only the primary
replay exporter keeps the SDK's persistent failed-replay queue. When only the
secondary RUM token is set, the secondary destination is the only active browser
RUM destination.

## What Broke And What It Looked Like

### OTLP protocol and port mismatch

The app pods were configured with `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`, but the working agent path is `4317`, not `4318`.

Symptoms:

- infrastructure metrics still showed up
- APM spans did not
- collector logs did not show app spans being accepted

### Agent service traffic policy drift

The chart installed the agent Service with `internalTrafficPolicy=Local` while the DaemonSet was pinned to the private `otel` nodes.

Symptoms:

- app pods on public workers timed out connecting to the agent Service
- private-node test pods could still connect
- collector health looked normal even while app traces were missing

### Environment tag mismatch

Collector-side telemetry uses `SPLUNK_DEPLOYMENT_ENVIRONMENT`, and the repo deploy scripts now render that same value into `deployment.environment` for the app manifests.

Symptoms:

- direct `kubectl apply` of the checked-in manifests without the repo scripts can still leave traces under the default `deployment.environment=streaming-app`
- scripted deploys keep infra and APM aligned on the same environment label

### Sparse synthetic traffic

The HTTP ThousandEyes tests do generate spans, but at a low rate. They are useful as proof that the path works, not as heavy trace generation.

The recurring load generators are much better when you want obvious APM volume.

## Validation Checklist

Use these checks after changing the collector, token, or allowlist setup.

### Canonical live smoke

Use the repo smoke test first:

```bash
bash skills/deploy-streaming-app/tests/splunk-otel-tracing-live-smoke.test.sh
```

It verifies the collector shape, generates `trace-map` traffic, checks accepted and exported span counters on every agent pod, and fails on recent exporter error patterns. If the app namespace is not the repo default, override `APP_NAMESPACE`.

### Collector placement

Confirm the collector is still on the private nodegroup:

```bash
kubectl -n otel-splunk get pods -o wide
```

### Agent Service routing

Confirm the Service still routes cluster-wide:

```bash
kubectl -n otel-splunk get svc splunk-otel-collector-agent \
  -o jsonpath='{.spec.internalTrafficPolicy}'
```

Expected output:

```text
Cluster
```

### App pod reachability to OTLP gRPC

From an app pod on a normal worker node, test the Service on `4317`:

```bash
kubectl -n streaming-demo exec deploy/streaming-frontend -- \
  node -e "const net=require('net');const s=net.connect(4317,'splunk-otel-collector-agent.otel-splunk.svc.cluster.local');s.on('connect',()=>{console.log('connected');s.end();process.exit(0)});s.on('error',(e)=>{console.error(e.message);process.exit(1)});setTimeout(()=>{console.error('timeout');process.exit(2)},5000)"
```

### Egress IP from the collector path

Launch a short-lived pod on the private `otel` nodegroup and confirm the public IP:

```bash
kubectl -n otel-splunk run egress-check \
  --rm -it --restart=Never \
  --image=curlimages/curl \
  --overrides='{"spec":{"nodeSelector":{"eks.amazonaws.com/nodegroup":"private"},"tolerations":[{"key":"dedicated","operator":"Equal","value":"otel","effect":"NoSchedule"}]}}' \
  -- curl -s https://checkip.amazonaws.com
```

Expected output:

```text
44.208.125.119
```

### Collector self-metrics for spans

Check the agent telemetry for both accepted and exported spans:

- `otelcol_receiver_accepted_spans`
- `otelcol_exporter_sent_spans{exporter="signalfx"}`
- `otelcol_exporter_sent_spans{exporter="signalfx/secondary"}`
- `otelcol_exporter_sent_spans{exporter="otlp_http/secondary"}`

If accepted spans stay at zero, the problem is still between the app pods and the collector. If accepted spans rise but secondary exported spans do not, the problem is downstream of the collector.

## Operator Notes

### Duo SSO and local `kubectl`

`duo-sso` fixes AWS authentication. It does not fix local TLS interception on the path to the EKS API.

If `kubectl` still fails after a successful `duo-sso`, re-check:

- whether the laptop public IP is allowlisted on the EKS endpoint as `/32`
- whether corporate VPN or Cisco Secure Access is intercepting the EKS TLS path

### rc0 API reads are separate from rc0 ingest

A token that can ingest into rc0 is not automatically proven to work for rc0 read or query APIs. Treat ingest validation and API-query validation as separate checks.
