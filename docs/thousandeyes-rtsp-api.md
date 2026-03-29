# ThousandEyes Test API Setup

This repo includes [`scripts/thousandeyes/create-rtsp-tests.sh`](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/scripts/thousandeyes/create-rtsp-tests.sh) to create the ThousandEyes tests used by this demo and [`scripts/thousandeyes/create-demo-dashboards.py`](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/scripts/thousandeyes/create-demo-dashboards.py) to sync the matching Splunk demo dashboards.

The test-creation script creates these demo tests:

- `RTSP-TCP-8554`: agent-to-server `TCP/8554` reachability and path test for the in-cluster demo path
- `UDP-Media-Path`: agent-to-agent UDP transport proxy for the media path
- `RTP-Stream-Proxy`: scheduled voice test for RTP-style loss, jitter, and delay visibility
- `aleccham-broadcast-trace-map`: HTTP server test for `/api/v1/demo/public/trace-map`
- `aleccham-broadcast-playback`: HTTP server test for `/api/v1/demo/public/broadcast/live/index.m3u8`

It uses the ThousandEyes `v7` scheduled test APIs:

- `POST /tests/agent-to-server`
- `POST /tests/agent-to-agent`
- `POST /tests/voice`
- `POST /tests/http-server`

Both the local script and the Kubernetes wrapper automatically read the repo-root `.env` file by default. Use `ENV_FILE=/path/to/file` if you want a different env file. Explicitly exported shell variables override `.env` values.

Choose the ThousandEyes target mode before you build payloads:

- `local`: use cluster-private or same-network endpoints that your Enterprise Agents can reach, such as `streaming-frontend.<namespace>.svc.cluster.local`
- `external`: use browser-facing or internet-reachable frontend and RTSP endpoints that ThousandEyes agents outside the cluster can reach

## Prerequisites

- A ThousandEyes bearer token with API access
- The `agentId` values for your source Enterprise Agents and target Enterprise Agent
- The account group ID if you want to create the tests in a non-default account group

## Before You Run

Set the required values in the repo-root `.env` file or export them in your shell before you create tests.

Required user inputs:

- `THOUSANDEYES_BEARER_TOKEN`: paste your ThousandEyes bearer token here
- `THOUSANDEYES_ACCOUNT_GROUP_ID`: choose the org or account-group ID returned by `scripts/thousandeyes/create-rtsp-tests.sh list-orgs`
- `TE_SOURCE_AGENT_IDS`: set a comma-separated list of source Enterprise Agent IDs from `scripts/thousandeyes/create-rtsp-tests.sh list-agents`
- `TE_TARGET_AGENT_ID`: set the target Enterprise Agent or Cloud Agent ID from `scripts/thousandeyes/create-rtsp-tests.sh list-agents`
- `TE_UDP_TARGET_AGENT_ID`: optional override when the UDP media-path test should target a different agent than the RTP proxy test

Sometimes required:

- `TE_RTSP_SERVER`: set this if you are running the direct API flow or if the Kubernetes wrapper cannot discover the RTSP hostname automatically
- `TE_RTSP_PORT`: override this if your RTSP service is not using the default port expected by your chosen flow
- `TE_DEMO_MONKEY_FRONTEND_BASE_URL`: set this to the public frontend URL in `external` mode instead of leaving the default `svc.cluster.local` value in place
- `TE_TRACE_MAP_TEST_URL`: override this directly when the trace-map test must hit a specific external URL
- `TE_BROADCAST_TEST_URL`: override this directly when the broadcast test must hit a specific external URL

If you are guiding another user through this flow, explicitly ask them for missing token values or object IDs before calling the API. Do not guess bearer tokens, account-group IDs, or agent IDs.

If you are also going to build the Splunk demo dashboards after the tests are live, ask for the Splunk variable names explicitly too. Do not ask for a generic Splunk token bundle. Use the exact names `SPLUNK_REALM`, `SPLUNK_ACCESS_TOKEN`, `SPLUNK_RUM_APP_NAME`, `SPLUNK_DEPLOYMENT_ENVIRONMENT`, and, when needed, `SPLUNK_DEMO_DASHBOARD_GROUP_ID` and `STREAMING_K8S_NAMESPACE`.

