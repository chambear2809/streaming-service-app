# 10. Traffic Flow Contract

Use this contract for the router-backed EKS delay demo and any booth setup that
needs ThousandEyes, browsers, load generators, and Splunk telemetry to see the
same network story.

## Non-Negotiable Routing Rules

- Public application traffic must enter through the router, not through direct
  AWS load balancer hostnames.
- Splunk OTel collector egress must leave through router EIP `44.208.125.119`.
- The EKS API server is not behind the router. Local `kubectl` access still
  depends on the laptop public IP being allowlisted on the EKS endpoint.
- Service-to-service calls stay inside the cluster. Do not route internal
  frontend-to-backend or backend-to-backend calls through the router.

## Required Flow Matrix

| Flow | Source | Destination | Port / Protocol | Required Path | Why It Matters |
| --- | --- | --- | --- | --- | --- |
| Public viewer app | Browser, public broadcast loadgen, ThousandEyes HTTP playback test | `streaming-frontend` | `80` / HTTP | client -> router EIP or router hostname -> internal frontend load balancer -> frontend pod | Keeps user-visible app traffic on the delay-injected public path. |
| Demo Monkey and trace pivot | Browser, operator, ThousandEyes trace-map test | `streaming-frontend` | `80` / HTTP | client -> router EIP or router hostname -> internal frontend load balancer -> frontend pod | Ensures trace-map symptoms and public app checks use the same network edge. |
| Public RTSP control path | RTSP client or ThousandEyes RTSP TCP source agent | `media-service-demo-rtsp` | `8554` / TCP | client -> router EIP or router hostname -> internal RTSP load balancer -> media RTSP service | Keeps RTSP reachability and path metrics on the router path. |
| In-cluster application calls | `streaming-frontend`, Java services | Cluster services | HTTP / service ports | pod -> Kubernetes Service -> backend pod | Internal application fanout should stay cluster-local. |
| App traces to collector | Node.js and Java app pods | `splunk-otel-collector-agent.otel-splunk.svc.cluster.local` | `4317` / OTLP gRPC | app pod -> collector agent Service -> private `otel` node agent pod | Keeps app telemetry ingestion cluster-local before export. |
| Collector export to Splunk | Collector agent and cluster receiver on private `otel` nodes | Splunk Observability ingest/API endpoints | `443` / HTTPS | collector pod -> private subnet route -> router NAT -> internet, source IP `44.208.125.119` | Gives Splunk one stable allowlist source IP. |
| Browser RUM beacons | Real browsers, including Playwright browser RUM loadgen | Splunk RUM ingest endpoints | `443` / HTTPS | browser runtime -> its network egress -> Splunk RUM ingest | Splunk sees the browser client's public egress IP. For the in-cluster browser RUM loadgen, pinning it to the private nodegroup makes this the router EIP path. |
| ThousandEyes UDP media path | ThousandEyes source agent | ThousandEyes target agent | configured UDP / RTP ports | source agent -> target agent | This is agent-to-agent path quality, not traffic to the app endpoint. |
| Local cluster administration | Operator laptop | EKS API endpoint | `443` / HTTPS | laptop public IP -> EKS API public endpoint | `duo-sso` authenticates AWS, but EKS API reachability still requires CIDR allowlisting. |

## Configuration Rules

- Set `TE_EXTERNAL_ROUTER_HOST` when ThousandEyes external tests must include
  the router path.
- Set `TE_DEMO_MONKEY_FRONTEND_BASE_URL` to the router-backed frontend URL for
  external HTTP tests.
- Set `TE_RTSP_SERVER` to the router host and `TE_RTSP_PORT=8554` for external
  RTSP checks.
- Set `STREAMING_PUBLIC_RTSP_URL` to the router-backed RTSP URL, for example
  `rtsp://44-208-125-119.sslip.io:8554/live` in the current RC0 demo.
- Keep the Splunk OTel collector on the private `otel` nodegroup so exporter
  traffic leaves as `44.208.125.119`.
- Keep the Playwright Browser RUM loadgen on the private router-egress nodegroup
  when RUM beacons need the same stable source IP.

## Bypass Patterns To Avoid

- Do not point public users, public loadgen, or ThousandEyes external HTTP tests
  at the direct frontend load balancer hostname.
- Do not point public RTSP checks at the direct RTSP load balancer hostname.
- Do not run collector exporters from public worker nodes when Splunk expects
  source IP `44.208.125.119`.
- Do not interpret UDP or RTP agent-to-agent tests as proof that the frontend or
  RTSP application endpoint is healthy. Use the HTTP and RTSP TCP tests for
  endpoint reachability.

## Quick Validation

Before a live walkthrough:

1. Open the public app through the router URL, not an internal load balancer.
2. Confirm the frontend runtime config exposes the public RTSP URL as the
   current router-backed hostname, for example
   `rtsp://44-208-125-119.sslip.io:8554/live`.
3. Confirm ThousandEyes HTTP tests target router-backed URLs.
4. Confirm ThousandEyes RTSP TCP targets the router host on `8554`.
5. Confirm collector egress from a private `otel` node returns
   `44.208.125.119` from `https://checkip.amazonaws.com`.
6. Confirm `kubectl` failures are not diagnosed as router failures unless the
   laptop public IP is allowlisted on the EKS API endpoint.
