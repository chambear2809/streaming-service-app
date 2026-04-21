#!/bin/sh

set -eu

THOUSANDEYES_API_BASE_URL="${THOUSANDEYES_API_BASE_URL:-https://api.thousandeyes.com/v7}"
THOUSANDEYES_BEARER_TOKEN="${THOUSANDEYES_BEARER_TOKEN:-${THOUSANDEYES_TOKEN:-}}"
TE_DEMO_MONKEY_FRONTEND_BASE_URL="${TE_DEMO_MONKEY_FRONTEND_BASE_URL:-http://streaming-frontend.streaming-service-app.svc.cluster.local}"
TE_TRACE_MAP_TEST_URL="${TE_TRACE_MAP_TEST_URL:-${TE_DEMO_MONKEY_FRONTEND_BASE_URL%/}/api/v1/demo/public/trace-map}"
TE_BROADCAST_TEST_URL="${TE_BROADCAST_TEST_URL:-${TE_DEMO_MONKEY_FRONTEND_BASE_URL%/}/api/v1/demo/public/broadcast/live/index.m3u8}"

json_escape() {
  printf '%s' "${1:-}" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

json_bool() {
  case "${1:-}" in
    true|TRUE|True|1|yes|YES|on|ON)
      printf 'true'
      ;;
    false|FALSE|False|0|no|NO|off|OFF|'')
      printf 'false'
      ;;
    *)
      echo "Invalid boolean value: ${1}" >&2
      return 1
      ;;
  esac
}

require_env() {
  name="$1"
  if [ -z "$(eval "printf '%s' \"\${$name:-}\"")" ]; then
    echo "Missing required environment variable: ${name}" >&2
    return 1
  fi
}

trim() {
  printf '%s' "${1:-}" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

build_agents_json() {
  csv="$1"
  rendered='['
  first='true'
  old_ifs="$IFS"
  IFS=','

  for raw_id in $csv; do
    agent_id="$(trim "${raw_id}")"
    [ -n "${agent_id}" ] || continue

    if [ "${first}" = 'false' ]; then
      rendered="${rendered},"
    fi

    rendered="${rendered}{\"agentId\":\"$(json_escape "${agent_id}")\"}"
    first='false'
  done

  IFS="$old_ifs"

  if [ "${first}" = 'true' ]; then
    echo "No agent IDs were provided in TE_SOURCE_AGENT_IDS." >&2
    return 1
  fi

  rendered="${rendered}]"
  printf '%s' "${rendered}"
}

source_agents_csv_for_scope() {
  scope="$1"
  case "${scope}" in
    app)
      printf '%s' "${TE_APP_SOURCE_AGENT_IDS:-${TE_SOURCE_AGENT_IDS:-}}"
      ;;
    media)
      printf '%s' "${TE_MEDIA_SOURCE_AGENT_IDS:-${TE_SOURCE_AGENT_IDS:-}}"
      ;;
    *)
      echo "Unsupported source-agent scope: ${scope}" >&2
      return 1
      ;;
  esac
}

build_agents_json_for_scope() {
  scope="$1"
  csv="$(source_agents_csv_for_scope "${scope}")"

  if [ -z "${csv}" ]; then
    case "${scope}" in
      app)
        echo "Missing required environment variable: TE_APP_SOURCE_AGENT_IDS or TE_SOURCE_AGENT_IDS" >&2
        ;;
      media)
        echo "Missing required environment variable: TE_MEDIA_SOURCE_AGENT_IDS or TE_SOURCE_AGENT_IDS" >&2
        ;;
    esac
    return 1
  fi

  build_agents_json "${csv}"
}

with_account_group() {
  path="$1"
  if [ -n "${THOUSANDEYES_ACCOUNT_GROUP_ID:-}" ]; then
    printf '%s?aid=%s' "${path}" "${THOUSANDEYES_ACCOUNT_GROUP_ID}"
  else
    printf '%s' "${path}"
  fi
}