## Discover IDs

In this repo, the ThousandEyes "org" the user wants to configure maps to an account group ID.

List orgs or account groups:

```bash
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
scripts/thousandeyes/create-rtsp-tests.sh list-orgs
```

List agents:

```bash
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
scripts/thousandeyes/create-rtsp-tests.sh list-agents
```

List account groups:

```bash
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
scripts/thousandeyes/create-rtsp-tests.sh list-account-groups
```

## Dry Run First

This prints the JSON payloads without calling the API:

```bash
export THOUSANDEYES_DRY_RUN=true
export THOUSANDEYES_BEARER_TOKEN='<bearer-token>'
export THOUSANDEYES_ACCOUNT_GROUP_ID='1234'
export TE_SOURCE_AGENT_IDS='111,222'
export TE_TARGET_AGENT_ID='333'
export TE_UDP_TARGET_AGENT_ID='3'
export TE_RTSP_SERVER='rtsp.example.com'
export TE_RTSP_PORT='8554'
export TE_DEMO_MONKEY_FRONTEND_BASE_URL='https://demo.example.com'
export TE_TRACE_MAP_TEST_URL="${TE_DEMO_MONKEY_FRONTEND_BASE_URL}/api/v1/demo/public/trace-map"
export TE_BROADCAST_TEST_URL="${TE_DEMO_MONKEY_FRONTEND_BASE_URL}/api/v1/demo/public/broadcast/live/index.m3u8"
export TE_A2A_PORT='5004'
export TE_A2A_THROUGHPUT_MEASUREMENTS='false'
export TE_A2A_THROUGHPUT_RATE_MBPS='12'
export TE_VOICE_PORT='49152'
export TE_VOICE_CODEC_ID='0'

scripts/thousandeyes/create-rtsp-tests.sh create-all
```

## Create the Tests

Once the payloads look right:

```bash
export THOUSANDEYES_DRY_RUN=false
scripts/thousandeyes/create-rtsp-tests.sh create-all
```

Or create them one at a time:

```bash
scripts/thousandeyes/create-rtsp-tests.sh create-rtsp-tcp
scripts/thousandeyes/create-rtsp-tests.sh create-udp-media
scripts/thousandeyes/create-rtsp-tests.sh create-rtp-stream
scripts/thousandeyes/create-rtsp-tests.sh create-demo-monkey-trace-map
scripts/thousandeyes/create-rtsp-tests.sh create-demo-monkey-broadcast
scripts/thousandeyes/create-rtsp-tests.sh create-demo-monkey-http
```

## Run From Kubernetes

This repo also includes an in-cluster path that creates the tests from a one-shot Kubernetes Job:

- [`scripts/thousandeyes/deploy-k8s-rtsp-tests.sh`](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/scripts/thousandeyes/deploy-k8s-rtsp-tests.sh)
- [`scripts/thousandeyes/create-rtsp-tests-in-cluster.sh`](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/scripts/thousandeyes/create-rtsp-tests-in-cluster.sh)

The deploy wrapper:

- discovers the external hostname and port from `media-service-demo-rtsp`
- derives the in-cluster frontend base URL from `streaming-frontend` in `local` mode unless you override it
- creates a Secret containing `THOUSANDEYES_BEARER_TOKEN`
- mounts the in-cluster creator script through a ConfigMap
- launches a one-shot Job that calls the ThousandEyes `v7` test APIs

Example:

```bash
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

If you want to inspect the rendered Secret, ConfigMap, and Job without applying them:

```bash
export K8S_DRY_RUN=true
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

If the service hostname is not ready yet, override it manually:

```bash
export TE_RTSP_SERVER='rtsp.example.com'
export TE_RTSP_PORT='8554'
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

To create only the Demo Monkey-sensitive HTTP tests from the cluster:

```bash
export THOUSANDEYES_JOB_ACTION='create-demo-monkey-http'
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

