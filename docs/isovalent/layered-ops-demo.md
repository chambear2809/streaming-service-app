# Layered Isovalent Ops Demo

This is a standalone walkthrough for using the existing `streaming-service-app`
demo with the Isovalent-backed Splunk surfaces that are already present in the
cluster.

Hard prerequisite:

- Isovalent must be deployed in the same Kubernetes cluster as the demo app.
- The app must actually be running there and generating traffic.
- If Hubble, Cilium, and Tetragon are not deployed in that cluster, the
  built-in Isovalent groups and the custom `07` dashboard will stay empty.

## Goal

Use one incident and one application flow to satisfy three audiences at once:

- exec: visible viewer and sponsor impact
- platform or SRE: service path, L7 behavior, and dependency timing
- security: runtime-aware network telemetry and workload-to-workload context

The default story is still `ad-break-delay`. The technical backup is
`trace-map-outage`.

## What This Uses

Application surfaces:

- `/broadcast`
- `/`
- `/#operations`
- `/demo-monkey`
- `/api/v1/demo/public/trace-map`

Repo dashboards and telemetry:

- `01 Start Here: Network Symptoms`
- `02 Pivot: User Impact To Root Cause`
- `03 Backend Critical Path`
- `04 Deep Dive: Trace Map Path`
- `07 Explain: Are Media Services Still Talking?`
- Browser RUM and APM for `streaming-frontend`, `media-service-demo`,
  `user-service-demo`, `content-service-demo`, `billing-service`, and
  `ad-service-demo`

Isovalent-backed Splunk groups:

- `Hubble by Isovalent`
  Use `Network Overview` and `L7 HTTP Metrics` first.
- `Cilium by Isovalent`
  Use `High-Level Health` first. Keep policy-oriented dashboards secondary in
  the default story.
- `Network explorer tetragon`
  Use `HTTP overview` first, then outbound protocol views when the room wants
  more detail.

## Preflight

Run the verifier before a live walkthrough:

```bash
python3 docs/isovalent/verify_splunk_assets.py
```

Confirm the recurring viewer and operator warm-up jobs still exist:

```bash
LOADGEN_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh

LOADGEN_OPERATOR_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Confirm the app is healthy before you touch the fault:

- `/broadcast` loads cleanly
- `/#operations` shows sponsor and service posture
- `/demo-monkey` is `Clear` or `Simulation bypassed`
- ThousandEyes playback and trace-map tests are live
- Isovalent telemetry is present for the same deployed cluster if you expect
  `07 Explain: Are Media Services Still Talking?` to populate

## Primary Story

### 1. Open On The Viewer

Start at `/broadcast`.

Call out:

- current channel
- sponsor pod timing
- service state
- direct playback URL

This is the business-impact anchor.

### 2. Step Into The Control Room

Move to `/#operations`.

Call out:

- same event, now from the operator view
- sponsor readiness
- queue posture
- service posture

This proves the incident is real on both the public and protected surfaces.

### 3. Show The Outside-In Baseline

Open the ThousandEyes HTTP tests first:

- `aleccham-broadcast-playback`
- `aleccham-broadcast-trace-map`

Keep this short. The point is to establish that the room saw the healthy state
before the fault was armed.

### 4. Arm The Fault

Use `/demo-monkey` and apply `ad-break-delay` just ahead of the next sponsor
boundary.

This is still the default because it creates a believable viewer symptom,
operator symptom, and business-impact story without claiming a fake network
outage.

### 5. Pivot Into Splunk

Use this order for the core walkthrough:

1. `02 Pivot: User Impact To Root Cause`
2. `03 Backend Critical Path`
3. `07 Explain: Are Media Services Still Talking?`
4. `Hubble by Isovalent / Network Overview`
5. `Hubble by Isovalent / L7 HTTP Metrics`
6. `Network explorer tetragon / HTTP overview`
7. `Cilium by Isovalent / High-Level Health`

If the room asks for a little more protocol depth:

- `Network explorer tetragon / HTTP - Outbound`
- `Network explorer tetragon / TCP overview`
- `Hubble by Isovalent / DNS Overview`

### How To Read The Isovalent Dashboards

This section is written for rooms that do not speak networking every day.

Use these translations consistently:

- flow: one part of the system talking to another
- verdict: whether that conversation was allowed or blocked
- latency: how long the system had to wait for the answer
- DNS: how one service finds another by name
- retransmit: the system had to resend data because the first attempt did not
  land cleanly

The safest framing for this demo is:

- the Isovalent views tell you whether the services are still able to find and
  talk to each other
- the APM views tell you why the application workflow fell behind

