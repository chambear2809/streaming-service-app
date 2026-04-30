#!/usr/bin/env python3
"""Create or update the ThousandEyes -> Splunk O11y metric stream for this repo.

The repo already manages ThousandEyes test creation and Splunk dashboard sync.
This helper fills in the remaining glue by reconciling one ThousandEyes
OpenTelemetry metric stream that exports the repo's demo tests into Splunk
Observability Cloud.

The script reads the repo-root .env by default. Exported shell variables win.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
DEFAULT_API_BASE_URL = "https://api.thousandeyes.com/v7"
DEFAULT_ENDPOINT_TYPE = "http"
DEFAULT_SIGNAL = "metric"
DEFAULT_DATA_MODEL_VERSION = "v2"
DEFAULT_CONTENT_TYPE = "application/x-protobuf"

TEST_SLOT_CONFIG = {
    "rtsp": {
        "endpoint": "agent-to-server",
        "test_type": "agent-to-server",
        "default_names": ("RTSP-TCP-8554", "RTSP-TCP-554"),
        "name_env": "TE_RTSP_TCP_TEST_NAME",
        "id_env": "TE_RTSP_TCP_TEST_ID",
    },
    "udp": {
        "endpoint": "agent-to-agent",
        "test_type": "agent-to-agent",
        "default_names": ("UDP-Media-Path",),
        "name_env": "TE_UDP_MEDIA_TEST_NAME",
        "id_env": "TE_UDP_MEDIA_TEST_ID",
    },
    "rtp": {
        "endpoint": "voice",
        "test_type": "voice",
        "default_names": ("RTP-Stream-Proxy",),
        "name_env": "TE_RTP_STREAM_TEST_NAME",
        "id_env": "TE_RTP_STREAM_TEST_ID",
    },
    "trace_map": {
        "endpoint": "http-server",
        "test_type": "http-server",
        "default_names": ("aleccham-broadcast-trace-map",),
        "name_env": "TE_TRACE_MAP_TEST_NAME",
        "id_env": "TE_TRACE_MAP_TEST_ID",
    },
    "broadcast_playback": {
        "endpoint": "http-server",
        "test_type": "http-server",
        "default_names": ("aleccham-broadcast-playback",),
        "name_env": "TE_BROADCAST_TEST_NAME",
        "id_env": "TE_BROADCAST_TEST_ID",
    },
}


@dataclass(frozen=True)
class DesiredTest:
    slot: str
    name: str
    test_id: str
    domain: str = "cea"


class JsonApi:
    def __init__(self, base_url: str, token: str, dry_run: bool = False) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.dry_run = dry_run

    def request(
        self,
        method: str,
        path: str,
        *,
        payload: Optional[Dict[str, Any]] = None,
        query: Optional[Dict[str, Any]] = None,
    ) -> Any:
        url = self._build_url(path, query)
        body = None
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.token}",
        }
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode("utf-8")
        if self.dry_run and method.upper() != "GET":
            print(f"[dry-run] {method.upper()} {url}", file=sys.stderr)
            if payload is not None:
                print(json.dumps(payload, indent=2), file=sys.stderr)
            return payload

        request = urllib.request.Request(url, headers=headers, data=body, method=method.upper())
        try:
            with urllib.request.urlopen(request) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            response_body = error.read().decode("utf-8", errors="replace")
            raise SystemExit(f"{method.upper()} {url} failed with HTTP {error.code}: {response_body}") from error
        except urllib.error.URLError as error:
            raise SystemExit(f"{method.upper()} {url} failed: {error}") from error

        if not raw:
            return {}
        return json.loads(raw)

    def _build_url(self, path: str, query: Optional[Dict[str, Any]]) -> str:
        url = f"{self.base_url}{path}"
        if not query:
            return url
        pairs: List[tuple[str, str]] = []
        for key, value in query.items():
            if value is None:
                continue
            pairs.append((key, str(value)))
        if not pairs:
            return url
        return f"{url}?{urllib.parse.urlencode(pairs)}"


def load_env_file(path: Path) -> Dict[str, str]:
    values: Dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        name = name.strip()
        value = value.strip()
        if not name:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[name] = value
    return values


def env_value(env_file: Dict[str, str], name: str, default: Optional[str] = None) -> Optional[str]:
    value = os.environ.get(name)
    if value is not None and value != "":
        return value
    value = env_file.get(name)
    if value is not None and value != "":
        return value
    return default


def require_value(env_file: Dict[str, str], name: str) -> str:
    value = env_value(env_file, name)
    if value is None:
        raise SystemExit(
            f"Missing required setting {name}. Set it in the repo-root .env or export it in your shell."
        )
    return value


def bool_value(raw: Optional[str], default: bool = False) -> bool:
    if raw is None or raw == "":
        return default
    lowered = raw.strip().lower()
    if lowered in {"1", "true", "yes", "on"}:
        return True
    if lowered in {"0", "false", "no", "off"}:
        return False
    raise SystemExit(f"Invalid boolean value {raw!r}.")


def build_endpoint_url(endpoint_type: str, realm: str, explicit_url: Optional[str]) -> str:
    if explicit_url:
        return explicit_url
    if endpoint_type == "http":
        return f"https://ingest.{realm}.signalfx.com/v2/datapoint/otlp"
    if endpoint_type == "grpc":
        return f"https://ingest.{realm}.signalfx.com:443"
    raise SystemExit(
        f"Unsupported THOUSANDEYES_O11Y_ENDPOINT_TYPE={endpoint_type!r}. Use 'http' or 'grpc'."
    )


def list_streams(api: JsonApi, account_group_id: str) -> List[Dict[str, Any]]:
    response = api.request("GET", "/streams", query={"aid": account_group_id})
    if isinstance(response, list):
        return response
    return response.get("streams", [])


def get_stream(api: JsonApi, account_group_id: str, stream_id: str) -> Dict[str, Any]:
    response = api.request("GET", f"/streams/{urllib.parse.quote(stream_id)}", query={"aid": account_group_id})
    if not isinstance(response, dict):
        raise SystemExit(f"Unexpected stream response for {stream_id}: {response!r}")
    return response


def list_tests_for_endpoint(api: JsonApi, account_group_id: str, endpoint: str) -> List[Dict[str, Any]]:
    response = api.request("GET", f"/tests/{endpoint}", query={"aid": account_group_id})
    return response.get("tests", [])


def resolve_tests(api: JsonApi, env_file: Dict[str, str], account_group_id: str) -> List[DesiredTest]:
    resolved: List[DesiredTest] = []
    tests_cache: Dict[str, List[Dict[str, Any]]] = {}
    skipped: List[str] = []

    for slot, config in TEST_SLOT_CONFIG.items():
        configured_name = env_value(env_file, config["name_env"])
        configured_test_id = env_value(env_file, config["id_env"])
        if configured_test_id:
            resolved.append(
                DesiredTest(
                    slot=slot,
                    name=configured_name or config["default_names"][0],
                    test_id=configured_test_id.strip(),
                )
            )
            continue

        endpoint = config["endpoint"]
        tests = tests_cache.setdefault(endpoint, list_tests_for_endpoint(api, account_group_id, endpoint))
        candidate_names = (configured_name,) if configured_name else config["default_names"]

        matched: Optional[Dict[str, Any]] = None
        for candidate_name in candidate_names:
            candidates = [test for test in tests if test.get("testName") == candidate_name]
            if not candidates:
                continue
            if len(candidates) > 1:
                ids = ", ".join(str(candidate.get("testId", "<missing>")) for candidate in candidates)
                raise SystemExit(
                    f"Multiple ThousandEyes tests named {candidate_name!r} were found ({ids}). "
                    f"Set {config['id_env']} to the exact test ID the stream should use."
                )
            matched = candidates[0]
            resolved.append(DesiredTest(slot=slot, name=candidate_name, test_id=str(matched["testId"])))
            break

        if matched is None:
            skipped.append(slot)

    if skipped:
        print(
            "Skipping repo test slots with no resolvable ThousandEyes test: "
            + ", ".join(sorted(skipped)),
            file=sys.stderr,
        )
    if not resolved:
        raise SystemExit(
            "No ThousandEyes repo tests could be resolved. Set the TE_*_TEST_ID values or create the tests first."
        )
    return resolved


def test_match_key(entry: Dict[str, Any]) -> tuple[str, str]:
    return (str(entry.get("id", "")).strip(), str(entry.get("domain", "cea")).strip() or "cea")


def normalized_test_matches(entries: Iterable[Dict[str, Any]]) -> List[Dict[str, str]]:
    seen: set[tuple[str, str]] = set()
    normalized: List[Dict[str, str]] = []
    for entry in entries:
        test_id, domain = test_match_key(entry)
        if not test_id:
            continue
        key = (test_id, domain)
        if key in seen:
            continue
        seen.add(key)
        normalized.append({"id": test_id, "domain": domain})
    return normalized


def desired_test_matches(resolved_tests: Sequence[DesiredTest]) -> List[Dict[str, str]]:
    return normalized_test_matches(
        {"id": test.test_id, "domain": test.domain}
        for test in resolved_tests
    )


def desired_test_types(resolved_tests: Sequence[DesiredTest]) -> List[str]:
    seen: set[str] = set()
    values: List[str] = []
    for test in resolved_tests:
        test_type = str(TEST_SLOT_CONFIG[test.slot]["test_type"]).strip()
        if not test_type or test_type in seen:
            continue
        seen.add(test_type)
        values.append(test_type)
    return values


def merge_required_test_type_filters(
    filters: Dict[str, Any],
    required_test_types: Sequence[str],
) -> Dict[str, Any]:
    merged = dict(filters)
    test_types = dict(merged.get("testTypes") or {})
    existing_values = [
        str(value).strip()
        for value in (test_types.get("values") or [])
        if str(value).strip()
    ]
    if existing_values:
        ordered_values = list(existing_values)
        seen = set(ordered_values)
        for test_type in required_test_types:
            if test_type not in seen:
                ordered_values.append(test_type)
                seen.add(test_type)
        test_types["values"] = ordered_values
        merged["testTypes"] = test_types
    return merged


def stream_test_ids(stream: Dict[str, Any]) -> set[str]:
    return {str(entry.get("id", "")).strip() for entry in stream.get("testMatch", []) if entry.get("id")}


def matching_stream_candidates(
    streams: Sequence[Dict[str, Any]],
    endpoint_url: str,
    endpoint_type: str,
    signal: str,
) -> List[Dict[str, Any]]:
    return [
        stream
        for stream in streams
        if stream.get("type") == "opentelemetry"
        and str(stream.get("signal", DEFAULT_SIGNAL)).strip() == signal
        and str(stream.get("endpointType", DEFAULT_ENDPOINT_TYPE)).strip() == endpoint_type
        and str(stream.get("streamEndpointUrl", "")).strip() == endpoint_url
    ]


def choose_target_stream(
    streams: Sequence[Dict[str, Any]],
    desired_test_ids: set[str],
) -> Optional[Dict[str, Any]]:
    if not streams:
        return None
    if len(streams) == 1:
        return streams[0]

    ranked = sorted(
        ((len(stream_test_ids(stream) & desired_test_ids), stream) for stream in streams),
        key=lambda item: item[0],
        reverse=True,
    )
    best_overlap = ranked[0][0]
    if best_overlap > 0:
        best_streams = [stream for overlap, stream in ranked if overlap == best_overlap]
        if len(best_streams) == 1:
            return best_streams[0]

    fully_covering = [stream for stream in streams if desired_test_ids.issubset(stream_test_ids(stream))]
    if len(fully_covering) == 1:
        return fully_covering[0]

    ids = ", ".join(str(stream.get("id", "<missing>")) for stream in streams)
    raise SystemExit(
        "Multiple matching Splunk O11y metric streams were found and none could be chosen safely "
        f"({ids}). Set THOUSANDEYES_O11Y_STREAM_ID or pass --stream-id to pin the exact stream."
    )


def build_payload(
    *,
    existing_stream: Optional[Dict[str, Any]],
    resolved_tests: Sequence[DesiredTest],
    endpoint_url: str,
    endpoint_type: str,
    splunk_token: str,
    enabled: bool,
    signal: str,
    data_model_version: str,
    content_type: str,
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "type": "opentelemetry",
        "signal": signal,
        "endpointType": endpoint_type,
        "streamEndpointUrl": endpoint_url,
        "dataModelVersion": data_model_version,
        "customHeaders": {
            "X-SF-Token": splunk_token,
            "Content-Type": content_type,
        },
        "enabled": enabled,
    }

    if existing_stream:
        for key in ("tagMatch", "endpointAgentLabel", "exporterConfig"):
            value = existing_stream.get(key)
            if value:
                payload[key] = value
        filters = existing_stream.get("filters")
        if filters:
            payload["filters"] = merge_required_test_type_filters(
                filters,
                desired_test_types(resolved_tests),
            )

    existing_matches = existing_stream.get("testMatch", []) if existing_stream else []
    payload["testMatch"] = normalized_test_matches(
        [*existing_matches, *desired_test_matches(resolved_tests)]
    )
    return payload


def build_update_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    allowed_keys = {
        "customHeaders",
        "streamEndpointUrl",
        "tagMatch",
        "testMatch",
        "enabled",
        "filters",
        "exporterConfig",
        "endpointAgentLabel",
    }
    return {key: value for key, value in payload.items() if key in allowed_keys}


def ensure_update_compatible(
    existing_stream: Dict[str, Any],
    *,
    endpoint_type: str,
    signal: str,
    data_model_version: str,
) -> None:
    if existing_stream.get("type") != "opentelemetry":
        raise SystemExit(
            f"Stream {existing_stream.get('id')} is type {existing_stream.get('type')!r}, not 'opentelemetry'."
        )
    if str(existing_stream.get("endpointType", "")).strip() != endpoint_type:
        raise SystemExit(
            f"Stream {existing_stream.get('id')} uses endpointType="
            f"{existing_stream.get('endpointType')!r}, but the requested value is {endpoint_type!r}. "
            "Pick a compatible stream or create a new one."
        )
    if str(existing_stream.get("signal", DEFAULT_SIGNAL)).strip() != signal:
        raise SystemExit(
            f"Stream {existing_stream.get('id')} uses signal={existing_stream.get('signal')!r}, "
            f"but the requested value is {signal!r}. Pick a compatible stream or create a new one."
        )
    if str(existing_stream.get("dataModelVersion", DEFAULT_DATA_MODEL_VERSION)).strip() != data_model_version:
        raise SystemExit(
            f"Stream {existing_stream.get('id')} uses dataModelVersion="
            f"{existing_stream.get('dataModelVersion')!r}, but the requested value is {data_model_version!r}. "
            "Pick a compatible stream or create a new one."
        )


def payload_summary(payload: Dict[str, Any]) -> Dict[str, Any]:
    summary = dict(payload)
    if "customHeaders" in summary:
        summary["customHeaders"] = {
            key: ("<redacted>" if value else value)
            for key, value in summary["customHeaders"].items()
        }
    return summary


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create or update the ThousandEyes OpenTelemetry metric stream into Splunk O11y."
    )
    parser.add_argument(
        "--env-file",
        default=str(DEFAULT_ENV_FILE),
        help="Path to the repo env file. Defaults to the repo-root .env.",
    )
    parser.add_argument(
        "--stream-id",
        help="Exact ThousandEyes stream ID to update. Overrides THOUSANDEYES_O11Y_STREAM_ID.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the intended create or update payload without changing ThousandEyes.",
    )
    parser.add_argument(
        "--print-json",
        action="store_true",
        help="Print the final stream JSON response to stdout.",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    env_file = load_env_file(Path(args.env_file))

    te_token = require_value(env_file, "THOUSANDEYES_BEARER_TOKEN")
    account_group_id = require_value(env_file, "THOUSANDEYES_ACCOUNT_GROUP_ID")
    splunk_realm = require_value(env_file, "SPLUNK_REALM")
    splunk_token = (
        env_value(env_file, "THOUSANDEYES_O11Y_INGEST_TOKEN")
        or env_value(env_file, "SPLUNK_ACCESS_TOKEN")
    )
    if splunk_token is None:
        raise SystemExit(
            "Missing required setting THOUSANDEYES_O11Y_INGEST_TOKEN or SPLUNK_ACCESS_TOKEN. "
            "Set a Splunk token that is allowed to write to the target OTLP ingest endpoint."
        )

    endpoint_type = env_value(env_file, "THOUSANDEYES_O11Y_ENDPOINT_TYPE", DEFAULT_ENDPOINT_TYPE) or DEFAULT_ENDPOINT_TYPE
    signal = env_value(env_file, "THOUSANDEYES_O11Y_SIGNAL", DEFAULT_SIGNAL) or DEFAULT_SIGNAL
    data_model_version = (
        env_value(env_file, "THOUSANDEYES_O11Y_DATA_MODEL_VERSION", DEFAULT_DATA_MODEL_VERSION)
        or DEFAULT_DATA_MODEL_VERSION
    )
    endpoint_url = build_endpoint_url(
        endpoint_type=endpoint_type,
        realm=splunk_realm,
        explicit_url=env_value(env_file, "THOUSANDEYES_O11Y_ENDPOINT_URL"),
    )
    enabled = bool_value(env_value(env_file, "THOUSANDEYES_O11Y_ENABLED"), default=True)
    content_type = env_value(env_file, "THOUSANDEYES_O11Y_CONTENT_TYPE", DEFAULT_CONTENT_TYPE) or DEFAULT_CONTENT_TYPE
    stream_id = args.stream_id or env_value(env_file, "THOUSANDEYES_O11Y_STREAM_ID")
    api_base_url = env_value(env_file, "THOUSANDEYES_API_BASE_URL", DEFAULT_API_BASE_URL) or DEFAULT_API_BASE_URL

    api = JsonApi(api_base_url, te_token, dry_run=args.dry_run)
    resolved_tests = resolve_tests(api, env_file, account_group_id)
    desired_ids = {test.test_id for test in resolved_tests}

    existing_stream: Optional[Dict[str, Any]] = None
    if stream_id:
        existing_stream = get_stream(api, account_group_id, stream_id)
    else:
        streams = list_streams(api, account_group_id)
        candidates = matching_stream_candidates(streams, endpoint_url, endpoint_type, signal)
        existing_stream = choose_target_stream(candidates, desired_ids)

    payload = build_payload(
        existing_stream=existing_stream,
        resolved_tests=resolved_tests,
        endpoint_url=endpoint_url,
        endpoint_type=endpoint_type,
        splunk_token=splunk_token,
        enabled=enabled,
        signal=signal,
        data_model_version=data_model_version,
        content_type=content_type,
    )

    if args.dry_run:
        print(json.dumps(payload_summary(payload), indent=2))
        return 0

    if existing_stream is None:
        response = api.request("POST", "/streams", payload=payload, query={"aid": account_group_id})
        action = "created"
    else:
        ensure_update_compatible(
            existing_stream,
            endpoint_type=endpoint_type,
            signal=signal,
            data_model_version=data_model_version,
        )
        response = api.request(
            "PUT",
            f"/streams/{urllib.parse.quote(str(existing_stream['id']))}",
            payload=build_update_payload(payload),
            query={"aid": account_group_id},
        )
        action = "updated"

    stream_id_text = str(response.get("id", existing_stream.get("id") if existing_stream else "<missing>"))
    covered = ", ".join(f"{test.name} ({test.test_id})" for test in resolved_tests)
    status = ((response.get("streamStatus") or {}).get("status")) or "unknown"
    print(f"Stream {stream_id_text} {action}.")
    print(f"Endpoint: {endpoint_url}")
    print(f"Status: {status}")
    print(f"Repo tests covered: {covered}")

    if args.print_json:
        print(json.dumps(payload_summary(response), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
