#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
TARGET_SCRIPT="${REPO_ROOT}/skills/deploy-streaming-app/scripts/deploy-demo.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

write_git_stub() {
  local path="$1"

  cat > "${path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [[ "\${1-}" == "-C" && "\${3-}" == "rev-parse" && "\${4-}" == "--show-toplevel" ]]; then
  printf '%s\n' "${REPO_ROOT}"
  exit 0
fi

if [[ "\${1-}" == "-C" && "\${3-}" == "rev-parse" && "\${4-}" == "--short" && "\${5-}" == "HEAD" ]]; then
  printf 'testsha\n'
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

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

write_npm_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
  chmod +x "${path}"
}

write_kubectl_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${DEPLOY_TEST_PATCH_LOG:-}" && "${1-}" == "-n" && "${4-}" == "service" ]]; then
  printf '%s\n' "$*" >> "${DEPLOY_TEST_PATCH_LOG}"
fi

if [[ "${1-}" == "config" && "${2-}" == "current-context" ]]; then
  printf 'stub-context\n'
  exit 0
fi

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
    jsonpath={.data.DEMO_AUTH_SECRET})
      printf 'ZGVtby1zZWNyZXQ=\n'
      exit 0
      ;;
    jsonpath={.data.DEMO_AUTH_PASSWORD})
      printf 'ZGVtby1wYXNzd29yZA==\n'
      exit 0
      ;;
  esac
fi

if [[ "${1-}" == "apply" ]]; then
  cat >/dev/null
  exit 0
fi

if [[ "${1-}" == "patch" ]]; then
  exit 0
fi

if [[ "${1-}" == "rollout" && ( "${2-}" == "restart" || "${2-}" == "status" ) ]]; then
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "service" && "${4-}" == "-o" ]]; then
  if [[ "${5-}" == "jsonpath={.status.loadBalancer.ingress[0].hostname}" ]]; then
    printf 'lb.example.internal\n'
    exit 0
  fi
  if [[ "${5-}" == "jsonpath={.status.loadBalancer.ingress[0].ip}" ]]; then
    printf '\n'
    exit 0
  fi
fi

if [[ "${1-}" == "get" && "${2-}" == "service" ]]; then
  cat <<TABLE
NAME                      TYPE           CLUSTER-IP      EXTERNAL-IP         PORT(S)
ad-service-demo           ClusterIP      10.0.0.10       <none>              8080/TCP
billing-service           ClusterIP      10.0.0.11       <none>              8080/TCP
content-service-demo      ClusterIP      10.0.0.12       <none>              8080/TCP
media-service-demo        ClusterIP      10.0.0.13       <none>              8080/TCP
media-service-demo-rtsp   LoadBalancer   10.0.0.14       lb.example.internal 8554/TCP
streaming-frontend        LoadBalancer   10.0.0.15       lb.example.internal 80/TCP
user-service-demo         ClusterIP      10.0.0.16       <none>              8080/TCP
TABLE
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

temp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${temp_dir}"
}
trap cleanup EXIT

stub_dir="${temp_dir}/bin"
mkdir -p "${stub_dir}"
write_git_stub "${stub_dir}/git"
write_tar_stub "${stub_dir}/tar"
write_npm_stub "${stub_dir}/npm"
write_kubectl_stub "${stub_dir}/kubectl"

patch_log="${temp_dir}/patch.log"
: > "${patch_log}"
: > "${temp_dir}/test.env"

env \
  PATH="${stub_dir}:${PATH}" \
  ENV_FILE="${temp_dir}/test.env" \
  DEMO_AUTH_PASSWORD='demo-password' \
  DEMO_AUTH_SECRET='demo-secret' \
  DEPLOY_TEST_PATCH_LOG="${patch_log}" \
  SPLUNK_OTEL_MODE='skip' \
  bash "${TARGET_SCRIPT}" \
    --platform kubernetes \
    --namespace skill-test \
    --rollout-snapshot-interval 0 \
    --external-url-timeout 0 \
    --frontend-service-type LoadBalancer \
    --frontend-load-balancer-scheme internal \
    --frontend-aws-load-balancer-type nlb \
    --rtsp-service-type LoadBalancer \
    --rtsp-load-balancer-scheme internal \
    --rtsp-aws-load-balancer-type nlb \
    --public-rtsp-url rtsp://router.example.com:8554/live \
    >/dev/null 2>&1 || fail "deploy-demo.sh failed"

patches="$(cat "${patch_log}")"
assert_contains "${patches}" 'patch service media-service-demo-rtsp --type merge -p {"spec":{"type":"LoadBalancer"}}'
assert_contains "${patches}" 'patch service media-service-demo-rtsp --type merge -p {"metadata":{"annotations":{"service.beta.kubernetes.io/aws-load-balancer-scheme":"internal"}}}'
assert_contains "${patches}" 'patch service media-service-demo-rtsp --type merge -p {"metadata":{"annotations":{"service.beta.kubernetes.io/aws-load-balancer-type":"nlb"}}}'
assert_contains "${patches}" 'patch service streaming-frontend --type merge -p {"spec":{"type":"LoadBalancer"}}'
assert_contains "${patches}" 'patch service streaming-frontend --type merge -p {"metadata":{"annotations":{"service.beta.kubernetes.io/aws-load-balancer-scheme":"internal"}}}'
assert_contains "${patches}" 'patch service streaming-frontend --type merge -p {"metadata":{"annotations":{"service.beta.kubernetes.io/aws-load-balancer-type":"nlb"}}}'

printf 'PASS: deploy-demo load balancer scheme patch regression test\n'
