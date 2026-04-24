#!/usr/bin/env bash

set -euo pipefail

TARGET_SERVICE="${SPLUNK_OTEL_AGENT_SERVICE_NAME:-splunk-otel-collector-agent}"

awk -v target_service="${TARGET_SERVICE}" '
function reset_doc() {
  kind = ""
  in_metadata = 0
  patch_service = 0
  in_spec = 0
}

BEGIN {
  reset_doc()
}

/^---[[:space:]]*$/ {
  reset_doc()
  print
  next
}

{
  if ($0 ~ /^kind:[[:space:]]+Service[[:space:]]*$/) {
    kind = "Service"
  } else if ($0 ~ /^kind:[[:space:]]+/) {
    kind = ""
  }

  if (kind == "Service" && $0 ~ /^metadata:[[:space:]]*$/) {
    in_metadata = 1
  } else if (in_metadata && $0 ~ /^[^[:space:]]/) {
    in_metadata = 0
  }

  if (kind == "Service" && in_metadata && $0 ~ /^  name:[[:space:]]+/) {
    service_name = $0
    sub(/^  name:[[:space:]]+/, "", service_name)
    patch_service = (service_name == target_service)
  }

  if (kind == "Service" && patch_service && $0 ~ /^spec:[[:space:]]*$/) {
    print
    print "  internalTrafficPolicy: Cluster"
    in_spec = 1
    next
  }

  if (kind == "Service" && patch_service && in_spec && $0 ~ /^  internalTrafficPolicy:[[:space:]]+/) {
    next
  }

  if (in_spec && $0 ~ /^[^[:space:]]/) {
    in_spec = 0
  }

  print
}
' 
