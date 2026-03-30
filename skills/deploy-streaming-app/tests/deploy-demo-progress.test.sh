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

[[ -n "${archive_path}" ]] || exit 1
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

mode="${DEPLOY_TEST_MODE:-progress}"

if [[ "${1-}" == "-n" ]]; then
  namespace="$2"
  shift 2
else
  namespace=""
fi

command_string="$*"

if [[ "${1-}" == "config" && "${2-}" == "current-context" ]]; then
  printf 'stub-context\n'
  exit 0
fi

if [[ "${1-}" == "create" && "${2-}" == "namespace" && "${4-}" == "--dry-run=client" ]]; then
  cat <<YAML
apiVersion: v1
kind: Namespace
metadata:
  name: ${3}
YAML
  exit 0
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

if [[ "${1-}" == "patch" && "${2-}" == "service" ]]; then
  exit 0
fi

if [[ "${1-}" == "rollout" && "${2-}" == "restart" ]]; then
  exit 0
fi

if [[ "${1-}" == "rollout" && "${2-}" == "status" ]]; then
  if [[ "${3-}" == "deployment/media-service-demo" ]]; then
    printf 'Waiting for deployment "media-service-demo" rollout to finish: 1 old replicas are pending termination...\n'
    sleep 2
  fi
  printf 'deployment "%s" successfully rolled out\n' "${3#deployment/}"
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "pods" ]]; then
  selector=""
  output=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -l)
        selector="$2"
        shift 2
        ;;
      -o)
        output="$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done

  if [[ "${selector}" == "app.kubernetes.io/name=media-service-demo" && "${output}" == "wide" ]]; then
    cat <<TABLE
NAME                                  READY   STATUS     RESTARTS   AGE   IP               NODE
media-service-demo-old                3/3     Running    4          45m   192.168.1.10     ip-192-168-10-10
media-service-demo-new                0/3     Init:1/5   0          90s   192.168.1.11     ip-192-168-10-11
TABLE
    exit 0
  fi

  if [[ "${selector}" == "app.kubernetes.io/name=media-service-demo" && "${output}" == "name" ]]; then
    printf 'pod/media-service-demo-old\n'
    printf 'pod/media-service-demo-new\n'
    exit 0
  fi

  if [[ "${output}" == "wide" ]]; then
    pod_name="${selector#app.kubernetes.io/name=}"
    cat <<TABLE
NAME                 READY   STATUS    RESTARTS   AGE   IP               NODE
${pod_name}-abc123   1/1     Running   0          30s   192.168.1.20     ip-192-168-10-20
TABLE
    exit 0
  fi

  if [[ "${output}" == "name" ]]; then
    pod_name="${selector#app.kubernetes.io/name=}"
    printf 'pod/%s-abc123\n' "${pod_name}"
    exit 0
  fi
fi

if [[ "${1-}" == "get" && "${2-}" == "pod/media-service-demo-new" ]]; then
  if [[ "${mode}" == "no-metrics" ]]; then
    printf 'init:stage-demo-movie:restart=0:wait=:last= container:rtsp-restreamer:ready=false:restart=4:wait=CrashLoopBackOff:last=OOMKilled container:rtsp-server:ready=true:restart=0:wait=:last= '
  else
    printf 'init:stage-demo-movie:restart=0:wait=:last= container:rtsp-restreamer:ready=false:restart=1:wait=:last= container:rtsp-server:ready=true:restart=0:wait=:last= '
  fi
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "pod/"* ]]; then
  printf 'container:app:ready=true:restart=0:wait=:last= '
  exit 0
fi

if [[ "${1-}" == "top" && "${2-}" == "pod" ]]; then
  if [[ "${mode}" == "no-metrics" ]]; then
    exit 1
  fi

  cat <<TABLE
POD                      NAME               CPU(cores)   MEMORY(bytes)
media-service-demo-new   stage-demo-movie   800m         128Mi
TABLE
  exit 0
