#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"

log() {
  printf '%s %s\n' "${LOG_PREFIX}" "$*"
}

warn() {
  printf '%s WARN: %s\n' "${LOG_PREFIX}" "$*" >&2
}

fail() {
  printf '%s ERROR: %s\n' "${LOG_PREFIX}" "$*" >&2
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

    if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi

    export "${key}=${value}"
  done < "${env_file}"
}

emit_prefixed() {
  local level="$1"
  local message="$2"
  local line

  [[ -n "${message}" ]] || return 0

  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    if [[ "${level}" == "warn" ]]; then
      warn "${line}"
    else
      log "${line}"
    fi
  done <<< "${message}"
}

require_non_negative_integer() {
  local value="$1"
  local name="$2"

  [[ "${value}" =~ ^[0-9]+$ ]] || fail "${name} must be a non-negative integer"
}

load_build_info_defaults() {
  local build_info_file="${DIST_DIR}/build-info.json"
  local build_info_output=""
  local build_info_app_name=""
  local build_info_app_version=""

  [[ -f "${build_info_file}" ]] || return 0

  build_info_output="$(
    node -e '
const fs = require("node:fs");
const info = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(`${info.appName ?? ""}\n${info.appVersion ?? ""}\n`);
' "${build_info_file}"
  )"

  if [[ "${build_info_output}" == *$'\n'* ]]; then
    build_info_app_name="${build_info_output%%$'\n'*}"
    build_info_app_version="${build_info_output#*$'\n'}"
    build_info_app_version="${build_info_app_version%%$'\n'*}"
  else
    build_info_app_name="${build_info_output}"
  fi

  if [[ -z "${SPLUNK_RUM_APP_NAME}" && -n "${build_info_app_name}" ]]; then
    SPLUNK_RUM_APP_NAME="${build_info_app_name}"
  fi
  if [[ -z "${APP_VERSION}" && -n "${build_info_app_version}" ]]; then
    APP_VERSION="${build_info_app_version}"
  fi
}

upload_sourcemaps() {
  local attempt=1
  local delay_seconds="${SPLUNK_SOURCEMAP_UPLOAD_INITIAL_DELAY_SECONDS}"
  local output=""
  local status=0

  while (( attempt <= SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS )); do
    log "Uploading frontend sourcemaps to Splunk RUM (attempt ${attempt}/${SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS})"

    if output="$(
      APP_VERSION="${APP_VERSION}" "${SPLUNK_RUM_CLI}" sourcemaps upload \
        --app-name "${SPLUNK_RUM_APP_NAME}" \
        --app-version "${APP_VERSION}" \
        --path "${DIST_DIR}" \
        --realm "${SPLUNK_REALM}" \
        --token "${SPLUNK_SOURCEMAP_UPLOAD_TOKEN}" \
        2>&1
    )"; then
      emit_prefixed log "${output}"
      if (( attempt > 1 )); then
        log "Splunk sourcemap upload succeeded on attempt ${attempt}/${SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS}."
      fi
      return 0
    else
      status=$?
    fi

    warn "Splunk sourcemap upload attempt ${attempt}/${SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS} failed with exit code ${status}."
    emit_prefixed warn "${output}"

    if (( attempt == SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS )); then
      break
    fi

    warn "Retrying frontend sourcemap upload in ${delay_seconds}s."
    sleep "${delay_seconds}"
    attempt=$((attempt + 1))

    if (( delay_seconds < SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS )); then
      delay_seconds=$((delay_seconds * 2))
      if (( delay_seconds > SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS )); then
        delay_seconds="${SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS}"
      fi
    fi
  done

  warn "Splunk sourcemap upload failed after ${SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS} attempts."
  warn "Retry later by rerunning scripts/frontend/upload-sourcemaps.sh after restoring the Splunk sourcemap env vars or repo-root .env file."
  return 1
}

load_env_file "${ENV_FILE}"

FRONTEND_DIR="${FRONTEND_DIR:-${ROOT_DIR}/frontend}"
DIST_DIR="${DIST_DIR:-${FRONTEND_DIR}/dist}"
APP_VERSION="${APP_VERSION:-}"
SPLUNK_RUM_APP_NAME="${SPLUNK_RUM_APP_NAME:-}"
SPLUNK_REALM="${SPLUNK_REALM:-}"
SPLUNK_SOURCEMAP_UPLOAD_TOKEN="${SPLUNK_SOURCEMAP_UPLOAD_TOKEN:-${SPLUNK_ACCESS_TOKEN:-}}"
SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS="${SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS:-4}"
SPLUNK_SOURCEMAP_UPLOAD_INITIAL_DELAY_SECONDS="${SPLUNK_SOURCEMAP_UPLOAD_INITIAL_DELAY_SECONDS:-5}"
SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS="${SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS:-30}"
LOG_PREFIX="${LOG_PREFIX:-[frontend-sourcemaps]}"
SPLUNK_RUM_CLI="${SPLUNK_RUM_CLI:-${FRONTEND_DIR}/node_modules/.bin/splunk-rum}"

[[ -d "${FRONTEND_DIR}" ]] || fail "Frontend directory not found: ${FRONTEND_DIR}"
[[ -d "${DIST_DIR}" ]] || fail "Build output directory not found: ${DIST_DIR}"
[[ -n "${SPLUNK_REALM}" ]] || fail "SPLUNK_REALM must be set"
if [[ -z "${SPLUNK_SOURCEMAP_UPLOAD_TOKEN}" && -n "${SPLUNK_RUM_ACCESS_TOKEN:-}" ]]; then
  fail "SPLUNK_RUM_ACCESS_TOKEN is for Browser RUM only. Set SPLUNK_ACCESS_TOKEN or SPLUNK_SOURCEMAP_UPLOAD_TOKEN for sourcemap upload."
fi
[[ -n "${SPLUNK_SOURCEMAP_UPLOAD_TOKEN}" ]] || fail "SPLUNK_SOURCEMAP_UPLOAD_TOKEN or SPLUNK_ACCESS_TOKEN must be set"
[[ -x "${SPLUNK_RUM_CLI}" ]] || fail "Splunk RUM CLI not found or not executable: ${SPLUNK_RUM_CLI}"

require_non_negative_integer "${SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS}" "SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS"
if (( SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS < 1 )); then
  fail "SPLUNK_SOURCEMAP_UPLOAD_MAX_ATTEMPTS must be at least 1"
fi
require_non_negative_integer "${SPLUNK_SOURCEMAP_UPLOAD_INITIAL_DELAY_SECONDS}" "SPLUNK_SOURCEMAP_UPLOAD_INITIAL_DELAY_SECONDS"
require_non_negative_integer "${SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS}" "SPLUNK_SOURCEMAP_UPLOAD_MAX_DELAY_SECONDS"

load_build_info_defaults

if [[ -z "${SPLUNK_RUM_APP_NAME}" ]]; then
  SPLUNK_RUM_APP_NAME="streaming-app-frontend"
fi
[[ -n "${APP_VERSION}" ]] || fail "APP_VERSION could not be determined; build the frontend first or set APP_VERSION explicitly"

upload_sourcemaps
