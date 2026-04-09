#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
THOUSANDEYES_API_BASE_URL = "https://api.thousandeyes.com/v7"

TEST_DEFINITIONS = (
    {
        "slot": "trace_map",
        "endpoint": "http-server",
        "default_name": "aleccham-broadcast-trace-map",
        "id_env": "TE_TRACE_MAP_TEST_ID",
        "name_env": "TE_TRACE_MAP_TEST_NAME",
        "url_env": "TE_TRACE_MAP_TEST_URL",
        "source_agents_env": "TE_SOURCE_AGENT_IDS",
        "distributed_tracing": True,
        "network_measurements": True,
        "require_metric_stream": True,
    },
    {
        "slot": "broadcast_playback",
        "endpoint": "http-server",
        "default_name": "aleccham-broadcast-playback",
        "id_env": "TE_BROADCAST_TEST_ID",
        "name_env": "TE_BROADCAST_TEST_NAME",
        "url_env": "TE_BROADCAST_TEST_URL",
        "source_agents_env": "TE_SOURCE_AGENT_IDS",
        "network_measurements": True,
        "require_metric_stream": True,
    },
    {
        "slot": "rtsp",
        "endpoint": "agent-to-server",
        "default_name": "RTSP-TCP-8554",
        "id_env": "TE_RTSP_TCP_TEST_ID",
        "name_env": "TE_RTSP_TCP_TEST_NAME",
        "server_env": "TE_RTSP_SERVER",
        "port_env": "TE_RTSP_PORT",
        "source_agents_env": "TE_SOURCE_AGENT_IDS",
        "network_measurements": True,
        "require_metric_stream": True,
    },
    {
        "slot": "udp",
        "endpoint": "agent-to-agent",
        "default_name": "UDP-Media-Path",
        "id_env": "TE_UDP_MEDIA_TEST_ID",
        "name_env": "TE_UDP_MEDIA_TEST_NAME",
        "port_env": "TE_A2A_PORT",
        "source_agents_env": "TE_SOURCE_AGENT_IDS",
        "target_agent_env": "TE_UDP_TARGET_AGENT_ID",
        "target_agent_fallback_env": "TE_TARGET_AGENT_ID",
        "require_metric_stream": True,
    },
    {
        "slot": "rtp",
        "endpoint": "voice",
        "default_name": "RTP-Stream-Proxy",
        "id_env": "TE_RTP_STREAM_TEST_ID",
        "name_env": "TE_RTP_STREAM_TEST_NAME",
        "source_agents_env": "TE_SOURCE_AGENT_IDS",
        "target_agent_env": "TE_TARGET_AGENT_ID",
        "require_metric_stream": True,
    },
)


class JsonApi:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def get(self, path: str) -> dict[str, Any]:
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self.token}",
            },
        )
        try:
            with urllib.request.urlopen(request) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise SystemExit(
                f"ThousandEyes API request failed with HTTP {exc.code}: {body}"
            ) from exc


def load_env_file(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        name, value = line.split("=", 1)
        name = name.strip()
        value = value.strip()
        if not name or name in os.environ:
            continue

        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]

        os.environ[name] = value


def env_value(name: str) -> str | None:
    value = os.environ.get(name, "").strip()
    return value or None


def require_value(name: str) -> str:
    value = env_value(name)
    if value is None:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


def env_csv(name: str) -> list[str]:
    return [item.strip() for item in os.environ.get(name, "").split(",") if item.strip()]


