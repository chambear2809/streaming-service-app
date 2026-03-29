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

## Start Here

Choose the path that matches how you want to work with the repo:

1. Local dependency services only:

```bash
cp example.env .env
docker compose up -d
```

This brings up the backing services from [docker-compose.yml](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/docker-compose.yml) such as PostgreSQL, Kafka, Redis, MinIO, MongoDB, Zipkin, and Vault. It does not boot every application service for you.

2. Frontend-only local preview:

- See [frontend/README.md](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/frontend/README.md) for the static build and preview flow.

3. Full Kubernetes or OpenShift demo deployment:

```bash
cp example.env .env
bash skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
```

- Use `--platform openshift` when you want the UI exposed through an OpenShift Route.
- The lower-level split deploy scripts remain available at `scripts/backend-demo/deploy.sh` and `scripts/frontend/deploy.sh`, but `skills/deploy-streaming-app/scripts/deploy-demo.sh` is the canonical full-demo entry point.

4. Observability and synthetic-test setup:

- See [docs/thousandeyes-rtsp-api.md](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/docs/thousandeyes-rtsp-api.md) for the ThousandEyes and Splunk dashboard workflow.
- See [docs/distributed-tracing.md](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/docs/distributed-tracing.md) for the tracing setup used by the cluster demo.

## Broadcast Demo Surfaces

- `frontend/` contains the broadcast-style operations portal with the protected library, player, lineup wall, rundown, Demo Monkey controls, and RTSP ingest review surfaces.
- `scripts/backend-demo/deploy.sh` packages and deploys PostgreSQL plus the content, media, user, billing, ad, customer, payment, subscription, and order demo services, then hands off to the frontend deploy.
- `scripts/frontend/deploy.sh` builds the static frontend, uploads Splunk Browser RUM source maps when configured, and exposes the UI through the frontend service.
- `services/billing-service/` adds invoice, line-item, business-event, balance-due, and account-summary APIs above raw payment processing, with JWT-protected billing routes and a public `GET /api/v1/billing/health` readiness endpoint.

## Deployment Automation

- `skills/deploy-streaming-app/` contains the repo's Codex skill for deploying the demo stack into Kubernetes or OpenShift without editing checked-in manifests.
- `.cursor/skills/deploy-streaming-app/` mirrors the same workflow in Cursor's project-skill layout.
- `skills/deploy-streaming-app/scripts/deploy-demo.sh` is the canonical deploy entry point shared by both skill layouts.

## Common Environment Variables

Create the repo-root `.env` from `example.env` when you want a concrete place to keep operator-specific settings:

```bash
cp example.env .env
```

Frontend build, source maps, and Splunk Observability:

- `SPLUNK_REALM`
- `SPLUNK_RUM_ACCESS_TOKEN` for Browser RUM injection and source map upload
- `SPLUNK_ACCESS_TOKEN` for Splunk API calls such as dashboard sync
- `SPLUNK_RUM_APP_NAME` when you want to override the default RUM app name
- `SPLUNK_DEPLOYMENT_ENVIRONMENT` when you want to override the default environment label
- `SPLUNK_DEMO_DASHBOARD_GROUP_ID` when you want to update a specific dashboard group
- `SPLUNK_VALIDATION_TOKEN` when the dashboard-write token cannot read SignalFlow metric data
- `STREAMING_K8S_NAMESPACE` when the demo was deployed outside the default namespace

ThousandEyes test creation:

- `THOUSANDEYES_BEARER_TOKEN` is required for all ThousandEyes API flows
- `TE_SOURCE_AGENT_IDS` is required for test creation
- `TE_TARGET_AGENT_ID` is required for the RTP proxy test and acts as the default target for the UDP media-path test
- `TE_UDP_TARGET_AGENT_ID` is an optional override when the UDP media-path test should target a different agent than RTP
- `THOUSANDEYES_ACCOUNT_GROUP_ID` is recommended when you want deterministic org or account-group targeting, and it is required later for dashboard sync
- `TE_RTSP_SERVER` and `TE_RTSP_PORT` are needed for the direct API flow or when the Kubernetes wrapper cannot discover the RTSP endpoint automatically
- Choose the target mode before deriving URLs:
  - `local` keeps the default `svc.cluster.local` frontend targets for same-network Enterprise Agents
  - `external` requires browser-facing or internet-reachable RTSP and frontend targets