That keeps the room from confusing a slow sponsor handoff with a total network
outage.

#### Custom 07: Explain: Are Media Services Still Talking?

Use this custom dashboard right after `03 Backend Critical Path`.

It is the simplest bridge between the application story and the Isovalent
story because every chart is written as a question a non-networking audience
can answer.

Important panels in the custom dashboard:

- `Are media and ad services still receiving replies?`
- `Are 2xx replies still dominant?`
- `Is the frontend still sending viewer traffic out?`
- `Is media-service still feeding the frontend at stream rate?`
- `Is the external RTSP feed still arriving?`
- `Which workload pair is carrying the media path?`
- `Are 5xx responses climbing for media and ad services?`
- `Are 4xx responses climbing instead?`
- `Is anything being blocked instead of slowed?`

What these mean in plain language:

- receiving requests
  The application is still exchanging replies on the media and sponsor path.
- `2xx`
  The request completed successfully.
- viewer traffic out
  Whether the frontend is still pushing stream data outward to viewers.
- media feeding the frontend
  Whether the media service is still supplying stream data into the frontend tier.
- external RTSP feed
  Whether the contribution stream is still entering `media-service-demo` from
  outside the cluster.
- workload pair
  Which application component is talking to which other component most often.
- `5xx`
  The service failed while trying to process the request.
- `4xx`
  The request was rejected or malformed rather than failing inside the service.
- blocked
  Traffic was denied, which is a different story from simple delay.

What healthy looks like:

- requests keep arriving at `media-service-demo` and `ad-service-demo`
- `2xx` remains the dominant class
- the frontend egress chart stays visibly active
- the media-to-frontend chart stays active and roughly tracks the frontend egress story
- the external RTSP chart stays active whenever an outside contribution source
  is publishing into the demo
- the workload-pair table is led by the expected streaming path rather than
  random background traffic
- there are few or no `5xx` spikes
- `4xx` stays comparatively low
- the blocked chart remains dominated by allowed traffic

What the default sponsor-delay story should look like:

- request volume stays present
- `2xx` may still dominate at first
- frontend egress may dip if viewers stop receiving usable stream data
- media-to-frontend throughput helps you separate a frontend-delivery issue
  from a media-generation issue
- external RTSP throughput helps you prove whether the contribution feed is
  still reaching the cluster before the rest of the playback chain is considered
- the workload-pair table shows which service handoff is carrying the incident
- `5xx` may rise after the delay becomes visible
- `4xx` helps you separate caller-side rejection from server-side failure
- the verdict chart should usually stay mostly allowed, which helps you say
  this is delay rather than enforcement

What to say:

`This dashboard answers the room's main question in plain English. Are the
services still talking? Usually yes. Are they still mostly succeeding? Also
yes. Is the frontend still pushing stream data out? Is media-service still
feeding the frontend? Is the external RTSP contribution still arriving? Those
bandwidth views tell us whether the feed enters the cluster, crosses the media
tier, and leaves toward viewers, while the reply mix and workload pair show
where the sponsor handoff is straining.`

#### Hubble: Network Overview

Start here when you want the simplest picture of service-to-service activity.

Important panels in the live dashboard:

- `Flows processed by type`
- `Flows processed by verdict`
- `Top 10 sources`
- `Top 10 destinations`
- `Missing TCP SYN-ACKs`
- `Missing ICMP Echo Replys`
- `Network Policy drops by source`
- `Network Policy drops by destination`

What these mean in plain language:

- `Flows processed by type`
  This is the mix of conversations moving through the cluster. For a
  non-network audience, describe it as the volume of system conversations.
- `Flows processed by verdict`
  This tells you whether the traffic is mostly being allowed or blocked. In the
  default sponsor-delay story, this should mostly stay in the allowed state.
- `Top 10 sources` and `Top 10 destinations`
  These are the busiest talkers and listeners. In this demo, you should expect
  the busy names to line up with the streaming application rather than random
  infrastructure noise.
- `Missing TCP SYN-ACKs`
  One service knocked on the door and the other side did not answer the initial
  connection handshake.
- `Missing ICMP Echo Replys`
  Basic reachability checks are not getting an answer back.
- `Network Policy drops by source` and `Network Policy drops by destination`
  Traffic is being deliberately blocked by policy, not just slowed down.

What healthy looks like:

- conversation volume is steady
- allowed traffic dominates
- the busiest sources and destinations are the expected app services
- handshake misses and policy drops are low or flat

What the default sponsor-delay story should look like:

