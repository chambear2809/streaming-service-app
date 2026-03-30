# PostgreSQL DB Monitoring

This repo's canonical cluster demo deploys PostgreSQL as the `streaming-postgres` service. The existing Java and Node.js auto-instrumentation in this repo covers APM and RUM, but it does not add PostgreSQL infrastructure or query-level database monitoring by itself. Configure the Splunk OpenTelemetry Collector separately when you want PostgreSQL DB monitoring in Splunk Observability Cloud.

## Repo-Specific Shape

- [`k8s/backend-demo/postgres.yaml`](../k8s/backend-demo/postgres.yaml) deploys one PostgreSQL 16 pod and service named `streaming-postgres`
- The demo services all connect to the same database name, `streaming`
- The services separate their data with PostgreSQL schemas such as `demo_content`, `demo_media`, and `billing`
- For the collector's `postgresql` receiver, set `databases` to `streaming`
- Do not list schema names like `demo_content` or `billing` under `databases`

## Prerequisites

- A Splunk Database Monitoring license
- A supported collector version from the Splunk Database Monitoring docs:
  - Splunk Distribution of the OpenTelemetry Collector `v0.147.0` or later
  - OpenTelemetry Collector Contrib `v0.147.0` or later
  - Splunk OpenTelemetry Java `v2.22.0` or later when that version floor matters for the surrounding deployment
- PostgreSQL credentials that can connect to the `streaming` database
- A Splunk Observability Cloud realm and access token for the `dbmon` event exporter
- PostgreSQL must load `pg_stat_statements` through `shared_preload_libraries`
- The `pg_stat_statements` extension must exist in the `streaming` database
- In this repo, create the extension in `postgres` too. The live receiver behavior showed the top-query path still needs the view there.

Important support note:

- The Splunk PostgreSQL DB monitoring material provided for this task calls out specific managed Azure and Amazon RDS PostgreSQL platforms.
- This repo deploys a self-managed `postgres:16-alpine` pod on Kubernetes instead.
- Use the configuration below as the repo-aligned collector setup, but validate support in your Splunk tenant before you depend on the Database Monitoring UI behavior for this demo environment.

## Kubernetes Collector Pattern

When the collector runs on Kubernetes through the Splunk OTel Helm chart:

- Put the `postgresql` receiver on `clusterReceiver.config`, not `agent.config`
- Keep the receiver on the chart's single-replica `clusterReceiver` deployment so the shared database is scraped once, not once per node
- Add dedicated `metrics/dbmon` and `logs/dbmon` pipelines for PostgreSQL DB monitoring
- Keep the processor list identical between those two DBMON pipelines, in the same order
- Do not try to append `postgresql` to the chart's default `metrics` receiver list through a thin overlay unless you intentionally want to take ownership of that whole list

For fresh repo deployments, [`k8s/backend-demo/postgres.yaml`](../k8s/backend-demo/postgres.yaml) now:

- starts PostgreSQL with `shared_preload_libraries=pg_stat_statements`
- initializes `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;` in both `streaming` and `postgres` on first database bootstrap
- includes a narrow ingress policy so the collector in `otel-splunk` can reach `streaming-postgres:5432`

If your PostgreSQL pod was already running before those manifest changes were applied, you still need a one-time in-place enablement or a controlled PostgreSQL restart before query samples and top queries will work.

The canonical app deploy script in this repo does not install or mutate the Splunk OTel Collector. Treat PostgreSQL DB monitoring as a follow-on change to the collector Helm values you already use in the cluster.

## Collector Values Fragment

Use the checked-in override at [`k8s/otel-splunk/postgresql-dbmon.values.yaml`](../k8s/otel-splunk/postgresql-dbmon.values.yaml) as the repo's deployable PostgreSQL DB monitoring fragment.

It is designed to layer on top of an existing Splunk OTel Collector Helm release. Do not blindly replace your full `clusterReceiver.config` block if you already have cluster-level collectors configured.

The checked-in fragment keeps DBMON in dedicated `metrics/dbmon` and `logs/dbmon` pipelines so it can layer cleanly on top of the chart defaults without replacing the main `metrics` receiver list.