- In `external` mode, set `TE_DEMO_MONKEY_FRONTEND_BASE_URL` or explicit `TE_TRACE_MAP_TEST_URL` and `TE_BROADCAST_TEST_URL`

## Observability And Synthetic Tests

- `scripts/thousandeyes/create-rtsp-tests.sh` creates the RTSP-adjacent and Demo Monkey-sensitive HTTP tests directly through the ThousandEyes `v7` API. It reads the repo-root `.env` by default and exposes:
  - `list-orgs` and `list-account-groups` for account-group discovery
  - `list-agents` for source and target agent selection
  - `create-all` for the full test bundle
- `scripts/thousandeyes/deploy-k8s-rtsp-tests.sh` creates the same tests from a one-shot Kubernetes Job after discovering the RTSP service endpoint and deriving the in-cluster frontend base URL.
- `scripts/thousandeyes/create-demo-dashboards.py` creates or updates the Splunk Observability dashboard group for the demo flow.

Detailed walkthroughs live in the docs:

- [docs/thousandeyes-rtsp-api.md](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/docs/thousandeyes-rtsp-api.md) covers both ThousandEyes creation paths and dashboard sync.
- [docs/distributed-tracing.md](/Users/alecchamberlain/Documents/GitHub/streaming-service-app/docs/distributed-tracing.md) covers the tracing and Splunk Observability setup used by the Kubernetes demo.

Important behavior to know before you run the observability flows:

- The direct ThousandEyes helper can use the token's default account group when `THOUSANDEYES_ACCOUNT_GROUP_ID` is omitted, but dashboard sync requires both `THOUSANDEYES_BEARER_TOKEN` and `THOUSANDEYES_ACCOUNT_GROUP_ID`.
- Use `THOUSANDEYES_JOB_ACTION=create-demo-monkey-http` when you only want the two HTTP tests that Demo Monkey actually degrades.
- If `TE_UDP_TARGET_AGENT_ID` or `TE_TARGET_AGENT_ID` points at a Cloud Agent for the UDP media-path test, set `TE_A2A_THROUGHPUT_MEASUREMENTS=false` because ThousandEyes rejects UDP throughput measurements with a Cloud Agent endpoint.
- Dashboard sync requires `SPLUNK_REALM`, `SPLUNK_ACCESS_TOKEN`, `THOUSANDEYES_BEARER_TOKEN`, and `THOUSANDEYES_ACCOUNT_GROUP_ID`. `SPLUNK_VALIDATION_TOKEN` is optional and is only needed when the write token cannot read SignalFlow metric data.
- If the ThousandEyes tests were created with non-default names or duplicate names exist, set the matching `TE_*_TEST_NAME` or `TE_*_TEST_ID` overrides before running dashboard sync.
- The dashboard sync script skips missing repo test dashboards instead of creating empty placeholders, and it supports `--skip-te-metric-validation` when you need to bypass the post-sync SignalFlow data check.

Example dashboard sync:

```bash
python3 scripts/thousandeyes/create-demo-dashboards.py
```

## Load Generators

- `scripts/loadgen/broadcast-loadgen.mjs` simulates viewer sessions against the public broadcast page, status API, HLS playlists, segments, and optional trace-map pivots.
- `scripts/loadgen/deploy-k8s-broadcast-loadgen.sh` pushes that public loadgen into the Kubernetes cluster as either a one-shot `Job` or a recurring `CronJob`, with profile presets and operator actions for apply, status, pause, resume, trigger, and delete.
- `docs/broadcast-loadgen.md` documents the public workload model, the in-cluster launch flow, the safer `90 viewer / 10m` booth-default profile, the separate `120 viewer / 12m` stress profile, and the recurring `CronJob` controls.
- `scripts/loadgen/operator-billing-loadgen.mjs` simulates protected operator activity through the demo frontend, including Accounts, Payments, Commerce, billing events, RTSP control, and order lifecycle transitions.
- `scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh` pushes that protected loadgen into the Kubernetes cluster as either a one-shot `Job` or a recurring `CronJob`, with profile presets and the same apply/status/pause/resume/trigger workflow.
- `docs/operator-billing-loadgen.md` documents the protected workload mix, the in-cluster launch flow, the booth-default profile, and the recurring `CronJob` controls.

## License

This project is distributed under the MIT License. For more details, please refer to the LICENSE file located in the root directory of the project.
