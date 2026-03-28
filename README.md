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

## Distributed Tracing

- `docs/distributed-tracing.md` documents the Splunk Observability Cloud and ThousandEyes tracing setup used by the Kubernetes demo deployment.

## License

This project is distributed under the MIT License. For more details, please refer to the LICENSE file located in the root directory of the project.
