# Broadcast Load Generator

The broadcast load generator simulates viewer sessions against the public demo surface instead of hammering one URL.

It exercises:

- `/broadcast` plus same-origin page assets for a configurable slice of sessions
- `/api/v1/demo/public/broadcast/current` on the same 5-second cadence used by the browser page
- `/api/v1/demo/public/broadcast/live/index.m3u8` and downstream HLS variant playlists
- transport stream segment fetches for each viewer session
- `/api/v1/demo/public/trace-map` for a configurable subset of sessions that pivot into the network symptom view

## Local Run

```bash
node scripts/loadgen/broadcast-loadgen.mjs \
  --base-url http://127.0.0.1:8080 \
  --target-viewers 40 \
  --duration 12m
```

## In-Cluster Run

```bash
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

The deploy helper mounts the Node script into a `node:22-alpine` job pod and points it at `streaming-frontend.<namespace>.svc.cluster.local` by default.

To keep the load generator running on a regular cadence inside the cluster, deploy it as a `CronJob` instead of a one-shot `Job`:

```bash
LOADGEN_K8S_MODE=cronjob \
LOADGEN_CRON_SCHEDULE='*/15 * * * *' \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

The recurring mode defaults to `Forbid` concurrency so a new run does not overlap an older one when the booth profile is still active.

## Recommended Profiles

- `Warm-up`: `25` viewers, `6m` duration. Use this when you want low-risk background traffic before a demo starts.
- `Booth default`: `90` viewers, `10m` duration, `2m` ramp-up, `1m` ramp-down, `35%` page viewers, `10%` trace pivots. This is the safer profile for live demos after the `120/12m` run proved destructive in-cluster.
- `Stress`: `120` viewers, `12m` duration, `3m` ramp-up, `1m` ramp-down, `35%` page viewers, `10%` trace pivots. Use this to pressure-test the broadcast path, not as the default booth run.

The deploy helper now defaults to `LOADGEN_PROFILE=booth`. Set `LOADGEN_PROFILE=warmup`, `booth`, `stress`, or `custom` depending on the outcome you want. When you choose `custom`, the explicit `LOADGEN_*` variables win and no preset is applied.

## Operator Actions

Use `LOADGEN_K8S_ACTION` to manage the recurring load without editing manifests:

- `apply`: create or update the one-shot `Job` or recurring `CronJob`
- `status`: show the current `Job` or `CronJob` plus matching jobs and pods
- `delete`: remove the current `Job` or `CronJob`
- `pause`: set `spec.suspend=true` on the recurring `CronJob`
- `resume`: set `spec.suspend=false` on the recurring `CronJob`
- `trigger`: create a one-off `Job` immediately from the current `CronJob` template

Important environment variables:

- `NAMESPACE` or `STREAMING_K8S_NAMESPACE`
- `LOADGEN_PROFILE`
- `LOADGEN_K8S_MODE`
- `LOADGEN_K8S_ACTION`
- `LOADGEN_CRONJOB_NAME`
- `LOADGEN_CRON_SCHEDULE`
- `LOADGEN_CRON_CONCURRENCY_POLICY`
- `LOADGEN_CRON_SUSPEND`
- `LOADGEN_CRON_SUCCESS_HISTORY`
- `LOADGEN_CRON_FAILED_HISTORY`
- `LOADGEN_TRIGGER_JOB_NAME`
- `LOADGEN_BASE_URL`
- `LOADGEN_TARGET_VIEWERS`
- `LOADGEN_DURATION`
- `LOADGEN_RAMP_UP`
- `LOADGEN_RAMP_DOWN`
- `LOADGEN_SESSION_MIN`
- `LOADGEN_SESSION_MAX`
- `LOADGEN_PAGE_VIEWER_RATIO`
- `LOADGEN_TRACE_MAP_SESSION_RATIO`
- `LOADGEN_LIVE_EDGE_SEGMENTS`
- `LOADGEN_LIVE_EDGE_PARTS`
- `LOADGEN_MAX_PLAYBACK_ERRORS`
- `LOADGEN_CPU_REQUEST`
- `LOADGEN_CPU_LIMIT`
- `LOADGEN_MEMORY_REQUEST`
- `LOADGEN_MEMORY_LIMIT`

Booth-default example:

```bash
LOADGEN_PROFILE=booth \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

Stress example:

```bash
LOADGEN_PROFILE=stress \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

Recurring booth-default example:

```bash
LOADGEN_PROFILE=booth \
LOADGEN_K8S_MODE=cronjob \
LOADGEN_CRON_SCHEDULE='*/15 * * * *' \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

Inspect the recurring load:

```bash
LOADGEN_K8S_MODE=cronjob \
LOADGEN_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

Pause and resume the recurring load:

```bash
LOADGEN_K8S_MODE=cronjob \
LOADGEN_K8S_ACTION=pause \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh

LOADGEN_K8S_MODE=cronjob \
LOADGEN_K8S_ACTION=resume \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

Trigger the recurring template immediately:

```bash
LOADGEN_K8S_MODE=cronjob \
LOADGEN_K8S_ACTION=trigger \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh
```

## Output

The script prints rolling progress and a final summary with:

- started, completed, failed, and peak active sessions
- total requests, bytes transferred, and average requests per second
- overall request latency percentiles
- startup latency percentiles
- per-endpoint breakdown for page, asset, status, trace, playlist, and segment traffic

## Live Feed Notes

The public broadcast path is low-latency HLS, not a static VOD playlist. The load generator now follows the live edge by fetching:

- the selected video playlist and matching audio playlist when the master manifest exposes one
- the init segment when the media playlist advertises `#EXT-X-MAP`
- a small tail of full segments plus recent `#EXT-X-PART` assets instead of replaying the whole live window
- transient playback misses with retry tolerance, so a single `404` on an old live-edge asset does not kill the viewer session immediately