request_json() {
  method="$1"
  path="$2"
  payload="$3"
  url="${THOUSANDEYES_API_BASE_URL%/}${path}"
  response_file="$(mktemp)"

  status="$(
    curl -sS \
      -o "${response_file}" \
      -w '%{http_code}' \
      -X "${method}" \
      -H "Authorization: Bearer ${THOUSANDEYES_BEARER_TOKEN}" \
      -H 'Accept: application/json' \
      -H 'Content-Type: application/json' \
      --data "${payload}" \
      "${url}"
  )"

  cat "${response_file}"
  echo
  rm -f "${response_file}"

  case "${status}" in
    2*)
      ;;
    *)
      echo "ThousandEyes API request failed with status ${status}." >&2
      return 1
      ;;
  esac
}

submit_test() {
  collection_path="$1"
  payload="$2"
  existing_test_id="${3:-}"
  method='POST'
  path=''

  if [ -n "${existing_test_id}" ]; then
    method='PUT'
    path="$(with_account_group "${collection_path}/${existing_test_id}")"
  else
    path="$(with_account_group "${collection_path}")"
  fi

  request_json "${method}" "${path}" "${payload}"
}

build_rtsp_tcp_payload() {
  require_env TE_RTSP_SERVER
  agents_json="$(build_agents_json_for_scope app)"

  printf '%s' \
    "{\
\"testName\":\"$(json_escape "${TE_RTSP_TCP_TEST_NAME:-RTSP-TCP-8554}")\",\
\"description\":\"$(json_escape "${TE_RTSP_TCP_DESCRIPTION:-RTSP control-plane reachability and path test from the Kubernetes demo feed}")\",\
\"interval\":${TE_RTSP_TCP_INTERVAL:-${TE_INTERVAL:-60}},\
\"enabled\":$(json_bool "${TE_RTSP_TCP_ENABLED:-true}"),\
\"alertsEnabled\":$(json_bool "${TE_RTSP_TCP_ALERTS_ENABLED:-${TE_ALERTS_ENABLED:-true}}"),\
\"protocol\":\"tcp\",\
\"server\":\"$(json_escape "${TE_RTSP_SERVER}")\",\
\"port\":${TE_RTSP_PORT:-8554},\
\"probeMode\":\"$(json_escape "${TE_RTSP_TCP_PROBE_MODE:-auto}")\",\
\"pathTraceMode\":\"$(json_escape "${TE_RTSP_TCP_PATH_TRACE_MODE:-classic}")\",\
\"numPathTraces\":${TE_RTSP_TCP_NUM_PATH_TRACES:-3},\
\"fixedPacketRate\":${TE_RTSP_TCP_FIXED_PACKET_RATE:-25},\
\"randomizedStartTime\":$(json_bool "${TE_RTSP_TCP_RANDOMIZED_START_TIME:-false}"),\
\"dscpId\":\"$(json_escape "${TE_RTSP_TCP_DSCP_ID:-${TE_DSCP_ID:-0}}")\",\
\"agents\":${agents_json}\
}"
}

build_udp_media_payload() {
  udp_target_agent_id=""

  agents_json="$(build_agents_json_for_scope media)"
  udp_target_agent_id="${TE_UDP_TARGET_AGENT_ID:-${TE_TARGET_AGENT_ID:-}}"

  if [ -z "${udp_target_agent_id}" ]; then
    echo "Missing required environment variable: TE_UDP_TARGET_AGENT_ID or TE_TARGET_AGENT_ID" >&2
    return 1
  fi

  printf '%s' \
    "{\
\"testName\":\"$(json_escape "${TE_UDP_MEDIA_TEST_NAME:-UDP-Media-Path}")\",\
\"description\":\"$(json_escape "${TE_UDP_MEDIA_DESCRIPTION:-Agent-to-agent UDP transport proxy for the RTSP media path}")\",\
\"interval\":${TE_UDP_MEDIA_INTERVAL:-${TE_INTERVAL:-60}},\
\"enabled\":$(json_bool "${TE_UDP_MEDIA_ENABLED:-true}"),\
\"alertsEnabled\":$(json_bool "${TE_UDP_MEDIA_ALERTS_ENABLED:-${TE_ALERTS_ENABLED:-true}}"),\
\"protocol\":\"udp\",\
\"direction\":\"$(json_escape "${TE_A2A_DIRECTION:-bidirectional}")\",\
\"port\":${TE_A2A_PORT:-5004},\
\"targetAgentId\":\"$(json_escape "${udp_target_agent_id}")\",\
\"throughputMeasurements\":$(json_bool "${TE_A2A_THROUGHPUT_MEASUREMENTS:-true}"),\
\"throughputDuration\":${TE_A2A_THROUGHPUT_DURATION_MS:-10000},\
\"throughputRate\":${TE_A2A_THROUGHPUT_RATE_MBPS:-10},\
\"fixedPacketRate\":${TE_A2A_FIXED_PACKET_RATE:-50},\
\"numPathTraces\":${TE_A2A_NUM_PATH_TRACES:-3},\
\"randomizedStartTime\":$(json_bool "${TE_A2A_RANDOMIZED_START_TIME:-false}"),\
\"dscpId\":\"$(json_escape "${TE_A2A_DSCP_ID:-${TE_DSCP_ID:-0}}")\",\
\"agents\":${agents_json}\
}"
}

