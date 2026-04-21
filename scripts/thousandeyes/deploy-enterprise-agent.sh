#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
OUTPUT_PATH=""
APPLY_MANIFEST=false
WAIT_FOR_ROLLOUT=false
RESOLVE_AGENT_ID=false

usage() {
  cat <<'EOF'
Usage: deploy-enterprise-agent.sh [options]

Render or deploy a ThousandEyes Enterprise Agent into Kubernetes.

Options:
  --namespace <namespace>                Default: te-demo
  --name <deployment-name>               Default: thousandeyes-private
  --hostname <hostname>                  Default: te-agent-streaming-private
  --secret-name <secret-name>            Default: te-creds
  --image <image>                        Default: thousandeyes/enterprise-agent:latest
  --memory-request <value>               Default: 2000Mi
  --memory-limit <value>                 Default: 3584Mi
  --output <path>                        Write the rendered manifest to a file
  --apply                                Apply the namespace, secret, and deployment
  --wait                                 Wait for rollout completion after apply
  --resolve-agent-id                     Poll the ThousandEyes API for the deployed hostname
  --env-file <path>                      Repo-style env file to load
  --help

Environment variables:
  TEAGENT_ACCOUNT_TOKEN                  Raw ThousandEyes Enterprise Agent account token
  THOUSANDEYES_BEARER_TOKEN              ThousandEyes bearer token for agent-id lookup
  THOUSANDEYES_ACCOUNT_GROUP_ID          Optional account-group scope for API lookups
EOF
}

log() {
  printf '[deploy-te-agent] %s\n' "$*"
}

fail() {
  printf '[deploy-te-agent] ERROR: %s\n' "$*" >&2
  exit 1
}

load_env_file() {
  local env_file="$1"
  local line normalized key value

  [[ -f "${env_file}" ]] || return 0

  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"

    [[ -z "${line}" || "${line}" == \#* ]] && continue

    normalized="${line}"
    [[ "${normalized}" == export\ * ]] && normalized="${normalized#export }"
    [[ "${normalized}" == *=* ]] || continue

    key="${normalized%%=*}"
    value="${normalized#*=}"

    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"

    if [[ "${key}" != [A-Za-z_][A-Za-z0-9_]* ]]; then
      continue
    fi

    if [[ -n "${!key+x}" ]]; then
      continue
    fi

    if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi

    export "${key}=${value}"
  done < "${env_file}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace)
      NAMESPACE="${2:?missing value for --namespace}"
      shift 2
      ;;
    --name)
      DEPLOYMENT_NAME="${2:?missing value for --name}"
      shift 2
      ;;
    --hostname)
      AGENT_HOSTNAME="${2:?missing value for --hostname}"
      shift 2
      ;;
    --secret-name)
      SECRET_NAME="${2:?missing value for --secret-name}"
      shift 2
      ;;
    --image)
      IMAGE="${2:?missing value for --image}"
      shift 2
      ;;
    --memory-request)
      MEMORY_REQUEST="${2:?missing value for --memory-request}"
      shift 2
      ;;
    --memory-limit)
      MEMORY_LIMIT="${2:?missing value for --memory-limit}"
      shift 2
      ;;
    --output)
      OUTPUT_PATH="${2:?missing value for --output}"
      shift 2
      ;;
    --apply)
      APPLY_MANIFEST=true
      shift
      ;;
    --wait)
      WAIT_FOR_ROLLOUT=true
      shift
      ;;
    --resolve-agent-id)
      RESOLVE_AGENT_ID=true
      shift
      ;;
    --env-file)
      ENV_FILE="${2:?missing value for --env-file}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

load_env_file "${ENV_FILE}"

NAMESPACE="${NAMESPACE:-te-demo}"
DEPLOYMENT_NAME="${DEPLOYMENT_NAME:-thousandeyes-private}"
AGENT_HOSTNAME="${AGENT_HOSTNAME:-te-agent-streaming-private}"
SECRET_NAME="${SECRET_NAME:-te-creds}"
IMAGE="${IMAGE:-thousandeyes/enterprise-agent:latest}"
MEMORY_REQUEST="${MEMORY_REQUEST:-2000Mi}"
MEMORY_LIMIT="${MEMORY_LIMIT:-3584Mi}"
ROLLOUT_TIMEOUT_SECONDS="${ROLLOUT_TIMEOUT_SECONDS:-300}"
REGISTRATION_TIMEOUT_SECONDS="${REGISTRATION_TIMEOUT_SECONDS:-600}"
THOUSANDEYES_API_BASE_URL="${THOUSANDEYES_API_BASE_URL:-https://api.thousandeyes.com/v7}"