- conversations continue
- allowed traffic still dominates
- you do not suddenly see the system go dark
- this supports the story that the platform is still connected and the real
  problem is that part of the application workflow is taking too long

What to say:

`This screen answers a simple question: are the parts of the application still
finding and talking to each other? In the sponsor-delay story, the answer is
usually yes. The conversation is still happening, but the handoff inside the
application is slower than it should be.`

#### Hubble: L7 HTTP Metrics

Use this when you want to explain the application-facing behavior of those
service conversations.

Important panels in the live dashboard:

- `Incoming Request Volume`
- `Incoming Request Success Rate (non-5xx responses)`
- `Request Duration - (p50, p95,p99)`
- `Incoming Requests by Source & Response Code`
- `Incoming Request Success Rate (non-5xx responses) By Source`
- `HTTP Request Duration by Source (P50, P95, P99)`
- `Incoming Requests by Destination and Response Code`
- `Incoming Request Success Rate (non-5xx responses) By Destination`
- `HTTP Request Duration by Destination (P50, P95, P99)`
- `CPU Usage`
- `CPU Usage by source`

What these mean in plain language:

- request volume
  How many application requests are arriving.
- success rate
  How often the services are replying without a server-side failure.
- request duration
  How long the response took, including slow but still successful work.
- by source or by destination
  Which caller or callee is associated with the change.
- CPU usage
  Whether the system is also spending more compute time while the slowdown is
  happening.

What healthy looks like:

- request volume is steady
- success rate stays high
- response time bands stay relatively tight
- no one source or destination suddenly becomes an obvious outlier

What the default sponsor-delay story should look like:

- request volume can stay normal because viewers are still arriving
- success rate may stay mostly healthy at first
- request duration is the panel most likely to move first
- the slowdown should line up with the services involved in the sponsor path,
  especially around `media-service-demo` and `ad-service-demo`

What to say:

`This is where we stop talking about pipes and start talking about wait time.
The key point for this audience is that the requests are still arriving, but
the system is taking longer to complete the sponsor handoff. Slow is different
from down, and this dashboard helps us prove that difference.`

#### Cilium: High-Level Health

Use this as the health check for the traffic-control layer itself.

Important panels in the live dashboard:

- `Cilium Pod Running State`
- `DNS Proxy running`
- `Agent Running`
- `Operator Running`
- `Agent not running`
- `Operator not Running`
- `DNS Proxy not running`
- `Cilium Agent Restarts`
- `Cilium Operator restarts`
- `Cilium Envoy restarts`
- `Client Agent Error Rate`

What these mean in plain language:

- running-state panels
  Are the components that steer and observe traffic alive.
- not-running panels
  Are any of those components currently missing.
- restart panels
  Are those components repeatedly falling over and restarting.
- client agent error rate
  Is the traffic-control layer itself generating more errors than normal.

What healthy looks like:

- running panels stay healthy
- not-running panels stay low
- restarts stay flat
- error rate stays low

What the default sponsor-delay story should look like:

- this dashboard should usually stay boring
- that is good news
- if this layer is stable while the application-facing dashboards show slower
  requests, you can confidently tell the room the platform fabric stayed
  healthy and the incident is more specific than a cluster-wide network issue

What to say:

`Think of this as the health dashboard for the traffic-control system itself.
If this stays steady while the application charts get slower, that is a strong
signal that the cluster fabric is healthy and the problem lives higher up in
the workflow.`

#### Network Explorer: HTTP Overview

Use this when you want a simple runtime-oriented view of request outcomes.

Important panels in the live dashboard:

- `# HTTP responses`
- `# HTTP response 2xx`
- `# HTTP response 4xx`
- `# HTTP response 5xx`
- `Mean response latency (ms)`

What these mean in plain language:

- total responses
  How much HTTP conversation is happening overall.
- `2xx`
  Successful replies.
- `4xx`
  The request was rejected or malformed from the caller side.
- `5xx`
  The service itself failed while trying to handle the request.
- mean response latency
  The average wait time before the reply came back.

What healthy looks like:

- most replies are `2xx`
- `4xx` and `5xx` remain comparatively low
- average latency stays stable

What the default sponsor-delay story should look like:

- latency is likely to rise before failure counts rise
- that gives you a simple sentence for the audience: the system got slower
  before it got broken
- if `5xx` also climbs, the room can see the incident move from slowdown into
  outright failure

What to say:

`This is the easiest Isovalent screen for a non-network audience. Green replies
mean the system answered successfully. Rising latency means the audience had to
wait longer. Rising 5xx means the service stopped coping and started failing.`

#### Backup Screens: DNS And TCP

Keep these as secondary pivots, not the opening move.