build_rtp_stream_payload() {
  require_env TE_TARGET_AGENT_ID
  agents_json="$(build_agents_json_for_scope media)"

  printf '%s' \
    "{\
\"testName\":\"$(json_escape "${TE_RTP_STREAM_TEST_NAME:-RTP-Stream-Proxy}")\",\
\"description\":\"$(json_escape "${TE_RTP_STREAM_DESCRIPTION:-Scheduled RTP proxy test for RTSP media quality}")\",\
\"interval\":${TE_RTP_STREAM_INTERVAL:-${TE_INTERVAL:-60}},\
\"enabled\":$(json_bool "${TE_RTP_STREAM_ENABLED:-true}"),\
\"alertsEnabled\":$(json_bool "${TE_RTP_STREAM_ALERTS_ENABLED:-${TE_ALERTS_ENABLED:-true}}"),\
\"codecId\":\"$(json_escape "${TE_VOICE_CODEC_ID:-0}")\",\
\"dscpId\":\"$(json_escape "${TE_VOICE_DSCP_ID:-${TE_DSCP_ID:-0}}")\",\
\"duration\":${TE_VOICE_DURATION_SEC:-10},\
\"jitterBuffer\":${TE_VOICE_JITTER_BUFFER:-40},\
\"numPathTraces\":${TE_VOICE_NUM_PATH_TRACES:-3},\
\"port\":${TE_VOICE_PORT:-49152},\
\"randomizedStartTime\":$(json_bool "${TE_VOICE_RANDOMIZED_START_TIME:-false}"),\
\"targetAgentId\":\"$(json_escape "${TE_TARGET_AGENT_ID}")\",\
\"agents\":${agents_json}\
}"
}

build_trace_map_payload() {
  require_env TE_TRACE_MAP_TEST_URL
  agents_json="$(build_agents_json_for_scope app)"

  printf '%s' \
    "{\
\"testName\":\"$(json_escape "${TE_TRACE_MAP_TEST_NAME:-aleccham-broadcast-trace-map}")\",\
\"description\":\"$(json_escape "${TE_TRACE_MAP_DESCRIPTION:-Demo Monkey-sensitive public trace map health check through the frontend gateway}")\",\
\"interval\":${TE_TRACE_MAP_INTERVAL:-${TE_INTERVAL:-60}},\
\"enabled\":$(json_bool "${TE_TRACE_MAP_ENABLED:-true}"),\
\"alertsEnabled\":$(json_bool "${TE_TRACE_MAP_ALERTS_ENABLED:-${TE_ALERTS_ENABLED:-true}}"),\
\"url\":\"$(json_escape "${TE_TRACE_MAP_TEST_URL}")\",\
\"desiredStatusCode\":\"$(json_escape "${TE_TRACE_MAP_DESIRED_STATUS_CODE:-200}")\",\
\"httpTimeLimit\":${TE_TRACE_MAP_HTTP_TIME_LIMIT:-15},\
\"httpTargetTime\":${TE_TRACE_MAP_HTTP_TARGET_TIME_MS:-1000},\
\"httpVersion\":${TE_TRACE_MAP_HTTP_VERSION:-2},\
\"networkMeasurements\":$(json_bool "${TE_TRACE_MAP_NETWORK_MEASUREMENTS:-true}"),\
\"numPathTraces\":${TE_TRACE_MAP_NUM_PATH_TRACES:-3},\
\"randomizedStartTime\":$(json_bool "${TE_TRACE_MAP_RANDOMIZED_START_TIME:-false}"),\
\"distributedTracing\":$(json_bool "${TE_TRACE_MAP_DISTRIBUTED_TRACING:-true}"),\
\"protocol\":\"tcp\",\
\"dscpId\":\"$(json_escape "${TE_TRACE_MAP_DSCP_ID:-${TE_DSCP_ID:-0}}")\",\
\"agents\":${agents_json}\
}"
}

