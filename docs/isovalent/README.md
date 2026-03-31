# Isovalent Demo Kit

This directory keeps the Isovalent-specific demo material separate from the
existing booth runbooks.

## Files

- `layered-ops-demo.md`
  The standalone walkthrough for the ops-first Isovalent story.
- `expected_dashboards.json`
  The expected Splunk Observability dashboard groups and dashboard names for
  this environment.
- `verify_splunk_assets.py`
  A live verifier that reads the repo-root `.env`, queries Splunk
  Observability Cloud, and checks that the expected groups and dashboards are
  present.

## Quick Start

Verify the Splunk assets first:

```bash
python3 docs/isovalent/verify_splunk_assets.py
```

Confirm the recurring load generators are still in place:

```bash
LOADGEN_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-broadcast-loadgen.sh

LOADGEN_OPERATOR_K8S_ACTION=status \
zsh scripts/loadgen/deploy-k8s-operator-billing-loadgen.sh
```

Then use `layered-ops-demo.md` as the live script.
