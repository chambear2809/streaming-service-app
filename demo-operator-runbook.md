# Streaming Service App Demo Operator Runbook

This runbook turns the current repo into a repeatable booth demo. It is written around the real app routes, current load generators, the current ThousandEyes tests, and the numbered Splunk dashboards.

## Demo Surfaces

- Public viewer: `/broadcast`
- Protected operator suite: `/`
- Master Control: `/#operations`
- Incident Simulation Console: `/demo-monkey`
- Public trace pivot: `/api/v1/demo/public/trace-map`
- Public playback manifest: `/api/v1/demo/public/broadcast/live/index.m3u8`

## Observability References

ThousandEyes tests:

- `aleccham-broadcast-playback`
- `aleccham-broadcast-trace-map`
- `RTSP-TCP-8554`
- `UDP-Media-Path`
- `RTP-Stream-Proxy`

Splunk dashboard order:

1. `01 Start Here: Network Symptoms`
2. `02 Pivot: User Impact To Root Cause`
3. `03 Backend Critical Path`
4. `04 Deep Dive: Trace Map Path`
5. `05 Deep Dive: Broadcast Playback Path`
6. `06 Deep Dive: RTP Media Quality`

APM services to look for:

- `streaming-frontend`
- `media-service-demo`
- `user-service-demo`
- `content-service-demo`
- `billing-service`
- `ad-service-demo`

## Pre-Demo Checklist

1. Open `/broadcast` and confirm the public player loads, sponsor timing is present, and the channel is healthy.
2. Open `/` and confirm the launch overlay shows the public channel, sponsor pod, and incident posture.
3. Open `/#operations` as the operator persona and confirm Master Control, sponsor context, and service posture render cleanly.
4. Open `/demo-monkey` and confirm the current profile is `Clear` or `Simulation bypassed`. Check the **Preset** row in the detail card on the left side of the page (labeled "Simulation bypassed" when no fault is armed) — not the form toggle state, which is ephemeral.
5. On `/demo-monkey`, confirm the **"Next break"** row in the left detail panel shows a sponsor pod time within the next few minutes. If it reads "Waiting for schedule" or shows a time more than 10 minutes away, verify the broadcast is running and the backend ad schedule is responding before starting the walkthrough. The primary booth story depends on arming the fault close to a pod boundary.
6. In ThousandEyes, confirm the playback and trace-map HTTP tests are reporting.
7. In Splunk, confirm the numbered demo dashboard group exists and traces are arriving for `streaming-frontend` and `media-service-demo`.
7. If you plan to use `trace-map-outage`, `dependency-timeout`, or `service-specific-failure`, open `/demo-monkey` and click the **"Open ThousandEyes"** launch button before the walkthrough to confirm it opens the ThousandEyes dashboard and not a JSON endpoint.

> **Config check:** The button reads `observabilityLinks.thousandEyesUrl` from `config.js`. If that key is absent, the button falls back to `/api/v1/demo/public/trace-map`, which returns raw JSON. Open `frontend/config.js` and confirm `observabilityLinks.thousandEyesUrl` is set before running a trace-pivot scenario. Example:
> ```js
> observabilityLinks: {
>     thousandEyesUrl: "https://app.thousandeyes.com/...",
>     splunkApmUrl: "https://app.us1.signalfx.com/...",
>     splunkRumUrl: "https://app.us1.signalfx.com/..."
> }
> ```
> If the URL is not configured, navigate to ThousandEyes directly and skip the launch button for this walkthrough.

If ThousandEyes tests or dashboards are missing, use `docs/06-thousandeyes-rtsp-api.md` and `docs/03-distributed-tracing.md` before the booth cycle starts.

## Warm The Environment

Available profiles for both loadgen scripts:

| Profile | Broadcast viewers | Duration | Purpose |
| --- | --- | --- | --- |
| `warmup` | 25 | 6m | Light pre-check before the booth opens |
| `booth` | 90 | 10m | Standard demo load (35% page viewers, 10% trace-map sessions) |
| `stress` | 120 | 12m | Pressure test — do not use during a live walkthrough |
| `custom` | (from env) | (from env) | Manual override for any parameter |

Use `booth` for all standard walkthroughs. For the operator loadgen, `booth` uses 3 concurrent workers, 8-minute duration, 4-second pause between requests, and `TAKE_LIVE_RATIO=0.00`.

Viewer-side traffic:

```bash
LOADGEN_PROFILE=booth \
LOADGEN_K8S_MODE=cronjob \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

Protected operator, billing, and optional commerce traffic:

```bash
LOADGEN_OPERATOR_PROFILE=booth \
LOADGEN_OPERATOR_K8S_MODE=cronjob \
LOADGEN_OPERATOR_TAKE_LIVE_RATIO=0.00 \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Recommended live-demo posture:

- Use recurring `cronjob` mode when you want both warm-up flows active at the same time without juggling terminals.
- If you use one-shot `job` mode instead, launch the two helpers from separate terminals. Both scripts wait for completion in job mode and auto-delete completed jobs by default, so a single terminal will not keep the viewer and operator load side by side.
- Keep `LOADGEN_OPERATOR_TAKE_LIVE_RATIO=0.00` for the standard booth walkthrough so the protected loadgen does not unexpectedly route an RTSP contribution onto the public channel.

Status checks:

