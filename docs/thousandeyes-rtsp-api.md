# ThousandEyes Test API Setup

This repo includes [`scripts/thousandeyes/create-rtsp-tests.sh`](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/scripts/thousandeyes/create-rtsp-tests.sh) to create the ThousandEyes tests used by this demo and [`scripts/thousandeyes/create-demo-dashboards.py`](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/scripts/thousandeyes/create-demo-dashboards.py) to sync the matching Splunk demo dashboards.

The test-creation script creates these demo tests:

- `RTSP-TCP-8554`: answers "can the chosen ThousandEyes agent reach the demo RTSP endpoint on TCP port 8554?"
- `UDP-Media-Path`: answers "can two ThousandEyes agents exchange UDP traffic over the media path?"
- `RTP-Stream-Proxy`: answers "if this were a real-time media stream, what would packet loss, jitter, and delay look like?"
- `aleccham-broadcast-trace-map`: answers "can a user or test agent reach the public trace-map API path?"
- `aleccham-broadcast-playback`: answers "can a user or test agent reach the public broadcast playback manifest URL?"

It uses the ThousandEyes `v7` scheduled test APIs:

- `POST /tests/agent-to-server`
- `POST /tests/agent-to-agent`
- `POST /tests/voice`
- `POST /tests/http-server`

Both the local script and the Kubernetes wrapper automatically read the repo-root `.env` file by default. Use `ENV_FILE=/path/to/file` if you want a different env file. Explicitly exported shell variables override `.env` values.

Choose the ThousandEyes target mode before you build payloads:

- `local`: use cluster-private or same-network endpoints that your Enterprise Agents can reach, such as `streaming-frontend.<namespace>.svc.cluster.local`
- `external`: use browser-facing or internet-reachable frontend and RTSP endpoints that ThousandEyes agents outside the cluster can reach

## Read This First If You Are New To ThousandEyes

If you come from Splunk Observability Cloud and not from a networking background, treat ThousandEyes in this repo as a way to create synthetic "outside-in" checks for the demo.

Splunk APM and RUM tell you what the app did after traffic arrived.

ThousandEyes adds a different perspective:

- can a test agent reach the frontend URL at all?
- can a test agent reach the RTSP media endpoint at all?
- if traffic flows between two locations, is the quality stable or degraded?

In plain language, the test types in this repo mean:

- HTTP tests: "can a browser-like path reach this frontend or API URL?"
- RTSP TCP test: "can a network path reach the RTSP control port?"
- UDP media-path test: "can two locations exchange UDP packets over the expected media path?"
- RTP test: "what would real-time audio or video quality look like over this path?"

The terms that confuse new users most often are:

- Enterprise Agent: a ThousandEyes test runner that your team controls, usually on a network you care about
- Cloud Agent: a ThousandEyes-hosted test runner on the public internet
- source agent: where the test starts
- target agent: the remote side used for agent-to-agent media tests
- account group: the ThousandEyes workspace or org where the tests live
- `local` mode: test private or same-network addresses, usually for Enterprise Agents that can see your cluster network
- `external` mode: test public addresses that an outside user or outside agent can reach

If you are unsure which mode to choose:

- choose `local` when your Enterprise Agents can reach the cluster-private frontend or service DNS names
- choose `external` when you want to test the public URL that a normal outside user would use

If you are unsure which tests to start with:

- start with the HTTP tests first, because they are the easiest to explain and they line up directly with what a user sees in the demo UI
- add RTSP, UDP, and RTP only after you are comfortable with the frontend path and agent selection

If you do not know an account group ID or agent ID yet, do not guess. The commands later in this doc show you how to list them first.

## How Test Direction Maps To This Environment

This repo's current demo environment is centered on a Kubernetes deployment that typically lives in AWS `us-east-1`.

For the validated demo story in this repo, the ThousandEyes agent placement is:

- source Enterprise Agent near the app environment in Ashburn, Virginia
- RTP target Enterprise Agent also near the app environment in Ashburn, Virginia
- optional UDP override target as a far-away Cloud Agent in Singapore

That placement is not arbitrary. It tells a story about direction and distance.

### Start With The Simplest Mental Model

Think of every ThousandEyes test in this repo as starting from somewhere and going toward something:

- HTTP tests start at a ThousandEyes source agent and go toward a frontend or API URL
- the RTSP TCP test starts at a ThousandEyes source agent and goes toward the RTSP endpoint
- the UDP media-path test starts at a source agent and sends traffic toward a target agent
- the RTP test starts at a source agent and sends voice-style media traffic toward a target agent

The most important distinction is:

- agent-to-server tests have one agent and one endpoint
- agent-to-agent tests have two agents, one on each end of the path

### Which Tests Use Which Direction

#### HTTP Tests

Direction:

- source agent -> frontend or API URL

In this demo, that means:

- source agent -> `streaming-frontend`
- source agent -> `/api/v1/demo/public/trace-map`
- source agent -> `/api/v1/demo/public/broadcast/live/index.m3u8`

There is no target agent for these tests. The destination is the application URL itself.

#### RTSP TCP Test

Direction:

- source agent -> RTSP endpoint on `media-service-demo-rtsp:8554`

There is no target agent here either. Like the HTTP tests, this is an agent-to-server reachability check.

#### UDP Media-Path Test

Direction:

- source agent -> target agent

This is no longer a test against an application URL. It is a test between two ThousandEyes agents that represents the expected media path between two locations.

In the repo's current demo story:

- source Enterprise Agent in Ashburn represents the side of the path that is near the app environment
- target Cloud Agent in Singapore represents a far-away remote location

That is why this test is useful for showing distance-related degradation or long-haul path behavior.

#### RTP Stream Test

Direction:

- source agent -> target agent

Like the UDP media-path test, this is an agent-to-agent test. The difference is that it measures voice or RTP-style media quality rather than generic UDP transport.

In the repo's default demo story:

- source Enterprise Agent in Ashburn
- target Enterprise Agent in Ashburn

That keeps the RTP path close to the application environment and under Enterprise-Agent control, which is usually the clearest way to show a healthy baseline media-quality path.

### Why The Agent Locations Matter

For this demo environment, the app is usually near `us-east-1`, so an Ashburn Enterprise Agent is treated as "near the app."

That means:

- use Ashburn or another nearby Enterprise Agent when you want to represent users or infrastructure close to the app environment
- use a far-away Cloud Agent such as Singapore when you want to represent geographic distance from the app

In plain language:

- near agent = "what does this path look like close to where the app runs?"
- far agent = "what does this path look like from much farther away?"

### Why RTP And UDP Might Use Different Targets

In this repo, it is often useful to keep the RTP test and the UDP media-path test pointed at different target locations.

Recommended demo pattern:

- keep RTP on a nearby Enterprise Agent pair for a stable, easy-to-explain media-quality baseline
- point the UDP media-path test at a distant Cloud Agent when you want to show how the path changes across geography

That is why the repo supports `TE_UDP_TARGET_AGENT_ID` as an override. It lets the UDP test use a different target without forcing the RTP test to use the same far-away target.

### How To Choose Agent Locations When Your Environment Is Different

If your cluster is not in `us-east-1`, keep the same logic but swap the geography:

- choose a source Enterprise Agent near the deployed application
- choose a nearby Enterprise Agent target when you want a "healthy close-to-app" RTP baseline
- choose a distant Cloud Agent target when you want to demonstrate a long-distance UDP path

The exact city can change. The selection logic should not.

### Safe First-Time Recommendation

If you are new to ThousandEyes in this repo:

1. start with HTTP tests from a source agent that can reach the frontend
2. add the RTSP TCP test from that same source location
3. keep RTP on a nearby Enterprise-Agent pair
4. use the Singapore-style far-away Cloud Agent pattern only for the UDP media-path test when you deliberately want to show geographic separation

That sequence is easier to explain to a Splunk Observability Cloud audience because it moves from simple URL reachability to richer network-path and media-quality stories.

## Prerequisites

- A ThousandEyes bearer token with API access
- The `agentId` values for your source Enterprise Agents and target Enterprise Agent
- The account group ID if you want to create the tests in a non-default account group

For a first-time user, the easiest mental model is:

1. make sure the app is already deployed and reachable
2. decide whether you are testing private addresses or public ones
3. decide where the source and target agents should sit relative to the app environment
4. list account groups and agents
5. dry-run the ThousandEyes payloads before creating anything
6. use the Kubernetes wrapper if you want the repo to discover the demo endpoints for you

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

If you are not sure what to put in those variables, use this translation:

- `THOUSANDEYES_ACCOUNT_GROUP_ID`: "which ThousandEyes workspace should own these tests?"
- `TE_SOURCE_AGENT_IDS`: "which agent or agents should start the test?"
- `TE_TARGET_AGENT_ID`: "which remote agent should represent the far side of the media path?"
- `TE_UDP_TARGET_AGENT_ID`: "should the UDP media-path test use a different remote agent than the RTP test?"
- `TE_RTSP_SERVER`: "what hostname should ThousandEyes use for the RTSP endpoint?"
- `TE_DEMO_MONKEY_FRONTEND_BASE_URL`: "what base frontend URL should the HTTP tests hit?"

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

This is the easiest path for a user who does not want to hand-build payloads or think through every endpoint variable manually.

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

For a non-networking user, this is a good first ThousandEyes exercise because the HTTP tests map directly to visible frontend behavior:

- `/api/v1/demo/public/trace-map`
- `/api/v1/demo/public/broadcast/live/index.m3u8`

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

For the RTP dashboard, ThousandEyes test results alone are not enough. An enabled ThousandEyes OTel metric stream must also include the RTP test through `testMatch` or export the `voice` test type. Otherwise Splunk receives no `rtp.client.request.*` series even when the ThousandEyes RTP test itself is healthy. The dashboard sync helper now warns when the repo RTP test exists but no enabled metric stream appears to cover it.

When you update a ThousandEyes data stream, remember that the `v7` update API overwrites list fields such as `testMatch` instead of appending to them. Preserve the existing `testMatch` entries when you add the RTP test.

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
