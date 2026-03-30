#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
MANIFEST_PATH="${REPO_ROOT}/k8s/backend-demo/media-service.yaml"

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

script_path="${temp_dir}/rtsp-restreamer-functions.sh"
stub_dir="${temp_dir}/bin"
mkdir -p "${stub_dir}"

awk '
  /- name: rtsp-restreamer$/ { in_restreamer = 1 }
  in_restreamer && /^              set -eu$/ { capture = 1 }
  capture {
    if ($0 ~ /^              trap /) {
      exit
    }
    sub(/^              /, "")
    print
  }
' "${MANIFEST_PATH}" > "${script_path}"

cat > "${stub_dir}/ffprobe" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '30.2\n'
EOF
chmod +x "${stub_dir}/ffprobe"

cat > "${stub_dir}/ffmpeg" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'ffmpeg invoked unexpectedly\n' >> "${FFMPEG_LOG}"
exit 99
EOF
chmod +x "${stub_dir}/ffmpeg"

source_file="${temp_dir}/current.mp4"
ad_file="${temp_dir}/sponsor-break.mp4"
stall_file="${temp_dir}/sponsor-stall.mp4"
work_dir="${temp_dir}/work"
ffmpeg_log="${temp_dir}/ffmpeg.log"

: > "${source_file}"
: > "${ad_file}"
: > "${stall_file}"
mkdir -p "${work_dir}"

PATH="${stub_dir}:${PATH}"
FFMPEG_LOG="${ffmpeg_log}"

# shellcheck disable=SC1090
. "${script_path}"

build_route_playlist "file" "${source_file}" "true" "true" "false" "false" "${ad_file}" "${stall_file}" "${work_dir}" \
  || fail "short file route should build a stitched playlist without segmenting"

playlist_contents="$(cat "${work_dir}/live-playlist.txt")"
assert_contains "${playlist_contents}" "file '${source_file}'"
assert_contains "${playlist_contents}" "file '${ad_file}'"

[[ ! -f "${ffmpeg_log}" ]] || fail "short file route should bypass ffmpeg segmentation"
[[ ! -d "${work_dir}/segments" ]] || fail "short file route should not create segment output"

printf 'PASS: media restreamer short-file ad playlist regression test\n'
