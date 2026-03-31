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

write_helper_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf 'ARGS:%s\n' "$*" >> "${DEPLOY_HELPER_LOG}"
printf 'REALM:%s\n' "${SPLUNK_REALM:-}" >> "${DEPLOY_HELPER_LOG}"
printf 'TOKEN:%s\n' "${SPLUNK_ACCESS_TOKEN:-}" >> "${DEPLOY_HELPER_LOG}"
printf 'ENV:%s\n' "${SPLUNK_DEPLOYMENT_ENVIRONMENT:-}" >> "${DEPLOY_HELPER_LOG}"
printf 'CHART:%s\n' "${SPLUNK_OTEL_HELM_CHART_VERSION:-}" >> "${DEPLOY_HELPER_LOG}"
exit 0
EOF
  chmod +x "${path}"
}

write_kubectl_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

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

if [[ "${1-}" == "apply" ]]; then
  cat >/dev/null
  exit 0
fi

if [[ "${1-}" == "patch" ]]; then
  exit 0
fi

if [[ "${1-}" == "rollout" && "${2-}" == "restart" ]]; then
  exit 0
fi

if [[ "${1-}" == "rollout" && "${2-}" == "status" ]]; then
  printf '%s successfully rolled out\n' "${3-}"
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "service" ]]; then
  cat <<TABLE
NAME                      TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)
ad-service-demo           ClusterIP   10.0.0.10       <none>        8080/TCP
billing-service           ClusterIP   10.0.0.11       <none>        8080/TCP
content-service-demo      ClusterIP   10.0.0.12       <none>        8080/TCP
media-service-demo        ClusterIP   10.0.0.13       <none>        8080/TCP
media-service-demo-rtsp   ClusterIP   10.0.0.14       <none>        8554/TCP
streaming-frontend        ClusterIP   10.0.0.15       <none>        80/TCP
user-service-demo         ClusterIP   10.0.0.16       <none>        8080/TCP
TABLE
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

run_deploy() {
  local extra_args="$1"
  local temp_dir="$2"
  local output_file="$3"

  local stub_dir="${temp_dir}/bin"
  mkdir -p "${stub_dir}"
  write_git_stub "${stub_dir}/git"
  write_tar_stub "${stub_dir}/tar"
  write_npm_stub "${stub_dir}/npm"
  write_kubectl_stub "${stub_dir}/kubectl"
  write_helper_stub "${temp_dir}/helper.sh"
  : > "${temp_dir}/helper.log"
  : > "${temp_dir}/test.env"

  set +e
  env \
    PATH="${stub_dir}:${PATH}" \
    ENV_FILE="${temp_dir}/test.env" \
    DEMO_AUTH_PASSWORD='demo-password' \
    DEMO_AUTH_SECRET='demo-secret' \
    FRONTEND_SERVICE_TYPE='ClusterIP' \
    RTSP_SERVICE_TYPE='ClusterIP' \
    ROLLOUT_SNAPSHOT_INTERVAL_SECONDS='0' \
    DEPLOY_HELPER_LOG="${temp_dir}/helper.log" \
    SPLUNK_OTEL_HELPER_SCRIPT="${temp_dir}/helper.sh" \
    SPLUNK_REALM='us1' \
    SPLUNK_ACCESS_TOKEN='collector-token' \
    SPLUNK_DEPLOYMENT_ENVIRONMENT='streaming-app' \
    SPLUNK_OTEL_HELM_CHART_VERSION='0.148.0' \
    bash "${TARGET_SCRIPT}" --platform kubernetes --namespace skill-test ${extra_args} \
    >"${output_file}" 2>&1
  local status=$?
  set -e

  [[ ${status} -eq 0 ]] || {
    cat "${output_file}" >&2
    fail "deploy-demo.sh exited with status ${status}"
  }
}

test_skip_mode_does_not_invoke_helper() {
  local temp_dir helper_log

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  run_deploy "" "${temp_dir}" "${temp_dir}/output.log"
  helper_log="$(cat "${temp_dir}/helper.log")"
  [[ -z "${helper_log}" ]] || fail "skip mode unexpectedly invoked collector helper"
}

test_reuse_mode_invokes_helper_with_expected_args() {
  local temp_dir helper_log

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  run_deploy "--splunk-otel-mode reuse --splunk-otel-cluster-name explicit-cluster" "${temp_dir}" "${temp_dir}/output.log"
  helper_log="$(cat "${temp_dir}/helper.log")"

  assert_contains "${helper_log}" 'ARGS:--mode reuse'
  assert_contains "${helper_log}" '--platform kubernetes'
  assert_contains "${helper_log}" '--cli kubectl'
  assert_contains "${helper_log}" '--timeout 900s'
  assert_contains "${helper_log}" '--env-file '
  assert_contains "${helper_log}" '--cluster-name explicit-cluster'
  assert_contains "${helper_log}" 'REALM:us1'
  assert_contains "${helper_log}" 'TOKEN:collector-token'
  assert_contains "${helper_log}" 'ENV:streaming-app'
  assert_contains "${helper_log}" 'CHART:0.148.0'
}

test_skip_mode_does_not_invoke_helper
test_reuse_mode_invokes_helper_with_expected_args

printf 'PASS: deploy-demo Splunk OTel forwarding\n'