render_manifest() {
  cat <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  namespace: ${NAMESPACE}
  name: ${DEPLOYMENT_NAME}
  labels:
    app: ${DEPLOYMENT_NAME}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ${DEPLOYMENT_NAME}
  template:
    metadata:
      labels:
        app: ${DEPLOYMENT_NAME}
    spec:
      hostname: ${AGENT_HOSTNAME}
      containers:
        - name: thousandeyes
          image: ${IMAGE}
          imagePullPolicy: Always
          command:
            - /sbin/my_init
          securityContext:
            capabilities:
              add:
                - NET_ADMIN
                - SYS_ADMIN
          env:
            - name: TEAGENT_ACCOUNT_TOKEN
              valueFrom:
                secretKeyRef:
                  name: ${SECRET_NAME}
                  key: TEAGENT_ACCOUNT_TOKEN
            - name: TEAGENT_INET
              value: "4"
          resources:
            requests:
              memory: ${MEMORY_REQUEST}
            limits:
              memory: ${MEMORY_LIMIT}
EOF
}

ensure_namespace() {
  kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

ensure_secret() {
  if [[ -n "${TEAGENT_ACCOUNT_TOKEN:-}" ]]; then
    kubectl -n "${NAMESPACE}" create secret generic "${SECRET_NAME}" \
      --from-literal=TEAGENT_ACCOUNT_TOKEN="${TEAGENT_ACCOUNT_TOKEN}" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    return
  fi

  kubectl -n "${NAMESPACE}" get secret "${SECRET_NAME}" >/dev/null 2>&1 || \
    fail "set TEAGENT_ACCOUNT_TOKEN or create secret ${SECRET_NAME} in namespace ${NAMESPACE}"
}

wait_for_rollout() {
  kubectl -n "${NAMESPACE}" rollout status "deployment/${DEPLOYMENT_NAME}" --timeout="${ROLLOUT_TIMEOUT_SECONDS}s" >&2
}

with_account_group() {
  local path="$1"
  if [[ -n "${THOUSANDEYES_ACCOUNT_GROUP_ID:-}" ]]; then
    printf '%s?aid=%s' "${path}" "${THOUSANDEYES_ACCOUNT_GROUP_ID}"
  else
    printf '%s' "${path}"
  fi
}

extract_agent_id() {
  local hostname="$1"

  if command -v jq >/dev/null 2>&1; then
    jq -r --arg hostname "${hostname}" '
      [
        .. | objects | select(
          (.hostname? // .agentName? // .agentNameRaw? // .name? // "") == $hostname
        ) | .agentId?
      ] | map(select(. != null)) | first // empty
    '
    return
  fi

  python3 - "${hostname}" <<'PY'
import json
import sys

target = sys.argv[1]
payload = json.load(sys.stdin)

def walk(node):
    if isinstance(node, dict):
        name = node.get("hostname") or node.get("agentName") or node.get("agentNameRaw") or node.get("name")
        agent_id = node.get("agentId")
        if name == target and agent_id is not None:
            print(agent_id)
            raise SystemExit(0)
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for item in node:
            walk(item)

walk(payload)
PY
}

resolve_agent_id() {
  local elapsed=0
  local response=""
  local agent_id=""

  [[ -n "${THOUSANDEYES_BEARER_TOKEN:-${THOUSANDEYES_TOKEN:-}}" ]] || \
    fail "THOUSANDEYES_BEARER_TOKEN must be set for --resolve-agent-id"

  while (( elapsed < REGISTRATION_TIMEOUT_SECONDS )); do
    response="$(
      curl -fsSL \
        -H "Authorization: Bearer ${THOUSANDEYES_BEARER_TOKEN:-${THOUSANDEYES_TOKEN:-}}" \
        -H 'Accept: application/json' \
        "${THOUSANDEYES_API_BASE_URL%/}$(with_account_group "/agents")"
    )"
    agent_id="$(printf '%s' "${response}" | extract_agent_id "${AGENT_HOSTNAME}" || true)"
    if [[ -n "${agent_id}" ]]; then
      printf '%s\n' "${agent_id}"
      return 0
    fi

    sleep 15
    elapsed=$((elapsed + 15))
  done

  fail "timed out waiting for Enterprise Agent hostname ${AGENT_HOSTNAME} to register in ThousandEyes"
}

manifest="$(render_manifest)"

if [[ -n "${OUTPUT_PATH}" ]]; then
  mkdir -p "$(dirname "${OUTPUT_PATH}")"
  printf '%s\n' "${manifest}" > "${OUTPUT_PATH}"
  log "Wrote manifest to ${OUTPUT_PATH}"
fi

if [[ "${APPLY_MANIFEST}" == "true" ]]; then
  ensure_namespace
  ensure_secret
  printf '%s\n' "${manifest}" | kubectl apply -f - >/dev/null

  if [[ "${WAIT_FOR_ROLLOUT}" == "true" ]]; then
    wait_for_rollout
  fi
fi

if [[ "${RESOLVE_AGENT_ID}" == "true" ]]; then
  agent_id="$(resolve_agent_id)"
  printf 'TE_MEDIA_SOURCE_AGENT_IDS=%s\n' "${agent_id}"
elif [[ -z "${OUTPUT_PATH}" && "${APPLY_MANIFEST}" != "true" ]]; then
  printf '%s\n' "${manifest}"
fi
