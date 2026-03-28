# Broadcast Operations Frontend

This folder contains a broadcast-style frontend for the `streaming-service-app` repository. The frontend is built into a `dist/` directory for cluster deployment so it can ship minified JavaScript bundles, source maps, and Splunk RUM instrumentation metadata.

## Purpose

- Provide a concrete operator-facing surface for the project.
- Present the app like a small broadcast control room instead of a generic media grid.
- Reflect backend readiness with auth, catalog, and media health probes.
- Keep a fallback library so the UI is still reviewable even when the protected backend catalog is unavailable.

## Configuration

Create a repo-root `.env` from `example.env` when you want to override the checked-in frontend defaults without editing `frontend/config.js`:

```bash
cp example.env .env
```

The frontend build and `scripts/frontend/deploy.sh` both read that repo-root `.env`. The main Splunk Observability settings are:

```bash
STREAMING_ENVIRONMENT_LABEL=Primary Operations
SPLUNK_REALM=us1
SPLUNK_ACCESS_TOKEN=<browser-rum-access-token>
SPLUNK_RUM_APP_NAME=streaming-app-frontend
SPLUNK_DEPLOYMENT_ENVIRONMENT=streaming-app
```

`SPLUNK_REALM`, `SPLUNK_ACCESS_TOKEN`, and `SPLUNK_RUM_APP_NAME` are also reused for source map upload during deploy, so you only have to set them once.

## Local Preview

From the repository root:

```bash
cd frontend && npm install
npm run build
python3 -m http.server 8080 -d dist
```

Then open `http://localhost:8080`.

`python3 -m http.server` serves the built static assets only. The protected demo APIs are not proxied in this mode, so the UI will fall back to the seeded program library and local-only interactions.

## Cluster Deploy

From the repository root:

```bash
zsh scripts/frontend/deploy.sh
```

This creates a namespace, installs frontend dependencies if needed, builds `dist/`, injects Splunk source map IDs into the production bundles, uploads source maps when `SPLUNK_REALM` and `SPLUNK_ACCESS_TOKEN` are set, deploys the frontend gateway, and exposes the frontend through a Kubernetes `LoadBalancer` service. The gateway serves the static broadcast console, proxies the demo auth, catalog, media, billing, and public health endpoints, and is auto-instrumented as its own Node.js APM service.