`Hubble by Isovalent / DNS Overview` includes:

- `DNS Queries`
- `Top 10 DNS Queries`
- `Missing DNS Responses`
- `DNS Errors`

`Network explorer tetragon / DNS overview` includes:

- `DNS responses`
- `DNS errors`
- `DNS misses / evictions`

What to say:

`DNS is how services find each other by name. If DNS errors spike, the services
may know what work they need to do but not where to send it.`

`Network explorer tetragon / TCP overview` includes:

- `TCP traffic (bytes)`
- `TCP traffic growth (%)`
- `Mean RTT`
- `# Retransmit Segments`
- `TCP Errors`

What to say:

`TCP is the basic delivery layer for many service conversations. If mean RTT
climbs, the round trip is taking longer. If retransmits climb, the system is
having to resend data. If those stay calm while HTTP latency rises, the room
should think application slowdown first, not raw transport failure.`

#### Simple Interpretation Guide

Use these shortcuts live:

- `07` shows requests and success staying present while wait time rises:
  the services are still talking, but the handoff is slowing down.
- Hubble conversation volume is steady, but HTTP duration rises:
  services are still talking, but the workflow is slower.
- Cilium health stays stable while Hubble and APM show delay:
  the cluster traffic fabric is healthy; the incident is in the application
  path.
- HTTP latency rises before `5xx`:
  the system is slowing down before it starts failing.
- DNS errors rise:
  services may be struggling to find the right destination.
- policy drops rise:
  traffic is being blocked intentionally, which is a different story from
  overload or delay.

### 6. Close By Audience Layer

Use the same screens, but tighten the narration to the audience in front of
you.

Exec layer:

- the viewer felt the sponsor break slip
- the operator saw the same break window destabilize
- the platform stayed up, but the workflow degraded at a revenue-sensitive
  moment

Platform or SRE layer:

- `streaming-frontend` and `media-service-demo` are the operational center of
  the story
- Hubble gives the service-to-service and L7 pivot
- Network Explorer gives a protocol-oriented dependency view without abandoning
  the application narrative

Security layer:

- the same incident can be explained with runtime-aware network telemetry
- Tetragon-backed Network Explorer shows workload and protocol context at the
  kernel-observed network layer
- you already have the security-adjacent observability foundation in place,
  even though this default demo is not an enforcement demo

## Technical Backup Story

Use `trace-map-outage` when the room wants a more explicit request-path and
dependency story.

Sequence:

1. open `/api/v1/demo/public/trace-map`
2. show the ThousandEyes trace-map HTTP test
3. move to `07 Explain: Are Media Services Still Talking?`
4. move to `Hubble by Isovalent / L7 HTTP Metrics`
5. move to `Hubble by Isovalent / DNS Overview` if name-resolution or service
   lookup becomes part of the discussion
6. move to `Network explorer tetragon / HTTP overview`
7. finish in APM service map and `04 Deep Dive: Trace Map Path`

This backup is the cleanest path when the audience wants the network and
dependency topology to be more explicit than the sponsor-delay story naturally
provides.

## Guardrails

- Do not make `Policy Verdicts` the hero screen in the default story. The
  `streaming-service-app` namespace does not currently have dedicated Cilium or
  Isovalent policy objects in the demo flow, so that dashboard is secondary.
- Keep RTSP, RTP, and transport-quality screens after the main story unless the
  room is specifically asking for contribution ingest or media-path detail.
- If the room wants enforcement, treat that as a v2 extension: add a
  rollback-safe namespace-scoped Cilium policy just for the streaming demo and
  then bring `Policy Verdicts` forward.

## Expected Signal Families

These are the metric families the current collector configuration already
scrapes and forwards, and they explain why the dashboards above are the right
ones to preload:

- Hubble: `hubble_http_requests_total`,
  `hubble_http_request_duration_seconds_bucket`,
  `hubble_flows_processed_total`, `hubble_drop_total`,
  `hubble_policy_verdicts_total`
- Cilium: `cilium_policy_l7_total`, `cilium_bpf_map_ops_total`,
  `cilium_endpoint_state`, `cilium_errors_warnings_total`
- Tetragon: `tetragon_socket_stats_txbytes_total`,
  `tetragon_http_response_total`, `tetragon_dns_total`

## References

- Isovalent Observability docs:
  `https://docsnext.isovalent.com/solution/observability.html`
- Tetragon overview:
  `https://tetragon.io/docs/overview/`
- Splunk Network Explorer docs:
  `https://help.splunk.com/en/splunk-observability-cloud/monitor-infrastructure/network-explorer`
