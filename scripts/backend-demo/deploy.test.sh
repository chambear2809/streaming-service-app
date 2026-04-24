#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_DEPLOY_SCRIPT="${REPO_ROOT}/scripts/backend-demo/deploy.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

temp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${temp_dir}"
}
trap cleanup EXIT

write_tar_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

archive_path=""
prev=""
for arg in "$@"; do
  if [[ "${prev}" == "-czf" ]]; then
    archive_path="${arg}"
    break
  fi
  prev="${arg}"
done

mkdir -p "$(dirname "${archive_path}")"
: > "${archive_path}"
EOF
  chmod +x "${path}"
}

write_kubectl_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1-}" == "create" && "${2-}" == "namespace" ]]; then
  cat <<YAML
apiVersion: v1
kind: Namespace
metadata:
  name: ${3}
YAML
  exit 0
fi

if [[ "${1-}" == "-n" ]]; then
  shift 2
fi

if [[ "${1-}" == "create" && ( "${2-}" == "configmap" || "${2-}" == "secret" ) ]]; then
  cat <<YAML
apiVersion: v1
kind: ${2}
metadata:
  name: ${3}
YAML
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "secret" && "${4-}" == "-o" ]]; then
  case "${5-}" in
    jsonpath={.data.DEMO_AUTH_SECRET}|jsonpath={.data.DEMO_AUTH_PASSWORD})
      printf 'ZGVtby12YWx1ZQ==\n'
      exit 0
      ;;
  esac
fi

if [[ "${1-}" == "apply" ]]; then
  cat >> "${KUBECTL_APPLY_LOG_FILE:?}"
  exit 0
fi

if [[ "${1-}" == "rollout" && ( "${2-}" == "restart" || "${2-}" == "status" ) ]]; then
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "svc" ]]; then
  printf 'NAME TYPE\n'
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

fixture_root="${temp_dir}/repo"
mkdir -p \
  "${fixture_root}/scripts/backend-demo" \
  "${fixture_root}/scripts/frontend" \
  "${fixture_root}/k8s/backend-demo" \
  "${fixture_root}/bin"

cp "${SOURCE_DEPLOY_SCRIPT}" "${fixture_root}/scripts/backend-demo/deploy.sh"

cat > "${fixture_root}/scripts/frontend/deploy.sh" <<'EOF'
#!/usr/bin/env zsh
set -euo pipefail
exit 0
EOF
chmod +x "${fixture_root}/scripts/frontend/deploy.sh"

printf '<project />\n' > "${fixture_root}/pom.xml"

for manifest in \
  postgres \
  content-service \
  media-service \
  user-service \
  billing-service \
  ad-service \
  customer-service \
  payment-service \
  subscription-service \
  order-service; do
  cat > "${fixture_root}/k8s/backend-demo/${manifest}.yaml" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  namespace: streaming-service-app
spec:
  template:
    spec:
      containers:
        - name: app
          env:
            - name: OTEL_DEPLOYMENT_ENVIRONMENT
              value: 'streaming-app'
            - name: OTEL_RESOURCE_ATTRIBUTES
              value: service.name=fixture,deployment.environment=$(OTEL_DEPLOYMENT_ENVIRONMENT)
EOF
done

write_tar_stub "${fixture_root}/bin/tar"
write_kubectl_stub "${fixture_root}/bin/kubectl"

cat > "${fixture_root}/.env" <<'EOF'
SPLUNK_DEPLOYMENT_ENVIRONMENT=from-dotenv
EOF

apply_log="${fixture_root}/apply.log"
: > "${apply_log}"

env \
  PATH="${fixture_root}/bin:${PATH}" \
  ENV_FILE="${fixture_root}/.env" \
  KUBECTL_APPLY_LOG_FILE="${apply_log}" \
  DEMO_AUTH_SECRET='demo-secret' \
  DEMO_AUTH_PASSWORD='demo-password' \
  zsh "${fixture_root}/scripts/backend-demo/deploy.sh" \
  >/dev/null 2>&1 || fail "backend-demo deploy flow failed"

applied="$(<"${apply_log}")"
assert_contains "${applied}" "name: OTEL_DEPLOYMENT_ENVIRONMENT"
assert_contains "${applied}" "value: 'from-dotenv'"
assert_contains "${applied}" 'deployment.environment=$(OTEL_DEPLOYMENT_ENVIRONMENT)'

printf 'PASS: backend-demo deployment environment regression test\n'
