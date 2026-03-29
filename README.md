# Streaming Service App

This repository contains a microservices-based streaming platform plus a lightweight broadcast-style demo frontend. The core platform is oriented around subscription video streaming, while the demo layer exposes auth, content, and media slices in a way that is easier to review as an operator-facing broadcasting experience.

The subscription management system is integrated with Stripe for payment processing and subscription status management. Every day at midnight, the subscription service checks for subscriptions that are about to expire and automatically renews or cancels them based on user settings and payment status.

## Technology Stack
The project employs the following technologies:
- Java JDK 22
- JWT
- MinIO (S3)
- Redis
- FFmpeg
- Kafka
- Spring Boot: A framework for building microservices.
- Spring Security: Manages authentication and authorization.
- Feign: A declarative HTTP client for microservices.
- Spring Data JPA: Facilitates interaction with relational databases.
- PostgreSQL: A relational database for storing core information.
- MongoDB: A NoSQL database for managing schema-less data.
- Eureka: A service registry for managing microservices.
- Spring Cloud Config: Enables centralized configuration management.
- Flyway Migration: Handles database versioning.
- Zookeeper: Coordinates distributed systems.
- Gateway: An API gateway for routing requests.
- Quartz: A task scheduler for managing periodic operations.
- Stripe: Integrates with a payment system for handling subscriptions and payments.

## Diagrams
All use case and architecture diagrams related to the project can be found in the diagrams folder. This folder contains visualizations that aid in understanding the structure and interactions of the system components.

## Broadcast Demo Surfaces

- `frontend/` contains a static broadcast-operations portal with a protected library, player, lineup wall, rundown, and RTSP ingest review surface.
- `scripts/frontend/deploy.sh` deploys the static frontend to Kubernetes.
- `scripts/backend-demo/deploy.sh` deploys lightweight demo auth, content, and media services that back the portal.
- `services/billing-service/` adds invoice, line-item, balance-due, and account-summary APIs as a dedicated microservice above raw payment processing, with JWT-protected billing routes and a public `GET /api/v1/billing/health` readiness endpoint.

## Deployment Skills

- `skills/deploy-streaming-app/` contains the Codex skill for deploying the demo stack into Kubernetes or OpenShift without editing the checked-in manifests.
- `.cursor/skills/deploy-streaming-app/` mirrors the same workflow in Cursor's project-skill layout so Cursor can discover it automatically.
- `skills/deploy-streaming-app/scripts/deploy-demo.sh` is the canonical deploy entry point shared by both skills. It supports namespace overrides, frontend runtime labeling, PostgreSQL provisioning for billing, and OpenShift Route exposure for the UI.
- `.cursor/skills/deploy-streaming-app/scripts/deploy-demo.sh` is a thin wrapper that forwards to the canonical repo script, so the Cursor copy stays aligned without duplicating the deploy logic.
- Both skill copies now include the ThousandEyes validation workflow and the Splunk dashboard workflow: create `.env` from `example.env` when needed, use the helper script to figure out which ThousandEyes org or account group to configure, discover agents, search for a named Enterprise Agent across account groups, choose a far-away Cloud Agent when useful, handle the Cloud Agent UDP throughput restriction, prompt for missing token or object-ID inputs, create either RTSP-adjacent or Demo Monkey-sensitive tests, and sync the numbered Splunk demo dashboard group.

## Operator Inputs

- Configure user-specific values in the repo-root `.env` or export them in your shell before running deploy or test-creation commands.
- Frontend and Splunk Observability values usually needed before build or deploy:
  - `SPLUNK_REALM`
  - `SPLUNK_RUM_ACCESS_TOKEN`
  - `SPLUNK_RUM_APP_NAME`
  - `SPLUNK_DEPLOYMENT_ENVIRONMENT`
  - If you want to sync dashboards or call the Splunk API, also provide `SPLUNK_ACCESS_TOKEN`
  - If you want to update an existing dashboard group instead of matching or creating one automatically, also provide `SPLUNK_DEMO_DASHBOARD_GROUP_ID`
  - If the demo was deployed into a non-default namespace, also provide `STREAMING_K8S_NAMESPACE`
- ThousandEyes values usually needed before creating tests:
  - `THOUSANDEYES_BEARER_TOKEN`
  - `THOUSANDEYES_ACCOUNT_GROUP_ID`
  - `TE_SOURCE_AGENT_IDS`
  - `TE_TARGET_AGENT_ID`
  - `TE_UDP_TARGET_AGENT_ID` when you want the UDP media-path test to target a different agent than RTP
  - If RTSP discovery will not happen automatically, also set `TE_RTSP_SERVER` and `TE_RTSP_PORT`
  - Choose whether the tests should target `local` cluster-private endpoints or `external` public endpoints before deriving the test URLs
  - In `external` mode, also set `TE_DEMO_MONKEY_FRONTEND_BASE_URL` or explicit `TE_TRACE_MAP_TEST_URL` and `TE_BROADCAST_TEST_URL`
