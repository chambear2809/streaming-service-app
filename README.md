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
- Both skill copies now include the ThousandEyes validation workflow: create `.env` from `example.env` when needed, use the helper script to figure out which ThousandEyes org or account group to configure, discover agents, search for a named Enterprise Agent across account groups, choose a far-away Cloud Agent when useful, handle the Cloud Agent UDP throughput restriction, prompt for missing token or object-ID inputs, and create either RTSP-adjacent or Demo Monkey-sensitive tests.

## Operator Inputs

- Configure user-specific values in the repo-root `.env` or export them in your shell before running deploy or test-creation commands.
- Frontend and Splunk Observability values usually needed before build or deploy:
  - `SPLUNK_REALM`
  - `SPLUNK_ACCESS_TOKEN`
  - `SPLUNK_RUM_APP_NAME`
  - `SPLUNK_DEPLOYMENT_ENVIRONMENT`
- ThousandEyes values usually needed before creating tests:
  - `THOUSANDEYES_BEARER_TOKEN`
  - `THOUSANDEYES_ACCOUNT_GROUP_ID`
  - `TE_SOURCE_AGENT_IDS`
  - `TE_TARGET_AGENT_ID`
  - If RTSP discovery will not happen automatically, also set `TE_RTSP_SERVER` and `TE_RTSP_PORT`
- If you are guiding a user through setup, prompt for the exact variable names instead of asking for a generic token or ID bundle.
- Recommended prompt:
  - `Please provide THOUSANDEYES_BEARER_TOKEN, THOUSANDEYES_ACCOUNT_GROUP_ID, TE_SOURCE_AGENT_IDS, and TE_TARGET_AGENT_ID. You can add them to the repo-root .env or export them in your shell.`

## ThousandEyes Tests

- `scripts/thousandeyes/create-rtsp-tests.sh` creates the scheduled ThousandEyes RTSP-adjacent tests plus the Demo Monkey-sensitive HTTP server tests directly through the `v7` API and reads the repo-root `.env` by default.
  - It also exposes `list-orgs` as a user-facing alias for `list-account-groups`, so operators can identify the correct `THOUSANDEYES_ACCOUNT_GROUP_ID` before creating tests.
- `scripts/thousandeyes/deploy-k8s-rtsp-tests.sh` creates the same tests from a one-shot Kubernetes Job after discovering the external `media-service-demo-rtsp` LoadBalancer hostname and port and deriving the in-cluster `streaming-frontend` base URL.
- `scripts/thousandeyes/create-rtsp-tests-in-cluster.sh` is the in-cluster runner mounted by the Kubernetes Job.
- Before you run either ThousandEyes flow, fill in the required token and object IDs in the repo-root `.env` or export them in your shell:
  - `THOUSANDEYES_BEARER_TOKEN`
  - `THOUSANDEYES_ACCOUNT_GROUP_ID`
  - `TE_SOURCE_AGENT_IDS`
  - `TE_TARGET_AGENT_ID`
  - If RTSP discovery will not happen automatically, also set `TE_RTSP_SERVER` and `TE_RTSP_PORT`
- `example.env` includes the ThousandEyes settings used by both the direct API path and the in-cluster Job path:
  - `THOUSANDEYES_BEARER_TOKEN`
  - `THOUSANDEYES_ACCOUNT_GROUP_ID`
  - `TE_SOURCE_AGENT_IDS`
  - `TE_TARGET_AGENT_ID`
  - `TE_A2A_THROUGHPUT_MEASUREMENTS`
- Use `THOUSANDEYES_JOB_ACTION=create-demo-monkey-http` when you want the two HTTP server tests that Demo Monkey actually changes:
  - `aleccham-broadcast-trace-map`
  - `aleccham-broadcast-playback`
- If `TE_TARGET_AGENT_ID` points at a Cloud Agent, set `TE_A2A_THROUGHPUT_MEASUREMENTS=false` because ThousandEyes does not allow UDP throughput measurements with a Cloud Agent endpoint.
- Use `scripts/thousandeyes/create-rtsp-tests.sh list-orgs` to decide which org or account group ID belongs in `THOUSANDEYES_ACCOUNT_GROUP_ID`, and use `scripts/thousandeyes/create-rtsp-tests.sh list-agents` to choose `TE_SOURCE_AGENT_IDS` and `TE_TARGET_AGENT_ID`.
- If values are missing, stop and ask the user for the exact env var names you need before calling the ThousandEyes API.

## Distributed Tracing

- `docs/distributed-tracing.md` documents the Splunk Observability Cloud and ThousandEyes tracing setup used by the Kubernetes demo deployment.
- `docs/thousandeyes-rtsp-api.md` documents how to create both RTSP-adjacent and Demo Monkey-sensitive ThousandEyes synthetic tests via the v7 API.

## License

This project is distributed under the MIT License. For more details, please refer to the LICENSE file located in the root directory of the project.
