#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_DEPLOY_SCRIPT="${REPO_ROOT}/scripts/frontend/deploy.sh"
SOURCE_UPLOAD_SCRIPT="${REPO_ROOT}/scripts/frontend/upload-sourcemaps.sh"
TEMP_DIR="$(mktemp -d)"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"

  [[ "${haystack}" == *"${needle}"* ]] || fail "expected output to contain: ${needle}"
}

cleanup() {
  rm -rf "${TEMP_DIR}"
}

trap cleanup EXIT

write_git_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1-}" == "-C" && "${3-}" == "rev-parse" && "${4-}" == "--short" && "${5-}" == "HEAD" ]]; then
  printf 'testsha\n'
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

write_npm_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

dist_dir="${PWD}/dist"
mkdir -p "${PWD}/node_modules/.bin" "${dist_dir}"

for file in \
  index.html \
  broadcast.html \
  demo-monkey.html \
  styles.css \
  app.js \
  broadcast.js \
  demo-monkey.js \
  config.js \
  server.js \
  splunk-instrumentation.js \
  app.js.map \
  broadcast.js.map \
  demo-monkey.js.map \
  splunk-instrumentation.js.map; do
  printf 'fixture for %s\n' "${file}" > "${dist_dir}/${file}"
done

cat > "${dist_dir}/build-info.json" <<'JSON'
{
  "appName": "fixture-frontend",
  "appVersion": "fixture-build"
}
JSON
EOF
  chmod +x "${path}"
}

write_kubectl_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1-}" == "-n" ]]; then
  shift 2
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

if [[ "${1-}" == "create" && "${2-}" == "configmap" ]]; then
  cat <<YAML
apiVersion: v1
kind: ConfigMap
metadata:
  name: ${3}
YAML
  exit 0
fi

if [[ "${1-}" == "apply" ]]; then
  cat >/dev/null
  exit 0
fi

if [[ "${1-}" == "rollout" && "${2-}" == "restart" ]]; then
  exit 0
fi

if [[ "${1-}" == "rollout" && "${2-}" == "status" ]]; then
  printf 'deployment "%s" successfully rolled out\n' "${3#deployment/}"
  exit 0
fi

if [[ "${1-}" == "get" && "${2-}" == "service" ]]; then
  cat <<TABLE
NAME                TYPE           CLUSTER-IP    EXTERNAL-IP   PORT(S)
streaming-frontend  LoadBalancer   10.0.0.15     <pending>     80/TCP
TABLE
  exit 0
fi

exit 0
EOF
  chmod +x "${path}"
}

write_splunk_rum_stub() {
  local path="$1"

  cat > "${path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

count_file="${SPLUNK_STUB_COUNT_FILE:?}"
args_file="${SPLUNK_STUB_ARGS_FILE:?}"
attempt=0

if [[ -f "${count_file}" ]]; then
  attempt="$(<"${count_file}")"
fi

attempt=$((attempt + 1))
printf '%s' "${attempt}" > "${count_file}"
printf '%s\n' "$@" > "${args_file}"
printf 'upload complete\n'
EOF
  chmod +x "${path}"
}

make_fixture_repo() {
  local name="$1"
  local root="${TEMP_DIR}/${name}"

  mkdir -p \
    "${root}/scripts/frontend" \
    "${root}/k8s/frontend" \
    "${root}/frontend/node_modules/.bin" \
    "${root}/bin"

  cp "${SOURCE_DEPLOY_SCRIPT}" "${root}/scripts/frontend/deploy.sh"
  cp "${SOURCE_UPLOAD_SCRIPT}" "${root}/scripts/frontend/upload-sourcemaps.sh"

  cat > "${root}/k8s/frontend/deployment.yaml" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: streaming-frontend
  namespace: streaming-service-app
EOF

  cat > "${root}/k8s/frontend/service.yaml" <<'EOF'
apiVersion: v1
kind: Service
metadata:
  name: streaming-frontend
  namespace: streaming-service-app
EOF

  write_git_stub "${root}/bin/git"
  write_npm_stub "${root}/bin/npm"
  write_kubectl_stub "${root}/bin/kubectl"
  write_splunk_rum_stub "${root}/frontend/node_modules/.bin/splunk-rum"

  printf '%s\n' "${root}"
}

run_deploy() {
  local root="$1"
  local output_file="$2"

  set +e
  env \
    PATH="${root}/bin:${PATH}" \
    SPLUNK_STUB_COUNT_FILE="${root}/stub-count.txt" \
    SPLUNK_STUB_ARGS_FILE="${root}/stub-args.txt" \
    zsh "${root}/scripts/frontend/deploy.sh" > "${output_file}" 2>&1
  local status=$?
  set -e

  [[ ${status} -eq 0 ]] || {
    cat "${output_file}" >&2
    fail "frontend deploy exited with status ${status}"
  }
}

test_access_token_default_is_used_for_upload() {
  local root output args

  root="$(make_fixture_repo access-token)"

  cat > "${root}/.env" <<'EOF'
SPLUNK_REALM=us1
SPLUNK_ACCESS_TOKEN=observability-upload-token
SPLUNK_RUM_ACCESS_TOKEN=browser-rum-token
EOF

  run_deploy "${root}" "${root}/output.log"

  [[ "$(<"${root}/stub-count.txt")" == "1" ]] || fail "expected sourcemap upload to run once"

  args="$(tr '\n' ' ' < "${root}/stub-args.txt")"
  assert_contains "${args}" "--token observability-upload-token"
}

test_explicit_upload_override_wins() {
  local root args

  root="$(make_fixture_repo explicit-override)"

  cat > "${root}/.env" <<'EOF'
SPLUNK_REALM=us1
SPLUNK_ACCESS_TOKEN=observability-upload-token
SPLUNK_RUM_ACCESS_TOKEN=browser-rum-token
SPLUNK_SOURCEMAP_UPLOAD_TOKEN=dedicated-upload-token
EOF

  run_deploy "${root}" "${root}/output.log"

  [[ "$(<"${root}/stub-count.txt")" == "1" ]] || fail "expected sourcemap upload to run once"

  args="$(tr '\n' ' ' < "${root}/stub-args.txt")"
  assert_contains "${args}" "--token dedicated-upload-token"
}

test_browser_token_only_warns_and_skips_upload() {
  local root output

  root="$(make_fixture_repo rum-only)"

  cat > "${root}/.env" <<'EOF'
SPLUNK_REALM=us1
SPLUNK_RUM_ACCESS_TOKEN=browser-rum-token
EOF

  run_deploy "${root}" "${root}/output.log"

  output="$(<"${root}/output.log")"
  assert_contains "${output}" "SPLUNK_RUM_ACCESS_TOKEN is set, but sourcemap upload needs SPLUNK_ACCESS_TOKEN or SPLUNK_SOURCEMAP_UPLOAD_TOKEN."
  if [[ -f "${root}/stub-count.txt" ]]; then
    fail "expected sourcemap upload to be skipped when only the browser token is present"
  fi
}

test_access_token_default_is_used_for_upload
test_explicit_upload_override_wins
test_browser_token_only_warns_and_skips_upload
