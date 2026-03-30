# Kubernetes Deployment Learning Guide

This guide is for someone who already has `kubectl` access to a Kubernetes cluster and wants to get the canonical `streaming-service-app` demo deployed from this repo.

It is intentionally explanatory. The goal is not just to give you a command to paste, but to make it clear what the repo is doing, what a normal rollout looks like, how to tell whether it is healthy, and where to go next after the base deployment works.

If you want the short version, the main deploy command is:

```bash
bash skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
```

Everything else in this document explains how to use that command safely and what to expect from it.

## What This Guide Deploys

The canonical Kubernetes deploy path in this repo brings up the current demo slice:

- PostgreSQL as `streaming-postgres`
- `content-service-demo`
- `media-service-demo`
- `user-service-demo`
- `billing-service`
- `ad-service-demo`
- `streaming-frontend`

That is the current recommended cluster demo path for this repository.

This guide does not deploy the broader set of optional business services such as `customer-service`, `payment-service`, `subscription-service`, or `order-service`. Those exist in the repo, but they are not part of the current canonical deployment flow.

## What You Need Before You Start

You said the main prerequisite is already satisfied: `kubectl` access is working.

That is necessary, but not sufficient by itself. The deploy flow in this repo also assumes:

- you are running the commands from inside a local checkout of this repo
- `git`, `tar`, `node`, and `npm` are installed on the workstation where you run the deploy command
- the cluster can pull the container images used by the demo
- the cluster can reach Maven repositories, or an internal Maven mirror, because the Java services are compiled inside the cluster during this deploy flow

That last point matters. Even if your cluster can pull container images successfully, the rollout can still fail later if the build containers cannot download Java dependencies.

## Step 1: Open The Repo And Choose A Namespace

Start in the repo root:

```bash
cd /path/to/streaming-service-app
```

Pick a namespace for this deployment. In this guide, the examples use `streaming-demo`.

Use a lowercase Kubernetes-safe name. The deploy script validates this and will reject names that are not lowercase RFC 1123 labels.

Good example:

```bash
streaming-demo
```

Bad examples:

```text
Streaming-Demo
streaming_demo
streaming.demo
```

Why this matters:

- the deploy flow renders checked-in manifests at apply time
- the namespace gets injected into service URLs used by the frontend and other helper logic
- if the namespace format is invalid, it is better to fail immediately than to half-deploy and debug broken references later

## Step 2: Create A Repo-Root `.env`

Create a local `.env` file from the checked-in template:

```bash
cp example.env .env
```

This is the first thing to do even if you are not planning to use Splunk, ThousandEyes, or DB monitoring on day one.

Why this matters:

- the deploy script reads `.env` automatically
- the template gives you safe demo defaults for the base deployment
- it gives you one place to fill in optional follow-on values later without rewriting commands

For a basic Kubernetes deployment, you do not need to fill in every variable in `.env`.

You can leave the following areas blank if you only want the app running:

- Splunk Observability API tokens
- Browser RUM token
- PostgreSQL DB monitoring values
- ThousandEyes values

Those settings are optional follow-on steps, not blockers for the base app rollout.

### Optional: Pin The Demo Login Password

The deploy script can generate demo auth credentials automatically, but you can also choose to set them yourself in `.env` before deploying:

```dotenv
DEMO_AUTH_PASSWORD=YourChosenPassword
DEMO_AUTH_SECRET=some-long-random-secret
```

If you do not set them:

- the script generates them
- the script prints the resulting demo password at the end of the rollout

That is usually the easiest path for a first deployment.

## Step 3: Run The Canonical Kubernetes Deploy Command

Run the deploy script:

```bash
bash skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
```

### What Each Part Means

`bash skills/deploy-streaming-app/scripts/deploy-demo.sh`

- this is the repo's canonical full-demo deployment entry point
- it is the path the repo documentation and deploy skill treat as the current default

`--platform kubernetes`

- tells the script to use the standard Kubernetes behavior
- for Kubernetes, the script defaults the frontend service to `LoadBalancer`
- for Kubernetes, the RTSP service also defaults to `LoadBalancer`

`--namespace streaming-demo`

- tells the script where to deploy everything
- the script creates the namespace if it does not exist yet
- the script also rewrites rendered manifest content so the deployment points at the namespace you chose instead of the repo's historical default namespace

## Step 4: Understand What The Script Is Doing

When the deploy runs, it is doing more than a plain `kubectl apply`.

At a high level, the script:

1. verifies the local repo layout and required local tooling
2. creates the target namespace
3. stages backend service source archives into ConfigMaps
4. creates or updates demo auth secrets
5. deploys PostgreSQL first
6. deploys the backend demo services
7. builds the frontend locally
8. creates frontend asset ConfigMaps
9. deploys the frontend service and deployment
10. waits for rollout and tries to discover access URLs

This explains why the flow can take longer than a simple manifest apply:

- the Java services are not just started, they are built as part of this process
- the frontend is rebuilt as part of the deployment
- the script waits for workloads to become usable instead of exiting immediately after applying YAML

## Step 5: Read The Rollout Output Correctly

This repo's deploy script prints rollout progress snapshots on purpose. Do not treat the extra output as noise. It is there because some parts of the demo legitimately take time, and without those snapshots a healthy rollout can look stuck.

### What Is Normal

These behaviors are expected:

- PostgreSQL comes up first
- backend services roll after PostgreSQL is ready
- `media-service-demo` can take noticeably longer than the other services
- the frontend is applied later, after the backend and frontend assets are prepared

The most important special case is `media-service-demo`.

It can spend several minutes in its `stage-demo-movie` initialization work while it downloads the demo source media and prebuilds 90-second loop segments. That is normal in this repo. A long wait there is not automatically a failure.

### What Usually Means Trouble

These are the signals to pay attention to:

- restart counts that keep increasing
- `CrashLoopBackOff`
- `OOMKilled`
- build-related failures from the Java services
- no external address yet for the frontend, if you were expecting a `LoadBalancer`

The script is designed to surface these cases more clearly than a plain `kubectl rollout status`.

## Step 6: Check The Namespace After The Script Finishes

After the deploy command returns, inspect the namespace directly.

### Check The Pods

```bash
kubectl -n streaming-demo get pods
```

What you want to see:

- a pod for `streaming-postgres`
- pods for the demo services
- a pod for `streaming-frontend`
- pods in `Running` state once initialization is complete

If some pods are still becoming ready right after the script ends, wait a short time and check again.

### Check The Services

```bash
kubectl -n streaming-demo get service
```

What you want to see:

- `streaming-frontend`
- `media-service-demo-rtsp`
- the backend ClusterIP services for the demo services

On Kubernetes, the important detail is whether `streaming-frontend` received an external address. If it did, that is your easiest browser entry point.

## Step 7: Access The Frontend

At the end of the deploy, the script prints either:

- a discovered frontend URL
- or a `port-forward` hint if an external address was not automatically discoverable

### Best Case: The Script Prints A Frontend URL

If the script prints a line like this:

```text
Frontend URL: http://...
```

open that URL in your browser.

### Fallback: Use `port-forward`

If the script prints that the frontend URL was not automatically discoverable, use the fallback it suggests:

```bash
kubectl -n streaming-demo port-forward service/streaming-frontend 8080:80
```

Then open:

```text
http://localhost:8080
```

Why this fallback exists:

- on some clusters, `LoadBalancer` provisioning is slow
- on some lab clusters, external addresses never appear automatically
- `port-forward` gives you a reliable access path even when external exposure is delayed

## Step 8: Log In To The Demo

At the end of the rollout, the script prints the demo login password.

It also prints the available demo accounts and persona shortcuts.

The built-in persona shortcuts called out by the script are:

- `operator`
- `exec`
- `programming`

If you did not predefine `DEMO_AUTH_PASSWORD`, use the password printed by the deploy script output.

## Step 9: Run Basic Smoke Checks

Once the browser UI opens, do a quick sanity pass before moving on to Splunk or ThousandEyes work.

### Namespace-Level Smoke Checks

Run:

```bash
kubectl -n streaming-demo get pods
kubectl -n streaming-demo get service
```

Success means:

- the expected pods exist
- the services exist
- the frontend has a reachable path, either external or through `port-forward`

### UI-Level Smoke Checks

In the browser:

- load the main UI
- confirm the shell renders instead of returning a blank page or server error
- confirm the login flow works with one of the demo personas
- confirm the main broadcast surface loads without obvious backend failure banners

This is enough for a first “is the app deployed?” validation.

You do not need to configure Splunk, ThousandEyes, or DB monitoring before you can call the base deployment successful.

## Common Problems And What They Usually Mean

### `kubectl` Works, But The Script Fails Immediately

Usually this means one of the local non-cluster tools is missing.

This deploy flow also expects:

- `git`
- `tar`
- `node`
- `npm`

### The Namespace Is Rejected

The namespace must be lowercase and Kubernetes-safe.

Use names like:

```text
streaming-demo
demo-west
streaming-lab
```

Avoid uppercase letters, underscores, and dots.

