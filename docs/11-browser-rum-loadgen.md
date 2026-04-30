# 11. Browser RUM Load Generator

The browser RUM load generator launches real Playwright browser contexts. Use it
when the demo needs actual Splunk Browser RUM and session replay traffic, not
just backend/API traffic from Node `fetch()`.

It exercises:

- `/broadcast` by default, or any comma-separated paths in `LOADGEN_BROWSER_PATHS`
- the frontend JavaScript bundle and `splunk-instrumentation.js`
- browser clicks, movement, scrolling, public status fetches, and trace-map fetches
- in-session navigation across the configured paths so RUM page views are not a
  single startup burst
- Splunk RUM and session replay beacon export from the browser runtime

The existing broadcast and operator load generators are still useful for APM and
API pressure. They do not execute browser JavaScript, so they do not generate
Browser RUM.

## Local Run

Install Playwright where the script can resolve it:

```bash
npm install --no-save --prefix scripts/loadgen playwright@1.56.1
npm --prefix scripts/loadgen exec -- playwright install chromium
```

Then run a short browser session:

```bash
node scripts/loadgen/browser-rum-loadgen.mjs \
  --base-url http://127.0.0.1:8080 \
  --target-browsers 2 \
  --duration 2m
```

The script prints an egress IP using `https://checkip.amazonaws.com`. Splunk RUM
should see that same public egress address for the browser beacon connections,
subject to any proxy, VPN, or NAT in the path.

## In-Cluster Run

```bash
zsh scripts/loadgen/deploy-k8s-browser-rum-loadgen.sh
```

The deploy helper creates a ConfigMap for the script and runs it in the official
Playwright container image. The container installs the matching `playwright`
package at startup while skipping browser binary downloads because the image
already contains the browsers.

By default the wrapper pins the pod to the private router-egress nodegroup:

- `nodeSelector`: `eks.amazonaws.com/nodegroup=private`
- `toleration`: `dedicated=otel:NoSchedule`

That is intentional for the EKS delay demo. It makes the browser pod's outbound
RUM beacon traffic leave through the same router EIP used by collector egress.
If your cluster does not have that nodegroup or taint, disable the placement:

```bash
LOADGEN_BROWSER_ROUTER_EGRESS=false \
zsh scripts/loadgen/deploy-k8s-browser-rum-loadgen.sh
```

## Recurring Browser RUM

To keep Browser RUM warm during a booth session, deploy it as a CronJob:

```bash
LOADGEN_BROWSER_K8S_MODE=cronjob \
LOADGEN_BROWSER_PROFILE=booth \
zsh scripts/loadgen/deploy-k8s-browser-rum-loadgen.sh
```

The profile defaults are:

- `warmup`: `3` browser contexts, `8m`, scheduled every `5m`
- `booth`: `6` browser contexts, `15m`, scheduled every `5m`
- `stress`: `16` browser contexts, `15m`, scheduled every `5m`
- `custom`: use explicit `LOADGEN_BROWSER_*` values

Playwright and Chromium are heavier than the Node fetch load generators,
especially on the broadcast page. The Kubernetes wrapper defaults to a `1000m`
CPU request, `3000m` CPU limit, `2048Mi` memory request, and `6144Mi` memory
limit. Tune `LOADGEN_BROWSER_CPU_REQUEST`, `LOADGEN_BROWSER_CPU_LIMIT`,
`LOADGEN_BROWSER_MEMORY_REQUEST`, and `LOADGEN_BROWSER_MEMORY_LIMIT` if the
target cluster is smaller or the profile is more aggressive.

The recurring profiles intentionally default to `concurrencyPolicy=Allow`. A new
run starts before the prior run drains, which smooths the RUM charts into a
continuous band instead of producing the isolated spike pattern common with
one-shot jobs.

Set `LOADGEN_BROWSER_BASE_URL` when you want the browser to load a router-backed
public URL instead of the default in-cluster frontend Service URL.

The default path list is weighted by repetition:

```bash
LOADGEN_BROWSER_PATHS=/broadcast,/broadcast,/,/#operations,/demo-monkey
```

That keeps most sessions on the public broadcast surface while still producing
operator and Demo Monkey page views. Set `LOADGEN_BROWSER_AUTH_PERSONA=operator`
when protected pages should load with an authenticated demo session.

## Validation

Useful checks after the job starts:

```bash
LOADGEN_BROWSER_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-browser-rum-loadgen.sh

kubectl -n streaming-service-app logs job/browser-rum-loadgen --all-containers=true
```

The final JSON summary includes:

- `egressIp`
- `sessionsStarted`, `sessionsCompleted`, and `sessionsFailed`
- `rumRequests`, `rumResponses`, `rumFailures`, and RUM response statuses
- page and console error samples

If `rumRequests` stays at `0`, confirm the frontend build includes a Browser RUM
token and that the page loads `splunk-instrumentation.js`.