def resolve_test(
    api: JsonApi,
    account_group_id: str,
    definition: dict[str, Any],
) -> dict[str, Any]:
    test_id = env_value(definition["id_env"])
    if test_id:
        return api.get(
            f"/tests/{definition['endpoint']}/{urllib.parse.quote(test_id)}"
            f"?aid={urllib.parse.quote(account_group_id)}&expand=agent"
        )

    test_name = env_value(definition["name_env"]) or definition["default_name"]
    payload = api.get(
        f"/tests/{definition['endpoint']}?aid={urllib.parse.quote(account_group_id)}"
    )
    matches = [
        test for test in payload.get("tests", []) if test.get("testName") == test_name
    ]
    if not matches:
        raise SystemExit(
            f"No ThousandEyes {definition['endpoint']} test named {test_name!r} was found."
        )
    if len(matches) > 1:
        raise SystemExit(
            f"Multiple ThousandEyes {definition['endpoint']} tests named {test_name!r} were found."
        )

    resolved_test_id = str(matches[0]["testId"])
    return api.get(
        f"/tests/{definition['endpoint']}/{urllib.parse.quote(resolved_test_id)}"
        f"?aid={urllib.parse.quote(account_group_id)}&expand=agent"
    )


def agent_inventory(api: JsonApi, account_group_id: str) -> dict[str, dict[str, Any]]:
    payload = api.get(f"/agents?aid={urllib.parse.quote(account_group_id)}")
    return {
        str(agent["agentId"]): agent
        for agent in payload.get("agents", [])
        if agent.get("agentId") is not None
    }


def metric_streams(api: JsonApi, account_group_id: str) -> list[dict[str, Any]]:
    payload = api.get(f"/stream?aid={urllib.parse.quote(account_group_id)}")
    streams = payload if isinstance(payload, list) else payload.get("streams", [])
    return [
        stream
        for stream in streams
        if stream.get("enabled") is True
        and stream.get("type") == "opentelemetry"
        and stream.get("signal") == "metric"
    ]


def stream_matches_test(stream: dict[str, Any], test: dict[str, Any]) -> bool:
    test_id = str(test.get("testId", "")).strip()
    if not test_id:
        return False

    for matched in stream.get("testMatch", []):
        if str(matched.get("id", "")).strip() == test_id:
            return True

    if stream.get("testMatch"):
        return False

    configured_test_types = {
        str(value).strip()
        for value in (((stream.get("filters") or {}).get("testTypes") or {}).get("values") or [])
        if str(value).strip()
    }
    if not configured_test_types:
        return True
    return str(test.get("type", "")).strip() in configured_test_types


def expected_target_agent_id(definition: dict[str, Any]) -> str | None:
    if definition.get("target_agent_env"):
        value = env_value(definition["target_agent_env"])
        if value:
            return value
    if definition.get("target_agent_fallback_env"):
        value = env_value(definition["target_agent_fallback_env"])
        if value:
            return value
    return None


def split_server_host_port(server: str | None) -> tuple[str | None, str | None]:
    if not server:
        return None, None
    host = str(server).strip()
    if not host:
        return None, None
    if host.count(":") == 1:
        maybe_host, maybe_port = host.rsplit(":", 1)
        if maybe_port.isdigit():
            return maybe_host, maybe_port
    return host, None


def enterprise_agent_is_online(agent: dict[str, Any] | None) -> bool:
    if agent is None:
        return False
    if agent.get("agentType") != "enterprise":
        return True
    return (agent.get("agentState") or "").strip().lower() == "online"