### Pods Start, But Java Services Fail During Build Or Startup

The most likely cause is cluster egress.

Remember that this deploy flow still compiles the Java services inside the cluster. If those build containers cannot reach Maven repositories or your internal Maven mirror, the rollout can fail even though the cluster itself is otherwise healthy.

### The Frontend Has No External IP Yet

That does not always mean the deployment failed.

On some Kubernetes environments:

- `LoadBalancer` provisioning is delayed
- `LoadBalancer` provisioning is unavailable
- external IP assignment is controlled by infrastructure outside the namespace

Use:

```bash
kubectl -n streaming-demo port-forward service/streaming-frontend 8080:80
```

while you wait, or as a permanent lab access method.

### `media-service-demo` Looks Slow

This is common and often normal.

The repo deliberately warns that `media-service-demo` can spend a long time in `stage-demo-movie` while preparing demo media artifacts. Check for progress and restart reasons before assuming it is stuck.

### Frontend Source Map Upload Warnings

These are non-blocking for the base deployment.

If Splunk RUM settings are missing or the source map upload fails, the deploy flow warns and continues. That should not stop you from getting the app up and reachable.

## Recommended First-Day Command Set

If you want the shortest practical path for a first successful deployment, use this exact sequence:

```bash
cd /path/to/streaming-service-app
cp example.env .env
bash skills/deploy-streaming-app/scripts/deploy-demo.sh \
  --platform kubernetes \
  --namespace streaming-demo
kubectl -n streaming-demo get pods
kubectl -n streaming-demo get service
```

If the frontend URL is not printed or not reachable, add:

```bash
kubectl -n streaming-demo port-forward service/streaming-frontend 8080:80
```

Then open `http://localhost:8080`.

## Optional Next Steps After The Base Deploy Works

Once the base app is up, the next question is usually not “how do I deploy it?” but “how do I add observability and demo integrations?”

Use these follow-on docs:

### PostgreSQL DB Monitoring

Read [`docs/postgresql-db-monitoring.md`](postgresql-db-monitoring.md).

Use this when the base app is already deployed and you want Splunk Observability Cloud to collect PostgreSQL infrastructure metrics, query samples, and top-query events from `streaming-postgres`.

This is a follow-on OpenTelemetry Collector change. It is not part of the base app deploy script.

### Distributed Tracing And Browser Telemetry

Read [`docs/distributed-tracing.md`](distributed-tracing.md).

Use this when you want to understand how the repo wires Java, Node.js, and browser telemetry into Splunk Observability Cloud.

### ThousandEyes Test Setup

Read [`docs/thousandeyes-rtsp-api.md`](thousandeyes-rtsp-api.md).

Use this when the app is already reachable and you want to create the demo's RTSP, UDP, RTP, and HTTP synthetic tests.

If you are a Splunk Observability Cloud user and not a networking specialist, read that doc as a plain-English synthetic monitoring guide, not as a telecom manual.

The easiest way to think about the ThousandEyes test types in this demo is:

- HTTP tests tell you whether users can reach the public frontend and API paths
- the RTSP TCP test tells you whether the media control endpoint is reachable
- the UDP media-path test tells you whether two test locations can exchange media traffic
- the RTP test tells you whether real-time media quality would look healthy or degraded

That doc now also explains the terms `Enterprise Agent`, `Cloud Agent`, `source agent`, `target agent`, `account group`, `local`, and `external` in plain language.

It also now explains test direction and agent placement for this environment:

- HTTP and RTSP tests run from a source agent toward the app endpoints
- UDP and RTP tests run from a source agent toward a target agent
- for the current demo story, Ashburn is the "near the app" location and Singapore is the example "far away" Cloud Agent location
- the recommended pattern is to keep RTP near the app on Enterprise Agents and use the distant Cloud Agent pattern mainly for the UDP media-path test when you want to show geographic separation

For a first pass, start with the Kubernetes wrapper and the HTTP tests before you worry about the deeper media-path tests.

### Splunk Dashboard Sync

After ThousandEyes tests are live, use the dashboard sync guidance and script described in [`docs/thousandeyes-rtsp-api.md`](thousandeyes-rtsp-api.md).

That is the path for creating the matching Splunk demo dashboards once the tests already exist.

## Final Advice

For a first deployment, keep the goal narrow:

1. get the namespace up
2. get the pods healthy
3. get the frontend reachable
4. log in successfully

Do not make the first run harder than it needs to be by mixing in PostgreSQL DB monitoring, ThousandEyes setup, and dashboard creation before the base application works.

Once the base deployment is stable, the rest of the repo's specialized docs become much easier to follow.
