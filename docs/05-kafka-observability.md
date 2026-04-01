# 05. Kafka Observability

The registration-to-customer-provisioning path now emits both application-level Micrometer metrics and native Kafka client metrics.

## Application Metrics

`user-service` emits:

- `streaming.user.outbox.pending.events`
- `streaming.user.outbox.ready.events`
- `streaming.user.outbox.enqueue.total`
- `streaming.user.outbox.publish.total{event.type,outcome}`
- `streaming.user.outbox.publish.failures.total{event.type}`
- `streaming.user.outbox.retry.scheduled.total{event.type}`
- `streaming.user.outbox.publish.duration{event.type,outcome}`

`customer-service` emits:

- `streaming.customer.provisioning.total{outcome}`
- `streaming.customer.provisioning.duplicates.total`
- `streaming.customer.provisioning.duration{outcome}`

Both services expose the Actuator metrics endpoint at `/actuator/metrics`.

## Kafka Client Telemetry

Both services bind native Kafka client metrics into Micrometer:

- `user-service` binds producer metrics
- `customer-service` binds both producer and consumer metrics

This is where consumer lag and other broker-facing client health metrics surface. The exact meter names follow Micrometer's Kafka binder naming in your active registry.

Spring Kafka observation is also enabled for the `KafkaTemplate` and `@KafkaListener` path so publish and consume spans join the existing tracing pipeline.

## Broker Metrics

This repo does not manage a Kafka broker deployment, so broker-side JMX scraping is not configured here.

If you need broker metrics, add them where Kafka is actually deployed:

- expose broker JMX from the Kafka deployment
- scrape or receive those metrics with your OpenTelemetry collector
- keep broker metrics separate from service metrics so client lag and broker health can be reasoned about independently