def validation_errors(
    definition: dict[str, Any],
    test: dict[str, Any],
    agents_by_id: dict[str, dict[str, Any]],
    streams: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    test_name = str(test.get("testName", definition["default_name"]))
    assigned_agent_ids = [
        str(agent["agentId"])
        for agent in test.get("agents", [])
        if agent.get("agentId") is not None
    ]
    expected_agent_ids = env_csv(definition.get("source_agents_env", ""))

    if not assigned_agent_ids:
        errors.append(f"{test_name} has no assigned source agents.")

    if expected_agent_ids and sorted(assigned_agent_ids) != sorted(expected_agent_ids):
        errors.append(
            f"{test_name} source agents drifted: expected {','.join(expected_agent_ids)}, "
            f"got {','.join(assigned_agent_ids)}."
        )

    for agent_id in assigned_agent_ids:
        agent = agents_by_id.get(agent_id)
        if agent is None:
            errors.append(
                f"{test_name} references source agent {agent_id}, but it is not visible in the account group."
            )
            continue
        if agent.get("agentType") == "enterprise" and not enterprise_agent_is_online(agent):
            last_seen = agent.get("lastSeen", "unknown")
            errors.append(
                f"{test_name} references offline enterprise source agent {agent_id} "
                f"(last seen {last_seen})."
            )

    for agent_id in expected_agent_ids:
        agent = agents_by_id.get(agent_id)
        if agent is None:
            errors.append(
                f"Configured source agent {agent_id} for {test_name} is not visible in the account group."
            )
            continue
        if agent.get("agentType") == "enterprise" and not enterprise_agent_is_online(agent):
            last_seen = agent.get("lastSeen", "unknown")
            errors.append(
                f"Configured source agent {agent_id} for {test_name} is offline "
                f"(last seen {last_seen})."
            )

    if definition.get("distributed_tracing") is True and not test.get("distributedTracing"):
        errors.append(f"{test_name} must set distributedTracing=true.")

    if definition.get("network_measurements") is True and test.get("networkMeasurements") is not True:
        errors.append(f"{test_name} must set networkMeasurements=true.")

    expected_url = env_value(definition.get("url_env", ""))
    if expected_url and test.get("url") != expected_url:
        errors.append(
            f"{test_name} URL drifted: expected {expected_url}, got {test.get('url')}."
        )

    expected_server = env_value(definition.get("server_env", ""))
    expected_port = env_value(definition.get("port_env", ""))
    if expected_server or expected_port:
        actual_host, embedded_port = split_server_host_port(test.get("server"))
        actual_port = str(test.get("port") or embedded_port or "").strip() or None
        if expected_server and actual_host != expected_server:
            errors.append(
                f"{test_name} server drifted: expected {expected_server}, got {actual_host or test.get('server')}."
            )
        if expected_port and actual_port != expected_port:
            errors.append(
                f"{test_name} port drifted: expected {expected_port}, got {actual_port or '<missing>'}."
            )

    expected_target = expected_target_agent_id(definition)
    actual_target = str(test.get("targetAgentId") or "").strip() or None
    if expected_target and actual_target != expected_target:
        errors.append(
            f"{test_name} target agent drifted: expected {expected_target}, got {actual_target or '<missing>'}."
        )
    if expected_target:
        target_agent = agents_by_id.get(expected_target)
        if target_agent is None:
            errors.append(
                f"Configured target agent {expected_target} for {test_name} is not visible in the account group."
            )
        elif target_agent.get("agentType") == "enterprise" and not enterprise_agent_is_online(target_agent):
            last_seen = target_agent.get("lastSeen", "unknown")
            errors.append(
                f"Configured target agent {expected_target} for {test_name} is offline "
                f"(last seen {last_seen})."
            )

    if definition.get("require_metric_stream") and not any(
        stream_matches_test(stream, test) for stream in streams
    ):
        errors.append(
            f"No enabled ThousandEyes OTel metric stream covers {test_name} ({test.get('testId')})."
        )

    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify the repo-managed ThousandEyes demo tests are aligned with the current env and stream configuration."
    )
    parser.add_argument(
        "--env-file",
        default=str(DEFAULT_ENV_FILE),
        help="Path to the repo env file. Defaults to the repo-root .env.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    load_env_file(Path(args.env_file))

    token = require_value("THOUSANDEYES_BEARER_TOKEN")
    account_group_id = require_value("THOUSANDEYES_ACCOUNT_GROUP_ID")

    api = JsonApi(THOUSANDEYES_API_BASE_URL, token)
    agents_by_id = agent_inventory(api, account_group_id)
    streams = metric_streams(api, account_group_id)

    failures = 0
    for definition in TEST_DEFINITIONS:
        try:
            test = resolve_test(api, account_group_id, definition)
        except SystemExit as exc:
            print(f"FAIL: {exc}", file=sys.stderr)
            failures += 1
            continue

        errors = validation_errors(definition, test, agents_by_id, streams)
        if errors:
            for error in errors:
                print(f"FAIL: {error}", file=sys.stderr)
            failures += 1
            continue

        print(
            f"PASS: {test.get('testName')} ({test.get('testId')}) is aligned with the repo configuration."
        )

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