fi

  if [[ "${1-}" == "logs" && "${2-}" == "media-service-demo-old" ]]; then
    printf '2026-03-29 20:10:00 UTC Finished segment build for legacy-rollout-pod\n'
    exit 0
  fi

  if [[ "${1-}" == "logs" && "${2-}" == "media-service-demo-new" ]]; then
    printf '2026-03-29 20:15:54 UTC Finished segment build for 010-big-buck-bunny\n'
    exit 0
  fi

  if [[ "${1-}" == "exec" && "${2-}" == "media-service-demo-new" ]]; then
    if [[ "${command_string}" == *' -c app -- '* ]]; then
      printf '16\n'
      exit 0
    fi
    exit 1
  fi

  if [[ "${1-}" == "exec" && "${2-}" == "media-service-demo-old" ]]; then
    if [[ "${command_string}" == *' -c app -- '* ]]; then
      printf '4\n'
      exit 0
    fi
    exit 1
  fi

if [[ "${1-}" == "get" && "${2-}" == "endpoints" ]]; then
  cat <<TABLE
NAME                      ENDPOINTS             AGE
media-service-demo        192.168.1.11:8080     2h
media-service-demo-rtsp   192.168.1.11:8554     2h
TABLE
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
  local mode="$1"
  local output_file="$2"
  local temp_dir="$3"
  local stub_dir="${temp_dir}/bin"

  mkdir -p "${stub_dir}"
  write_git_stub "${stub_dir}/git"
  write_tar_stub "${stub_dir}/tar"
  write_npm_stub "${stub_dir}/npm"
  write_kubectl_stub "${stub_dir}/kubectl"
  : > "${temp_dir}/test.env"

  set +e
  env \
    PATH="${stub_dir}:${PATH}" \
    ENV_FILE="${temp_dir}/test.env" \
    DEMO_AUTH_PASSWORD='demo-password' \
    DEMO_AUTH_SECRET='demo-secret' \
    DEPLOY_TEST_MODE="${mode}" \
    FRONTEND_SERVICE_TYPE='ClusterIP' \
    RTSP_SERVICE_TYPE='ClusterIP' \
    ROLLOUT_SNAPSHOT_INTERVAL_SECONDS='1' \
    bash "${TARGET_SCRIPT}" --platform kubernetes --namespace skill-test \
    >"${output_file}" 2>&1
  local status=$?
  set -e

  [[ ${status} -eq 0 ]] || {
    cat "${output_file}" >&2
    fail "deploy-demo.sh exited with status ${status} in mode ${mode}"
  }
}

test_progress_snapshot_includes_media_details() {
  local temp_dir
  local output

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  run_deploy progress "${temp_dir}/output.log" "${temp_dir}"
  output="$(cat "${temp_dir}/output.log")"

  assert_contains "${output}" 'Rollout snapshot for deployment/media-service-demo'
  assert_contains "${output}" 'media init: 2026-03-29 20:15:54 UTC Finished segment build for 010-big-buck-bunny'
  assert_contains "${output}" 'media init segment files: 16'
  assert_contains "${output}" 'media endpoints:'
  assert_contains "${output}" 'metrics:'
}

test_progress_snapshot_surfaces_restart_reasons_without_metrics() {
  local temp_dir
  local output

  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' RETURN
  run_deploy no-metrics "${temp_dir}/output.log" "${temp_dir}"
  output="$(cat "${temp_dir}/output.log")"

  assert_contains "${output}" 'Rollout snapshot for deployment/media-service-demo'
  assert_contains "${output}" 'CrashLoopBackOff'
  assert_contains "${output}" 'OOMKilled'
  if [[ "${output}" == *'metrics:'* ]]; then
    fail 'expected no metrics section when kubectl top is unavailable'
  fi
}

test_progress_snapshot_includes_media_details
test_progress_snapshot_surfaces_restart_reasons_without_metrics

printf 'PASS: deploy-demo progress snapshots\n'
