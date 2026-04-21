# EKS Delay Demo

Use this flow when you want a dedicated AWS demo environment where:

- a two-NIC EC2 router sits in front of the streaming app public entrypoints
- the same EC2 also acts as NAT for the EKS private subnets
- `tc netem` can be toggled on demand to move ThousandEyes latency, jitter, and RTP quality metrics
- a ThousandEyes Enterprise Agent runs inside the cluster on the private side

## Prerequisites

Populate `.env` or export these variables before you deploy:

- `SPLUNK_REALM`
- `SPLUNK_ACCESS_TOKEN`
- `THOUSANDEYES_BEARER_TOKEN`
- `THOUSANDEYES_ACCOUNT_GROUP_ID`
- `TEAGENT_ACCOUNT_TOKEN`
- `TE_APP_SOURCE_AGENT_IDS`
- `TE_TARGET_AGENT_ID`

Optional but commonly used:

- `TE_UDP_TARGET_AGENT_ID`
- `SPLUNK_RUM_ACCESS_TOKEN`
- `SPLUNK_RUM_APP_NAME`
- `ROUTER_SSH_CIDR`

The default router EC2 key pair is `appd-tme-key`, and the default local PEM path is:

`/Users/alecchamberlain/Desktop/Splunk TME/AppD TME/aws/appd-tme-key.pem`

## Deploy

From the repo root:

```bash
bash scripts/aws/deploy-eks-delay-demo.sh
```

Common overrides:

```bash
bash scripts/aws/deploy-eks-delay-demo.sh \
  --region us-east-1 \
  --cluster-name streaming-eks-delay-demo \
  --namespace streaming-demo \
  --router-ssh-cidr 203.0.113.10/32 \
  --eks-public-access-cidrs 203.0.113.10/32
```

By default the script detects the current public IPv4 address and restricts the
EKS public API endpoint to that `/32` while also enabling private API access for
nodes inside the VPC. Override `--eks-public-access-cidrs` when you need a
different trusted source range.

When resuming from an existing state file, explicitly passed values for
`--eks-public-access-cidrs`, `--integration-mode`, and `--kubeconfig` override
the persisted state so you can safely narrow API access or write a fresh
kubeconfig on rerun.

On success the script prints:

- the generated state file path
- the generated kubeconfig path
- the router Elastic IP
- the public frontend URL
- the public RTSP URL
- the discovered in-cluster ThousandEyes Enterprise Agent ID

For ThousandEyes and Splunk dashboard identity, this path stays separate from the
repo's primary cluster flow:

- the delay-demo ThousandEyes tests default to cluster-scoped names like
  `RTSP-TCP-8554 (streaming-eks-delay-demo)` and
  `aleccham-broadcast-trace-map (streaming-eks-delay-demo)`
- the script persists those delay-demo-specific test IDs in the generated state
  file so reruns reconcile the same second-cluster test set instead of
  accidentally targeting the repo-global `TE_*_TEST_ID` values from `.env`
- the Splunk sync step writes a separate dashboard group named
  `Streaming Service App ThousandEyes Tests <cluster-name>`

## Toggle Delay

Inspect the current router qdisc state:

```bash
bash scripts/aws/router-delay.sh status
```

Enable fixed latency:

```bash
bash scripts/aws/router-delay.sh enable --delay-ms 100
```

Enable latency plus jitter:

```bash
bash scripts/aws/router-delay.sh enable --delay-ms 100 --jitter-ms 20
```

Enable latency, jitter, and loss for a stronger RTP / MOS impact:

```bash
bash scripts/aws/router-delay.sh enable --delay-ms 120 --jitter-ms 30 --loss-pct 1
```

Disable impairment:

```bash
bash scripts/aws/router-delay.sh disable
```

## Validate Delay

To prove the public path is actually slower, not just that `tc` is configured,
run the validator. It:

- resets the router to a clean baseline
- measures frontend time-to-first-byte from your workstation
- applies the requested `netem` delay on the router
- verifies the router qdisc shows that delay
- measures again and checks that the median frontend latency increased by at
  least the configured threshold
- disables the delay again unless you pass `--keep-delay`

Example:

```bash
bash scripts/aws/validate-router-delay.sh validate --delay-ms 120 --samples 5
```

Useful overrides:

```bash
bash scripts/aws/validate-router-delay.sh validate \
  --delay-ms 150 \
  --expected-min-increase-ms 150 \
  --samples 7 \
  --probe-url http://44.208.125.119/
```

The default probe target is `http://ROUTER_PUBLIC_IP/`, derived from the
generated state file.

## Destroy

Tear the environment down with the generated state file:

```bash
bash scripts/aws/destroy-eks-delay-demo.sh
```

If you used a non-default state file:

```bash
bash scripts/aws/destroy-eks-delay-demo.sh --state-file .generated/aws/custom-demo.env
```

## Notes

- The EKS API server is not placed behind the router.
- The router only exposes the demo ports (`80` and `8554`) to the internet. It
  does not open an ephemeral ingress range for NAT return traffic because EC2
  security groups are stateful.
- Public frontend and RTSP traffic traverse the router EIP before reaching the internal AWS load balancers.
- The deploy script waits for the router instance to reach the internal frontend
  and RTSP load balancers before it rewrites the HAProxy backends, which avoids
  the transient public `503` we saw when the NLB hostnames existed before their
  listeners were ready.
- Private-node internet egress also traverses the router, so the in-cluster ThousandEyes Enterprise Agent can participate in the impairment story.
- The router is intentionally a single instance for demo simplicity.