To force the Kubernetes wrapper to create tests against external endpoints instead of cluster-local defaults:

```bash
export TE_RTSP_SERVER='rtsp.example.com'
export TE_RTSP_PORT='8554'
export TE_DEMO_MONKEY_FRONTEND_BASE_URL='https://demo.example.com'
export TE_TRACE_MAP_TEST_URL="${TE_DEMO_MONKEY_FRONTEND_BASE_URL}/api/v1/demo/public/trace-map"
export TE_BROADCAST_TEST_URL="${TE_DEMO_MONKEY_FRONTEND_BASE_URL}/api/v1/demo/public/broadcast/live/index.m3u8"
scripts/thousandeyes/deploy-k8s-rtsp-tests.sh
```

## Build The Splunk Demo Dashboards

Once the tests are live, use [`scripts/thousandeyes/create-demo-dashboards.py`](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/scripts/thousandeyes/create-demo-dashboards.py) to create or update the Splunk Observability dashboard group that supports the demo story.

The script keeps the dashboards ordered for the walkthrough:

- `01 Start Here: Network Symptoms`
- `02 Pivot: User Impact To Root Cause`
- `03 Backend Critical Path`
- `04 Deep Dive: Trace Map Path`
- `05 Deep Dive: Broadcast Playback Path`
- `06 Deep Dive: RTP Media Quality`

Before you call the Splunk API, make sure the repo-root `.env` or your current shell defines:

- `SPLUNK_REALM`
- `SPLUNK_ACCESS_TOKEN`
- `SPLUNK_VALIDATION_TOKEN` when your dashboard-write token cannot read metric data but you still want the live ThousandEyes metric validation step
- `SPLUNK_RUM_APP_NAME`
- `SPLUNK_DEPLOYMENT_ENVIRONMENT`
- `STREAMING_K8S_NAMESPACE` when the demo is not deployed in `streaming-service-app`
- `SPLUNK_DEMO_DASHBOARD_GROUP_ID` when you want to update a specific existing dashboard group instead of matching or creating one automatically
- `TE_RTSP_TCP_TEST_NAME`, `TE_UDP_MEDIA_TEST_NAME`, `TE_RTP_STREAM_TEST_NAME`, `TE_TRACE_MAP_TEST_NAME`, and `TE_BROADCAST_TEST_NAME` when you created the ThousandEyes tests with non-default names
- `TE_RTSP_TCP_TEST_ID`, `TE_UDP_MEDIA_TEST_ID`, `TE_RTP_STREAM_TEST_ID`, `TE_TRACE_MAP_TEST_ID`, and `TE_BROADCAST_TEST_ID` when duplicate ThousandEyes test names exist and you need to pin the exact dashboard test ID

If you are guiding another user, stop and ask for those exact variable names before calling the Splunk API.

Use the default flow when you want the script to match or create the group automatically:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py
```

Update a specific existing group:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py \
  --group-id "$SPLUNK_DEMO_DASHBOARD_GROUP_ID"
```

Point the Kubernetes CPU chart at a non-default namespace:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py \
  --namespace streaming-demo
