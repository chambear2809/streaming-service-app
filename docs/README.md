# Docs Guide Order

Follow the top-level guides in this order when you want the canonical operator path through the repo:

1. [`01-kubernetes-deployment-learning-guide.md`](01-kubernetes-deployment-learning-guide.md) for the base Kubernetes deploy, rollout checks, and first-run troubleshooting.
2. [`02-splunk-otel-collector-bootstrap.md`](02-splunk-otel-collector-bootstrap.md) for installing or reusing the repo-compatible Splunk OTel collector.
3. [`03-distributed-tracing.md`](03-distributed-tracing.md) for Java and Node.js auto-instrumentation, trace propagation, and Browser RUM context.
4. [`04-postgresql-db-monitoring.md`](04-postgresql-db-monitoring.md) for adding PostgreSQL DB monitoring to `streaming-postgres`.
5. [`05-kafka-observability.md`](05-kafka-observability.md) for Kafka client telemetry and service-side messaging metrics.
6. [`06-thousandeyes-rtsp-api.md`](06-thousandeyes-rtsp-api.md) for ThousandEyes tests, alert rules, and dashboard sync.
7. [`07-broadcast-loadgen.md`](07-broadcast-loadgen.md) for public viewer traffic generation.
8. [`08-operator-billing-loadgen.md`](08-operator-billing-loadgen.md) for protected operator, billing, and optional commerce traffic generation.

Supporting material that does not fit the main numbered flow stays under [`docs/isovalent/`](isovalent/README.md).
