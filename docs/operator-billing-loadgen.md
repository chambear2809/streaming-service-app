# Operator, Billing, and Commerce Load Generator

The protected load generator simulates authenticated booth traffic against the
demo frontend instead of calling backend services directly.

It exercises:

- `/api/v1/demo/auth/persona/{persona}` and `/api/v1/demo/auth/session`
- `/api/v1/demo/content` and selected RTSP control endpoints
- `/api/v1/billing/invoices` and `/api/v1/billing/events`
- `/api/v1/customers`
- `/api/v1/payments/card-holder` and `/api/v1/payments/transactions`
- `/api/v1/subscription/all` and `/api/v1/subscription/active`
- `/api/v1/orders` plus `/api/v1/orders/{id}/status`
- `/api/v1/demo/public/broadcast/current` and `/api/v1/demo/public/trace-map`

## Local Run

```bash
node scripts/loadgen/operator-billing-loadgen.mjs \
  --base-url http://127.0.0.1:8080 \
  --persona operator \
  --duration 6m
```

## In-Cluster Run

```bash
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

The deploy helper mounts the Node script into a `node:22-alpine` job pod and
points it at `streaming-frontend.<namespace>.svc.cluster.local` by default.

To keep the protected load generator running on a regular cadence inside the
cluster, deploy it as a `CronJob` instead of a one-shot `Job`:

```bash
LOADGEN_OPERATOR_K8S_MODE=cronjob \
LOADGEN_OPERATOR_CRON_SCHEDULE='*/20 * * * *' \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

## Recommended Profiles

- `Warm-up`: `1` worker, `5m` duration, `6s` pause. Use this before a demo starts when you want a low-risk authenticated trickle.
- `Booth default`: `3` workers, `8m` duration, `4s` pause. This is the default profile for keeping the protected suite warm during live demos.
- `Stress`: `5` workers, `10m` duration, `3s` pause. Use this when you want more aggressive Accounts, Payments, Commerce, and media-control traffic.

The deploy helper defaults to `LOADGEN_OPERATOR_PROFILE=booth`. Set
`LOADGEN_OPERATOR_PROFILE=warmup`, `booth`, `stress`, or `custom` depending on
the mix you want. When you choose `custom`, the explicit
`LOADGEN_OPERATOR_*` variables win and no preset is applied.

## Operator Actions

Use `LOADGEN_OPERATOR_K8S_ACTION` to manage the recurring load without editing
manifests:

- `apply`: create or update the one-shot `Job` or recurring `CronJob`
- `status`: show the current `Job` or `CronJob` plus matching jobs and pods
- `delete`: remove the current `Job` or `CronJob`
- `pause`: set `spec.suspend=true` on the recurring `CronJob`
- `resume`: set `spec.suspend=false` on the recurring `CronJob`
- `trigger`: create a one-off `Job` immediately from the current `CronJob` template

Important environment variables:

- `NAMESPACE` or `STREAMING_K8S_NAMESPACE`
- `LOADGEN_OPERATOR_PROFILE`
- `LOADGEN_OPERATOR_K8S_MODE`
- `LOADGEN_OPERATOR_K8S_ACTION`
- `LOADGEN_OPERATOR_CRONJOB_NAME`
- `LOADGEN_OPERATOR_CRON_SCHEDULE`
- `LOADGEN_OPERATOR_CRON_CONCURRENCY_POLICY`
- `LOADGEN_OPERATOR_CRON_SUSPEND`
- `LOADGEN_OPERATOR_CRON_SUCCESS_HISTORY`
- `LOADGEN_OPERATOR_CRON_FAILED_HISTORY`
- `LOADGEN_OPERATOR_TRIGGER_JOB_NAME`
- `LOADGEN_OPERATOR_BASE_URL`
- `LOADGEN_OPERATOR_PERSONA`
- `LOADGEN_OPERATOR_DURATION`
- `LOADGEN_OPERATOR_CONCURRENCY`
- `LOADGEN_OPERATOR_PAUSE`
- `LOADGEN_OPERATOR_REQUEST_TIMEOUT`
- `LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE`
- `LOADGEN_OPERATOR_BILLING_EVENT_RATIO`
- `LOADGEN_OPERATOR_PAYMENT_RATIO`
- `LOADGEN_OPERATOR_PAYMENT_READ_RATIO`
- `LOADGEN_OPERATOR_COMMERCE_READ_RATIO`
- `LOADGEN_OPERATOR_ORDER_CREATE_RATIO`
- `LOADGEN_OPERATOR_ORDER_SETTLE_RATIO`
- `LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO`
- `LOADGEN_OPERATOR_RTSP_JOB_RATIO`
- `LOADGEN_OPERATOR_TAKE_LIVE_RATIO`

Booth-default example:

```bash
LOADGEN_OPERATOR_PROFILE=booth \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Stress example:

```bash
LOADGEN_OPERATOR_PROFILE=stress \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Recurring booth-default example:

```bash
LOADGEN_OPERATOR_PROFILE=booth \
LOADGEN_OPERATOR_K8S_MODE=cronjob \
LOADGEN_OPERATOR_CRON_SCHEDULE='*/20 * * * *' \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Inspect the recurring load:

```bash
LOADGEN_OPERATOR_K8S_MODE=cronjob \
LOADGEN_OPERATOR_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Pause and resume the recurring load:

```bash
LOADGEN_OPERATOR_K8S_MODE=cronjob \
LOADGEN_OPERATOR_K8S_ACTION=pause \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh

LOADGEN_OPERATOR_K8S_MODE=cronjob \
LOADGEN_OPERATOR_K8S_ACTION=resume \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Trigger the recurring template immediately:

```bash
LOADGEN_OPERATOR_K8S_MODE=cronjob \
LOADGEN_OPERATOR_K8S_ACTION=trigger \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

## Output

The script prints rolling worker-cycle summaries and a final JSON document with:

- session and cycle counts
- customer, payment-workspace, and commerce-workspace reads
- billing events, invoice captures, RTSP jobs, and broadcast activations
- order creation and settlement counts
- subscription activation and completion verification counts
- per-endpoint request counts, failures, status codes, and latency summaries