```

The dashboard sync script only creates detail dashboards for repo ThousandEyes tests that actually exist in the selected account group. It skips missing tests instead of creating empty placeholders.

The in-cluster test flow and the direct API helper now both default to `RTSP-TCP-8554`. The dashboard sync script still accepts either RTSP test name so older demos continue to bind to the right control-path test.

The dashboard sync script also validates that the ThousandEyes metric series behind the dashboard charts are present in Splunk for the selected test IDs. If your dashboard-write token cannot read metric data, set `SPLUNK_VALIDATION_TOKEN` or pass `--skip-te-metric-validation`.

## Key Environment Variables

Where to configure them:

- Edit the repo-root `.env`
- Or export the variables in your shell for the current terminal session

Shared:

- `THOUSANDEYES_BEARER_TOKEN`
- `THOUSANDEYES_ACCOUNT_GROUP_ID`
- `TE_SOURCE_AGENT_IDS`
- `TE_TARGET_AGENT_ID`
- `TE_UDP_TARGET_AGENT_ID` when the UDP media-path test should target a different agent than the RTP proxy test
- `TE_DSCP_ID`

When the user only knows the ThousandEyes org name, run `scripts/thousandeyes/create-rtsp-tests.sh list-orgs`, match the returned name to the desired org, and set the corresponding ID in `THOUSANDEYES_ACCOUNT_GROUP_ID`.

RTSP control-path test:

- `TE_RTSP_SERVER`
- `TE_RTSP_PORT`
- `TE_RTSP_TCP_TEST_NAME`
- `TE_RTSP_TCP_INTERVAL`

UDP media-path test:

- `TE_UDP_MEDIA_TEST_NAME`
- `TE_UDP_MEDIA_INTERVAL`
- `TE_A2A_PORT`
- `TE_A2A_THROUGHPUT_MEASUREMENTS`
- `TE_A2A_THROUGHPUT_RATE_MBPS`
- `TE_A2A_THROUGHPUT_DURATION_MS`

RTP proxy test:

- `TE_RTP_STREAM_TEST_NAME`
- `TE_RTP_STREAM_INTERVAL`
- `TE_VOICE_PORT`
- `TE_VOICE_CODEC_ID`
- `TE_VOICE_DURATION_SEC`

Demo Monkey HTTP tests:

- `TE_DEMO_MONKEY_FRONTEND_BASE_URL`
- `TE_TRACE_MAP_TEST_NAME`
- `TE_TRACE_MAP_TEST_URL`
- `TE_TRACE_MAP_INTERVAL`
- `TE_BROADCAST_TEST_NAME`
- `TE_BROADCAST_TEST_URL`
- `TE_BROADCAST_INTERVAL`

Target selection guidance:

- Leave `TE_DEMO_MONKEY_FRONTEND_BASE_URL` on the `svc.cluster.local` default when you want `local` Enterprise-Agent reachability inside the cluster network
- Override `TE_DEMO_MONKEY_FRONTEND_BASE_URL`, `TE_TRACE_MAP_TEST_URL`, and `TE_BROADCAST_TEST_URL` with public URLs when you want `external` ThousandEyes reachability

Splunk demo dashboard sync:

- `SPLUNK_REALM`
- `SPLUNK_ACCESS_TOKEN`
- `SPLUNK_RUM_APP_NAME`
- `SPLUNK_DEPLOYMENT_ENVIRONMENT`
- `SPLUNK_DEMO_DASHBOARD_GROUP_ID`
- `STREAMING_K8S_NAMESPACE`

## Notes

- ThousandEyes scheduled `RTP Stream` tests are created through the `voice` test API.
- In this cluster, the RTSP demo feed is exposed through `media-service-demo-rtsp` on port `8554`, so the Kubernetes wrapper defaults the TCP reachability test to `TCP/8554` instead of `TCP/554`.
- Demo Monkey changes the public frontend-backed paths `/api/v1/demo/public/trace-map` and `/api/v1/demo/public/broadcast/live/index.m3u8`, so the new HTTP server tests are the ones that visibly move when Demo Monkey presets are enabled.
- `throughputMeasurements` on the agent-to-agent UDP test cannot be enabled when either side is a Cloud Agent. If you set `TE_UDP_TARGET_AGENT_ID` or `TE_TARGET_AGENT_ID` to a Cloud Agent for the UDP test, set `TE_A2A_THROUGHPUT_MEASUREMENTS=false`.
- This does not validate the RTSP verbs themselves. Use an RTSP-aware client outside ThousandEyes for `OPTIONS`, `DESCRIBE`, `SETUP`, and `PLAY`.
- The scripts still accept `THOUSANDEYES_TOKEN` as a temporary compatibility fallback, but `THOUSANDEYES_BEARER_TOKEN` is now the primary env var.
