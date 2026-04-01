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

## Frontend Gateway

The Kubernetes frontend now runs as a small Node.js gateway service instead of a static NGINX container:

- `frontend/server.js` serves the built frontend assets and proxies the demo auth, catalog, media, billing, and public endpoints.
- `k8s/frontend/deployment.yaml` enables `instrumentation.opentelemetry.io/inject-nodejs: otel-splunk/splunk-otel-collector`.
- `OTEL_SERVICE_NAME=streaming-frontend`
- `OTEL_PROPAGATORS=baggage,b3,tracecontext`

This makes the frontend visible in Splunk APM as its own service while still preserving Browser RUM in the page and full trace propagation into the Java backends.

Because these annotations explicitly target `otel-splunk/splunk-otel-collector`, any reused collector install must preserve that namespace and instrumentation name or the repo-managed bootstrap path should be used instead.

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
- `SPLUNK_RUM_ACCESS_TOKEN`
- `SPLUNK_RUM_APP_NAME`
- `SPLUNK_DEPLOYMENT_ENVIRONMENT`

The frontend build stamps the current app version into the RUM config and then runs:

- `splunk-rum sourcemaps inject --path dist`
- `splunk-rum sourcemaps upload --app-name streaming-app-frontend --app-version <version> --path dist`

`scripts/frontend/deploy.sh` will skip upload unless both `SPLUNK_REALM` and `SPLUNK_RUM_ACCESS_TOKEN` are set. When the upload endpoint returns an error, the deploy scripts warn and continue instead of aborting the rollout.

Session replay is enabled for the Kubernetes frontend. It uses the same Splunk realm and RUM access token as browser RUM.

The frontend session replay configuration is intentionally permissive for this demo surface:

- Browser RUM text masking is disabled so page text remains visible in replay.
- Session replay text masking is disabled.
- The billing workspace is masked in browser RUM and session replay, and password inputs remain masked elsewhere in the app.
- Video element capture is enabled so the screening player and broadcast surfaces are visible in replay.

For a multi-service ThousandEyes Service Map in this demo environment, target:

- `http://<frontend-load-balancer>/api/v1/demo/public/trace-map`

That endpoint enters through `media-service-demo` and fans out to `user-service-demo`, `content-service-demo`, and `billing-service` in a single trace.

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