build_broadcast_payload() {
  require_env TE_BROADCAST_TEST_URL
  agents_json="$(build_agents_json_for_scope app)"

  printf '%s' \
    "{\
\"testName\":\"$(json_escape "${TE_BROADCAST_TEST_NAME:-aleccham-broadcast-playback}")\",\
\"description\":\"$(json_escape "${TE_BROADCAST_DESCRIPTION:-Demo Monkey-sensitive HLS manifest fetch through the frontend gateway}")\",\
\"interval\":${TE_BROADCAST_INTERVAL:-${TE_INTERVAL:-60}},\
\"enabled\":$(json_bool "${TE_BROADCAST_ENABLED:-true}"),\
\"alertsEnabled\":$(json_bool "${TE_BROADCAST_ALERTS_ENABLED:-${TE_ALERTS_ENABLED:-true}}"),\
\"url\":\"$(json_escape "${TE_BROADCAST_TEST_URL}")\",\
\"desiredStatusCode\":\"$(json_escape "${TE_BROADCAST_DESIRED_STATUS_CODE:-200}")\",\
\"contentRegex\":\"$(json_escape "${TE_BROADCAST_CONTENT_REGEX:-#EXTM3U}")\",\
\"downloadLimit\":${TE_BROADCAST_DOWNLOAD_LIMIT:-4096},\
\"httpTimeLimit\":${TE_BROADCAST_HTTP_TIME_LIMIT:-15},\
\"httpTargetTime\":${TE_BROADCAST_HTTP_TARGET_TIME_MS:-1000},\
\"httpVersion\":${TE_BROADCAST_HTTP_VERSION:-2},\
\"networkMeasurements\":$(json_bool "${TE_BROADCAST_NETWORK_MEASUREMENTS:-true}"),\
\"numPathTraces\":${TE_BROADCAST_NUM_PATH_TRACES:-3},\
\"randomizedStartTime\":$(json_bool "${TE_BROADCAST_RANDOMIZED_START_TIME:-false}"),\
\"protocol\":\"tcp\",\
\"dscpId\":\"$(json_escape "${TE_BROADCAST_DSCP_ID:-${TE_DSCP_ID:-0}}")\",\
\"agents\":${agents_json}\
}"
}

create_rtsp_tcp() {
  submit_test "/tests/agent-to-server" "$(build_rtsp_tcp_payload)" "${TE_RTSP_TCP_TEST_ID:-}"
}

create_udp_media() {
  submit_test "/tests/agent-to-agent" "$(build_udp_media_payload)" "${TE_UDP_MEDIA_TEST_ID:-}"
}

create_rtp_stream() {
  submit_test "/tests/voice" "$(build_rtp_stream_payload)" "${TE_RTP_STREAM_TEST_ID:-}"
}

create_demo_monkey_trace_map() {
  submit_test "/tests/http-server" "$(build_trace_map_payload)" "${TE_TRACE_MAP_TEST_ID:-}"
}

create_demo_monkey_broadcast() {
  submit_test "/tests/http-server" "$(build_broadcast_payload)" "${TE_BROADCAST_TEST_ID:-}"
}

require_env THOUSANDEYES_BEARER_TOKEN

case "${1:-create-all}" in
  create-rtsp-tcp)
    create_rtsp_tcp
    ;;
  create-udp-media)
    create_udp_media
    ;;
  create-rtp-stream)
    create_rtp_stream
    ;;
  create-demo-monkey-trace-map)
    create_demo_monkey_trace_map
    ;;
  create-demo-monkey-broadcast)
    create_demo_monkey_broadcast
    ;;
  create-demo-monkey-http)
    create_demo_monkey_trace_map
    create_demo_monkey_broadcast
    ;;
  create-all)
    create_rtsp_tcp
    create_udp_media
    create_rtp_stream
    create_demo_monkey_trace_map
    create_demo_monkey_broadcast
    ;;
  *)
    echo "Unsupported action: ${1:-}" >&2
    exit 1
    ;;
esac