```yaml
clusterReceiver:
  extraEnvs:
    - name: SPLUNK_DBMON_POSTGRES_ENDPOINT
      value: streaming-postgres.streaming-service-app.svc.cluster.local:5432
    - name: SPLUNK_DBMON_EVENT_ENDPOINT
      value: https://ingest.us1.signalfx.com/v3/event
    - name: SPLUNK_DBMON_POSTGRES_USERNAME
      valueFrom:
        secretKeyRef:
          name: streaming-postgres-dbmon
          key: username
    - name: SPLUNK_DBMON_POSTGRES_PASSWORD
      valueFrom:
        secretKeyRef:
          name: streaming-postgres-dbmon
          key: password
    - name: SPLUNK_DBMON_ACCESS_TOKEN
      valueFrom:
        secretKeyRef:
          name: streaming-postgres-dbmon
          key: access-token

  config:
    receivers:
      postgresql:
        collection_interval: 10s
        endpoint: ${env:SPLUNK_DBMON_POSTGRES_ENDPOINT}
        databases:
          - streaming
        username: ${env:SPLUNK_DBMON_POSTGRES_USERNAME}
        password: ${env:SPLUNK_DBMON_POSTGRES_PASSWORD}
        events:
          db.server.query_sample:
            enabled: true
          db.server.top_query:
            enabled: true
        tls:
          insecure: true

    exporters:
      otlphttp/dbmon:
        headers:
          X-SF-Token: ${env:SPLUNK_DBMON_ACCESS_TOKEN}
          X-splunk-instrumentation-library: dbmon
        logs_endpoint: ${env:SPLUNK_DBMON_EVENT_ENDPOINT}
        sending_queue:
          batch:
            flush_timeout: 15s
            max_size: 10485760 # 10 MiB
            sizer: bytes

    service:
      pipelines:
        metrics/dbmon:
          # Keep the DBMON metrics and logs pipelines aligned with each other.
          receivers:
            - postgresql
          processors:
            - memory_limiter
            - batch
            - resourcedetection
            - resource/add_environment
          exporters:
            - signalfx

        logs/dbmon:
          receivers:
            - postgresql
          # Match the metrics/dbmon processors exactly and in the same order.
          processors:
            - memory_limiter
            - batch
            - resourcedetection
            - resource/add_environment
          exporters:
            - otlphttp/dbmon
```

Adjust the placeholders before you apply it:

- Replace `streaming-postgres.streaming-service-app.svc.cluster.local:5432` if you deployed into a different namespace
- Replace the `logs_endpoint` realm if you are not on `us1`
- Use a Kubernetes Secret name that matches your collector namespace and secret-management approach
- If you need a different DBMON processor list in your environment, update both `metrics/dbmon` and `logs/dbmon` together so they stay identical
- Preserve your existing non-DBMON cluster receiver pipelines and only add the PostgreSQL-specific pieces

## Apply The Change

Create or update the secret in the collector namespace:

```bash
kubectl -n <collector-namespace> create secret generic streaming-postgres-dbmon \
  --from-literal=username='<postgres-username>' \
  --from-literal=password='<postgres-password>' \
  --from-literal=access-token='<splunk-dbmon-access-token>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

Then rerun the Helm upgrade for your existing collector release and layer the override file after your normal values:

```bash
helm upgrade <collector-release> splunk-otel-collector-chart/splunk-otel-collector \
  -n <collector-namespace> \
  -f <your-existing-values.yaml> \
  -f k8s/otel-splunk/postgresql-dbmon.values.yaml
```

## Validate

Repo-side validation helpers:

```bash
bash skills/deploy-streaming-app/tests/postgresql-db-monitoring-config.test.sh
bash skills/deploy-streaming-app/tests/postgresql-db-monitoring-live-smoke.test.sh
```

The live smoke test defaults to the repo namespaces and deployment names, but you can override `APP_NAMESPACE`, `OTEL_NAMESPACE`, `POSTGRES_ENDPOINT`, and related variables when your deployment differs. It checks the running cluster receiver config, PostgreSQL `pg_stat_statements` readiness, and fresh collector self-metrics exposed on port `8899`.

After the Helm upgrade:

- Confirm the cluster receiver pod restarted cleanly
- Check the cluster receiver logs for `postgresql` receiver startup or auth errors
- Verify PostgreSQL metrics appear in Splunk Observability Cloud
- Verify query samples and top queries arrive through the `logs/dbmon` pipeline

Remember that the `dbmon` exporter sends query events to the Splunk Observability Cloud event endpoint, not to the metrics endpoint.

## PostgreSQL Server Logs

PostgreSQL server log forwarding is a separate decision from Splunk Observability Database Monitoring.

- Ask explicitly whether the user also wants PostgreSQL server logs collected
- Do not assume that DB log forwarding is part of the default repo flow
- In this environment, default that answer to `no` unless the user confirms they have Splunk Platform access, whether Splunk Cloud Platform or Splunk Enterprise, and want to design that path too
- If that answer changes, collect the exact HEC endpoint, token, and index requirements before you add a log-forwarding path

## Related Repo Settings

[`example.env`](../example.env) now includes optional `SPLUNK_DBMON_*` placeholders so the deploy skill can reuse the same variable names when it guides users through this collector change.
