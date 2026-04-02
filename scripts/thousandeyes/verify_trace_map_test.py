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


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
THOUSANDEYES_API_BASE_URL = "https://api.thousandeyes.com/v7"


class JsonApi:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def get(self, path: str) -> dict:
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


def require_value(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


def env_csv(name: str) -> list[str]:
    return [item.strip() for item in os.environ.get(name, "").split(",") if item.strip()]


def resolve_trace_map_test(
    api: JsonApi,
    account_group_id: str,
    test_id: str | None,
    test_name: str | None,
) -> dict:
    if test_id:
        return api.get(
            f"/tests/http-server/{urllib.parse.quote(test_id)}"
            f"?aid={urllib.parse.quote(account_group_id)}&expand=agent"
        )

    if not test_name:
        raise SystemExit(
            "Set TE_TRACE_MAP_TEST_ID or TE_TRACE_MAP_TEST_NAME to resolve the trace-map test."
        )

    payload = api.get(f"/tests/http-server?aid={urllib.parse.quote(account_group_id)}")
    matches = [
        test for test in payload.get("tests", []) if test.get("testName") == test_name
    ]
    if not matches:
        raise SystemExit(f"No ThousandEyes HTTP Server test named {test_name!r} was found.")
    if len(matches) > 1:
        raise SystemExit(
            f"Multiple ThousandEyes HTTP Server tests named {test_name!r} were found."
        )

    resolved_test_id = matches[0].get("testId")
    return api.get(
        f"/tests/http-server/{urllib.parse.quote(str(resolved_test_id))}"
        f"?aid={urllib.parse.quote(account_group_id)}&expand=agent"
    )


def agent_inventory(api: JsonApi, account_group_id: str) -> dict[str, dict]:
    payload = api.get(f"/agents?aid={urllib.parse.quote(account_group_id)}")
    return {
        str(agent["agentId"]): agent
        for agent in payload.get("agents", [])
        if agent.get("agentId") is not None
    }


def validation_errors(
    test: dict,
    agents_by_id: dict[str, dict],
    expected_url: str | None = None,
    expected_agent_ids: list[str] | None = None,
) -> list[str]:
    errors: list[str] = []
    assigned_agent_ids = [
        str(agent["agentId"])
        for agent in test.get("agents", [])
        if agent.get("agentId") is not None
    ]

    if not test.get("distributedTracing"):
        errors.append("Trace-map test must set distributedTracing=true.")

    if expected_url and test.get("url") != expected_url:
        errors.append(
            f"Trace-map test URL drifted: expected {expected_url}, got {test.get('url')}."
        )

    if not assigned_agent_ids:
        errors.append("Trace-map test has no assigned source agents.")

    expected_agent_ids = expected_agent_ids or []
    if expected_agent_ids and sorted(assigned_agent_ids) != sorted(expected_agent_ids):
        errors.append(
            "Trace-map test agents drifted: "
            f"expected {','.join(expected_agent_ids)}, got {','.join(assigned_agent_ids)}."
        )

    online_assigned_agents = 0
    for agent_id in assigned_agent_ids:
        agent = agents_by_id.get(agent_id)
        if agent is None:
            errors.append(
                f"Trace-map test references agent {agent_id}, but it is not visible in the account group."
            )
            continue

        agent_state = (agent.get("agentState") or "").strip().lower()
        if agent_state == "online":
            online_assigned_agents += 1

    if assigned_agent_ids and online_assigned_agents == 0:
        errors.append("Trace-map test has no online assigned agents.")

    for agent_id in expected_agent_ids:
        agent = agents_by_id.get(agent_id)
        if agent is None:
            errors.append(
                f"Configured source agent {agent_id} is not visible in the account group."
            )
            continue

        agent_state = (agent.get("agentState") or "").strip().lower()
        if agent_state != "online":
            last_seen = agent.get("lastSeen", "unknown")
            errors.append(
                f"Configured source agent {agent_id} is {agent_state or 'unknown'} "
                f"(last seen {last_seen})."
            )

    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify that the ThousandEyes trace-map test is configured for Splunk distributed tracing."
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
    test_id = os.environ.get("TE_TRACE_MAP_TEST_ID", "").strip() or None
    test_name = os.environ.get("TE_TRACE_MAP_TEST_NAME", "").strip() or None
    expected_url = os.environ.get("TE_TRACE_MAP_TEST_URL", "").strip() or None
    expected_agent_ids = env_csv("TE_SOURCE_AGENT_IDS")

    api = JsonApi(THOUSANDEYES_API_BASE_URL, token)
    test = resolve_trace_map_test(api, account_group_id, test_id, test_name)
    agents_by_id = agent_inventory(api, account_group_id)
    errors = validation_errors(
        test=test,
        agents_by_id=agents_by_id,
        expected_url=expected_url,
        expected_agent_ids=expected_agent_ids,
    )
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1

    assigned_agent_ids = ",".join(
        str(agent["agentId"])
        for agent in test.get("agents", [])
        if agent.get("agentId") is not None
    )
    print(
        "PASS: ThousandEyes trace-map test is configured correctly "
        f"(testId={test.get('testId')}, agents={assigned_agent_ids})."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