- If you are guiding a user through setup, prompt for the exact variable names instead of asking for a generic token or ID bundle.
- Recommended prompt:
  - `Please provide THOUSANDEYES_BEARER_TOKEN, THOUSANDEYES_ACCOUNT_GROUP_ID, TE_SOURCE_AGENT_IDS, and TE_TARGET_AGENT_ID. If the UDP media-path test should use a different Cloud Agent target than RTP, also provide TE_UDP_TARGET_AGENT_ID. You can add them to the repo-root .env or export them in your shell.`
  - `Please provide SPLUNK_REALM, SPLUNK_RUM_ACCESS_TOKEN, SPLUNK_RUM_APP_NAME, and SPLUNK_DEPLOYMENT_ENVIRONMENT. If you also want dashboard sync, provide SPLUNK_ACCESS_TOKEN. If you already have a dashboard group to update, also provide SPLUNK_DEMO_DASHBOARD_GROUP_ID. If the demo is not in the default namespace, also provide STREAMING_K8S_NAMESPACE.`

## ThousandEyes Tests

- `scripts/thousandeyes/create-rtsp-tests.sh` creates the scheduled ThousandEyes RTSP-adjacent tests plus the Demo Monkey-sensitive HTTP server tests directly through the `v7` API and reads the repo-root `.env` by default.
  - It also exposes `list-orgs` as a user-facing alias for `list-account-groups`, so operators can identify the correct `THOUSANDEYES_ACCOUNT_GROUP_ID` before creating tests.
  - Use it when you want to point the tests at external URLs or other explicitly provided endpoints instead of relying on cluster-local defaults.
- `scripts/thousandeyes/deploy-k8s-rtsp-tests.sh` creates the same tests from a one-shot Kubernetes Job after discovering the external `media-service-demo-rtsp` LoadBalancer hostname and port and deriving the in-cluster `streaming-frontend` base URL.
  - Override `TE_DEMO_MONKEY_FRONTEND_BASE_URL`, `TE_TRACE_MAP_TEST_URL`, `TE_BROADCAST_TEST_URL`, `TE_RTSP_SERVER`, and `TE_RTSP_PORT` if you want the wrapper to test external endpoints instead of local ones.
- `scripts/thousandeyes/create-rtsp-tests-in-cluster.sh` is the in-cluster runner mounted by the Kubernetes Job.
- `scripts/thousandeyes/create-demo-dashboards.py` creates or updates the Splunk Observability dashboard group for the demo flow. It keeps the dashboards ordered as `01 Start Here: Network Symptoms`, `02 Pivot: User Impact To Root Cause`, `03 Backend Critical Path`, then the protocol-specific ThousandEyes deep dives.
- Before you run either ThousandEyes flow, fill in the required token and object IDs in the repo-root `.env` or export them in your shell:
  - `THOUSANDEYES_BEARER_TOKEN`
  - `THOUSANDEYES_ACCOUNT_GROUP_ID`
  - `TE_SOURCE_AGENT_IDS`
  - `TE_TARGET_AGENT_ID`
  - `TE_UDP_TARGET_AGENT_ID` when the UDP media-path test should target a different agent than RTP
  - If RTSP discovery will not happen automatically, also set `TE_RTSP_SERVER` and `TE_RTSP_PORT`
- `example.env` includes the ThousandEyes settings used by both the direct API path and the in-cluster Job path:
  - `THOUSANDEYES_BEARER_TOKEN`
  - `THOUSANDEYES_ACCOUNT_GROUP_ID`
  - `TE_SOURCE_AGENT_IDS`
  - `TE_TARGET_AGENT_ID`
  - `TE_UDP_TARGET_AGENT_ID`
  - `TE_A2A_THROUGHPUT_MEASUREMENTS`
- Use `THOUSANDEYES_JOB_ACTION=create-demo-monkey-http` when you want the two HTTP server tests that Demo Monkey actually changes:
  - `aleccham-broadcast-trace-map`
  - `aleccham-broadcast-playback`