```bash
LOADGEN_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh

LOADGEN_OPERATOR_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Use the default booth profiles unless you are intentionally stress testing. For the booth walkthrough, the recommended warm-up settings are:

- Broadcast loadgen: `90` viewers, `10m`, `2m` ramp-up, `1m` ramp-down, `35%` page viewers, `10%` trace pivots
- Operator loadgen: `3` workers, `8m`, `4s` pause, `0%` auto-take-live during the default story

## Recommended Fault Order

Use the faults in this order during live demos:

1. `ad-break-delay`
   Best default. Strong business story, easy recovery, easy APM pivot.
2. `one-break-sponsor-miss`
   Safer hard-failure variant because it auto-clears after the next sponsor pod.
3. `sponsor-pod-miss`
   Persistent hard failure. Use only when you want the booth story to stay broken until you clear it.
4. `viewer-brownout` or `packet-loss`
   Use when you want a true ThousandEyes-first outside-in story.
5. `frontend-crash`
   Use when the audience specifically wants Browser RUM first.

## Default Live Sequence

### 1. Open On The Public Viewer

Start at `/broadcast`.

Call out:

- current channel
- service state
- sponsor pod
- ad mode
- direct stream URL

This is the viewer proof point. Do not skip it.

### 2. Step Into Master Control

Open `/`, use the operator persona, then land on `/#operations`.

Call out:

- same event, now from the control-room side
- queue and sponsor readiness
- service posture
- control surface for the live channel

### 3. Establish Background Traffic

State that:

- public viewer traffic is being generated by the broadcast loadgen
- protected operator, billing, and RTSP desk traffic is being generated by the operator loadgen
- if the legacy full backend slice is deployed, the same loadgen also warms live Accounts, Payments, and Commerce APIs; otherwise those protected pages stay in seeded preview mode

This makes the telemetry look active before you inject the fault.

### 4. Show ThousandEyes Healthy First

Use ThousandEyes before you arm the issue.

Open:

- `aleccham-broadcast-playback`
- `aleccham-broadcast-trace-map`

Only bring in `RTSP-TCP-8554`, `UDP-Media-Path`, or `RTP-Stream-Proxy` when the audience wants the media transport path.

### 5. Arm The Default Fault

Open `/demo-monkey` and apply `ad-break-delay`.

Why this is the booth default:

- the public viewer feels it
- Master Control can explain it
- Splunk APM can diagnose it
- the sponsor-revenue angle is obvious
- it is strongest when you arm it just ahead of the next sponsor pod instead of at a random point in the loop

### 6. Return To The App Immediately

Go back to `/broadcast` and `/#operations`.

Wait for:

- the next sponsor pod to be near. Use the sponsor-break timing on `/broadcast`, `/#operations`, or `/demo-monkey` so the audience does not sit through avoidable dead time.
- visible stall at the sponsor boundary
- sponsor pod timing that now looks unstable
- the same sponsor context in Master Control

### 7. Pivot Into Splunk

For the sponsor-delay story, open the Splunk dashboards in this order:

1. `02 Pivot: User Impact To Root Cause`
2. `03 Backend Critical Path`
3. `01 Start Here: Network Symptoms` when you want to reconnect the application finding to the ThousandEyes timeline

Then open the relevant APM trace.

For the sponsor-delay story, keep the presenter focused on:

- `streaming-frontend`
- `media-service-demo`
- `ad-service-demo`

### 8. Use Browser RUM Last

Open Browser RUM after APM to reconnect the backend diagnosis to the public viewer page.

This keeps the flow clean:

- app symptom
- ThousandEyes evidence
- Splunk root cause
- Browser confirmation

### 9. Close The Story

Use the same close every time:

- The viewer page showed the symptom.
- Master Control showed the same problem in operator terms.
- ThousandEyes showed where the experience degraded.
- Splunk showed why the application and sponsor workflow fell behind.

## Scenario Pivot Guide

| Scenario | Start Here | Application Proof | Splunk Landing Zone |
| --- | --- | --- | --- |
| `ad-break-delay` | `/broadcast` then `/#operations` | queued sponsor break stalls but still exists | dashboards `02` and `03`, then APM |
| `one-break-sponsor-miss` | `/broadcast` | one sponsor break misses, then auto-clears (the **Duration** card on `/demo-monkey` shows the scheduled `autoClearAt` timestamp) | dashboard `03`, Browser RUM, then dashboard `05` if playback detail matters |
| `sponsor-pod-miss` | `/broadcast` | sponsor clip fails at the break boundary | dashboard `03`, APM, Browser RUM |
| `playback-outage` | `/broadcast` | public HLS player cannot start or stalls completely; player reconnect loop is visible | dashboard `05`, then `01` for network context |
| `viewer-startup-spike`, `viewer-brownout`, `packet-loss` | ThousandEyes | public stream starts slowly or drops | dashboard `01`, then `02` |
| `dependency-timeout`, `service-specific-failure`, `trace-map-outage` | `/api/v1/demo/public/trace-map` and ThousandEyes | public trace pivot degrades without taking down everything else | dashboard `04`, then APM service map |
| `frontend-crash` | Browser RUM | viewer page throws a client-side exception | Browser RUM first, APM second if needed |

## Reset

1. Open `/demo-monkey` and apply `Clear`.
2. If a contribution feed was taken live, return the channel to the house loop from Master Control or reset the broadcast route before the next walkthrough.
3. Confirm `/broadcast` is healthy again and sponsor timing has normalized.
4. Let one-shot loadgen jobs finish naturally, or remove them if you are resetting the booth early.

Delete the active loadgen jobs:

```bash
LOADGEN_K8S_ACTION=delete \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh

LOADGEN_OPERATOR_K8S_ACTION=delete \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

## When To Use The RTSP Deep Dive

Only use the RTSP story when the audience asks for contribution ingest, transport, or media quality specifically.

Then show:

- RTSP job creation in the protected suite
- the contribution taken live into the public channel
- ThousandEyes `RTSP-TCP-8554`, `UDP-Media-Path`, and `RTP-Stream-Proxy`
- Splunk dashboards `05` and `06`

This keeps the default booth story simple while preserving a credible engineering deep dive when needed.
