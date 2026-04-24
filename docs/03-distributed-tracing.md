# 03. Distributed Tracing

This repo's Kubernetes demo services use the Splunk OpenTelemetry Operator Java auto-instrumentation path:

- `k8s/backend-demo/content-service.yaml`
- `k8s/backend-demo/media-service.yaml`
- `k8s/backend-demo/user-service.yaml`
- `k8s/backend-demo/billing-service.yaml`

For PostgreSQL database monitoring of `streaming-postgres`, use the separate guide in [`docs/04-postgresql-db-monitoring.md`](04-postgresql-db-monitoring.md). Database monitoring is a collector configuration change, not part of the app-side Java or Node.js auto-instrumentation described here.

If the cluster does not already have the repo-compatible collector install, bootstrap it first with [`docs/02-splunk-otel-collector-bootstrap.md`](02-splunk-otel-collector-bootstrap.md) or run the canonical deploy flow with `--splunk-otel-mode install-if-missing`.

## Runtime Requirements

The app containers must keep the following OpenTelemetry propagator order so ThousandEyes metadata is preserved in Splunk APM:

```text
OTEL_PROPAGATORS=baggage,b3,tracecontext
```

This avoids the `b3` propagator clearing `tracestate`, which breaks the ThousandEyes link back from Splunk Observability Cloud.

The backend-demo manifests already set:

- `instrumentation.opentelemetry.io/inject-java: otel-splunk/splunk-otel-collector`
- `OTEL_SERVICE_NAME`
- `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- `OTEL_PROPAGATORS=baggage,b3,tracecontext`

After updating these environment variables, restart the affected deployments so the injected Java agent picks them up.

The repo-compatible collector path for those workloads is:

- `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://splunk-otel-collector-agent.otel-splunk.svc.cluster.local:4317`

Use `4317` here, not `4318`. The collector bootstrap helper also renders the agent Service to `internalTrafficPolicy=Cluster` so app pods on non-otel nodes can still reach the private collector agents.

## Frontend Gateway

The Kubernetes frontend now runs as a small Node.js gateway service instead of a static NGINX container:

- `frontend/server.js` serves the built frontend assets and proxies the demo auth, catalog, media, billing, and public endpoints.
- `k8s/frontend/deployment.yaml` enables `instrumentation.opentelemetry.io/inject-nodejs: otel-splunk/splunk-otel-collector`.
- `OTEL_SERVICE_NAME=streaming-frontend`
- `OTEL_PROPAGATORS=baggage,b3,tracecontext`

This makes the frontend visible in Splunk APM as its own service while still preserving Browser RUM in the page and full trace propagation into the Java backends.

Because these annotations explicitly target `otel-splunk/splunk-otel-collector`, any reused collector install must preserve that namespace and instrumentation name or the repo-managed bootstrap path should be used instead.

## APM Search Tips

The canonical deploy paths now render `deployment.environment` from `SPLUNK_DEPLOYMENT_ENVIRONMENT`, with `streaming-app` as the default when that variable is unset.

That means:

- when you deploy through the repo scripts, APM and collector-side telemetry use the same environment label
- if you search a default deployment, start with `deployment.environment=streaming-app`
- if you set `SPLUNK_DEPLOYMENT_ENVIRONMENT` to a custom label, search with that custom value

In the live cluster, the most useful service names to search first were:

- `streaming-frontend`
- `media-service-demo`
- `user-service-demo`
- `content-service-demo`
- `billing-service`
- `ad-service-demo`

If you want a known multi-service trace shape, start with `media-service-demo`. The public trace-map path fans out into multiple downstream services in one request.

## Live Smoke Test

For a live cluster verification of the app-to-collector and collector-to-Splunk trace path, use:

```bash
bash skills/deploy-streaming-app/tests/splunk-otel-tracing-live-smoke.test.sh
```

When the app was deployed into a different namespace, override `APP_NAMESPACE`, for example:

```bash
APP_NAMESPACE=streaming-demo \
  bash skills/deploy-streaming-app/tests/splunk-otel-tracing-live-smoke.test.sh
```

The smoke test:

- checks the live collector shape that this repo depends on, including `internalTrafficPolicy=Cluster` and OTLP gRPC on `4317`
- generates `trace-map` requests against `streaming-frontend`
- scrapes collector self-metrics from every agent pod
- fails if accepted spans do not rise, if exporter failure counters rise, or if recent collector logs show errors like `no such host`, `unsupported protocol scheme`, or `RBAC: access denied`

It also prints the live `deployment.environment` value from `streaming-frontend` so the resulting environment filter is obvious when you open Splunk APM.

## Browser RUM

The static frontend also initializes Splunk browser RUM before the page application scripts run:

- `frontend/package.json` declares `@splunk/otel-web` and `@splunk/otel-web-session-recorder`
- `frontend/splunk-instrumentation.js` initializes the browser agent and session replay
- `frontend/scripts/build.mjs` produces minified bundles and source maps in `frontend/dist`, and reads overrides from the repo-root `.env` in addition to normal shell env vars
- `scripts/frontend/deploy.sh` reads the same repo-root `.env`, builds the frontend, injects source map IDs, and uploads source maps when Splunk auth env vars are present

The checked-in frontend defaults use:

- Realm: `us1`
- Application name: `streaming-app-frontend`
- Deployment environment: `streaming-app`

Override them in the repo-root `.env` instead of editing `frontend/config.js` directly:

- `STREAMING_ENVIRONMENT_LABEL`
- `STREAMING_PUBLIC_RTSP_URL`
- `SPLUNK_REALM`
- `SPLUNK_ACCESS_TOKEN`
- `SPLUNK_RUM_ACCESS_TOKEN`
- `SPLUNK_RUM_APP_NAME`
- `SPLUNK_DEPLOYMENT_ENVIRONMENT`

The frontend build stamps the current app version into the RUM config and then runs:

- `splunk-rum sourcemaps inject --path dist`
- `splunk-rum sourcemaps upload --app-name streaming-app-frontend --app-version <version> --path dist`

`scripts/frontend/deploy.sh` will skip upload unless both `SPLUNK_REALM` and `SPLUNK_ACCESS_TOKEN` are set, unless you explicitly override the upload token with `SPLUNK_SOURCEMAP_UPLOAD_TOKEN`. When the upload endpoint returns an error, the deploy scripts now retry with bounded backoff before they warn and continue instead of aborting the rollout. You can also rerun `scripts/frontend/upload-sourcemaps.sh` against the current `frontend/dist`; it reuses the repo-root `.env` when present.

Session replay is enabled for the Kubernetes frontend. It uses the same Splunk realm and RUM access token as browser RUM.

The frontend session replay configuration is intentionally permissive for this demo surface:

- Browser RUM text masking is disabled so page text remains visible in replay.
- Session replay text masking is disabled.
- The billing workspace is masked in browser RUM and session replay, and password inputs remain masked elsewhere in the app.
- Video element capture is enabled so the screening player and broadcast surfaces are visible in replay.

For a multi-service ThousandEyes Service Map in this demo environment, target:

- `http://<frontend-load-balancer>/api/v1/demo/public/trace-map`

That endpoint enters through `media-service-demo` and fans out to `user-service-demo`, `content-service-demo`, and `billing-service` in a single trace.

Only the HTTP ThousandEyes tests generate app traces. The RTSP, UDP, and RTP ThousandEyes tests are still useful for network and media validation, but they do not create APM spans by themselves. For stronger APM validation, use the repo load generators in [`docs/07-broadcast-loadgen.md`](07-broadcast-loadgen.md) and [`docs/08-operator-billing-loadgen.md`](08-operator-billing-loadgen.md).

## ThousandEyes Connector

Create a Generic Connector in ThousandEyes with:

- Target URL: `https://api.<REALM>.signalfx.com`
- Header `X-SF-Token: <Splunk access token with API scope>`

Then create and enable a `Splunk Observability APM` operation.

## Splunk Validation

Once deployed, open the ThousandEyes Service Map and follow the trace link into Splunk Observability Cloud. You should see traces enriched with ThousandEyes attributes such as:

- `thousandeyes.account.id`
- `thousandeyes.test.id`
- `thousandeyes.permalink`
- `thousandeyes.source.agent.id`

For the full traffic and collector egress diagram, including the private-node collector path and router EIP `44.208.125.119`, read [`docs/09-splunk-otel-traffic-architecture.md`](09-splunk-otel-traffic-architecture.md).