- If `TE_UDP_TARGET_AGENT_ID` or `TE_TARGET_AGENT_ID` points at a Cloud Agent for the UDP media-path test, set `TE_A2A_THROUGHPUT_MEASUREMENTS=false` because ThousandEyes does not allow UDP throughput measurements with a Cloud Agent endpoint.
- Use `scripts/thousandeyes/create-rtsp-tests.sh list-orgs` to decide which org or account group ID belongs in `THOUSANDEYES_ACCOUNT_GROUP_ID`, and use `scripts/thousandeyes/create-rtsp-tests.sh list-agents` to choose `TE_SOURCE_AGENT_IDS`, `TE_TARGET_AGENT_ID`, and, when needed, `TE_UDP_TARGET_AGENT_ID`.
- If values are missing, stop and ask the user for the exact env var names you need before calling the ThousandEyes API.

## Splunk Demo Dashboards

- Build the dashboard group only after the ThousandEyes tests exist and Splunk has the frontend, APM, and Kubernetes signals you want to demo.
- Before calling the Splunk dashboard API, make sure the repo-root `.env` or the current shell already defines:
  - `SPLUNK_REALM`
  - `SPLUNK_ACCESS_TOKEN`
  - `SPLUNK_VALIDATION_TOKEN` when your dashboard-write token cannot read metric data but you still want the live ThousandEyes metric validation step
  - `SPLUNK_RUM_APP_NAME`
  - `SPLUNK_DEPLOYMENT_ENVIRONMENT`
  - If you want to update a specific existing group, `SPLUNK_DEMO_DASHBOARD_GROUP_ID`
  - If the demo was deployed into a non-default namespace, `STREAMING_K8S_NAMESPACE`
  - `TE_RTSP_TCP_TEST_NAME`, `TE_UDP_MEDIA_TEST_NAME`, `TE_RTP_STREAM_TEST_NAME`, `TE_TRACE_MAP_TEST_NAME`, or `TE_BROADCAST_TEST_NAME` when you created the ThousandEyes tests with non-default names
  - `TE_RTSP_TCP_TEST_ID`, `TE_UDP_MEDIA_TEST_ID`, `TE_RTP_STREAM_TEST_ID`, `TE_TRACE_MAP_TEST_ID`, or `TE_BROADCAST_TEST_ID` when duplicate ThousandEyes test names exist and you need to pin the exact dashboard test ID
- If values are missing, stop and ask the user for the exact env var names you need before calling the Splunk API.
- Sync the dashboard group:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py
```

- To target an existing dashboard group explicitly:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py \
  --group-id "$SPLUNK_DEMO_DASHBOARD_GROUP_ID"
```

- To point the workload charts at a non-default namespace:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py \
  --namespace streaming-demo
```

- The dashboard sync script skips repo test dashboards that do not exist yet in ThousandEyes instead of creating empty placeholders.
- The dashboard sync script now validates that the ThousandEyes metrics behind the dashboard charts are present in Splunk for the selected test IDs. If your write token cannot read metric data, set `SPLUNK_VALIDATION_TOKEN` or pass `--skip-te-metric-validation`.

## Distributed Tracing

- `docs/distributed-tracing.md` documents the Splunk Observability Cloud and ThousandEyes tracing setup used by the Kubernetes demo deployment.
- `docs/thousandeyes-rtsp-api.md` documents how to create both RTSP-adjacent and Demo Monkey-sensitive ThousandEyes synthetic tests via the v7 API and then sync the matching Splunk demo dashboards.

## Load Generators

- `scripts/loadgen/broadcast-loadgen.mjs` simulates viewer sessions against the public broadcast page, status API, HLS playlists, segments, and optional trace-map pivots.
- `scripts/loadgen/deploy-k8s-broadcast-loadgen.sh` pushes that public loadgen into the Kubernetes cluster as either a one-shot `Job` or a recurring `CronJob`, with profile presets and operator actions for apply, status, pause, resume, trigger, and delete.
- `docs/broadcast-loadgen.md` documents the public workload model, the in-cluster launch flow, the safer `90 viewer / 10m` booth-default profile, the separate `120 viewer / 12m` stress profile, and the recurring `CronJob` controls.
- `scripts/loadgen/operator-billing-loadgen.mjs` simulates protected operator activity through the demo frontend, including Accounts, Payments, Commerce, billing events, RTSP control, and order lifecycle transitions.
- `scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh` pushes that protected loadgen into the Kubernetes cluster as either a one-shot `Job` or a recurring `CronJob`, with profile presets and the same apply/status/pause/resume/trigger workflow.
- `docs/operator-billing-loadgen.md` documents the protected workload mix, the in-cluster launch flow, the booth-default profile, and the recurring `CronJob` controls.

## License

This project is distributed under the MIT License. For more details, please refer to the LICENSE file located in the root directory of the project.
