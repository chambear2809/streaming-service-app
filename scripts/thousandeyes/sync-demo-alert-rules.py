#!/usr/bin/env python3
"""
Create or update repo-managed ThousandEyes alert rules for the demo tests and
explicitly assign those rules to the repo-managed tests.
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
from typing import Any


ROOT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = ROOT_DIR / ".env"
THOUSANDEYES_API_BASE_URL = os.environ.get("THOUSANDEYES_API_BASE_URL", "https://api.thousandeyes.com/v7")


def load_env_file(env_file: Path) -> None:
    if not env_file.is_file():
        return

    for raw_line in env_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :]
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or key in os.environ:
            continue
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        elif value.startswith("'") and value.endswith("'"):
            value = value[1:-1]
        os.environ[key] = value


def require_value(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


def env_bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    normalized = value.strip().lower()
    if normalized in {"true", "1", "yes", "on"}:
        return True
    if normalized in {"false", "0", "no", "off"}:
        return False
    raise SystemExit(f"Invalid boolean value for {name}: {value}")


def env_int(name: str, default: int) -> int:
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise SystemExit(f"Invalid integer value for {name}: {value}") from exc


def env_csv(name: str) -> list[str]:
    raw = os.environ.get(name, "")
    return [item.strip() for item in raw.split(",") if item.strip()]


class JsonApi:
    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(self, method: str, path: str, query: dict[str, Any] | None = None, body: Any | None = None) -> Any:
        query_string = ""
        if query:
            encoded = urllib.parse.urlencode(query, doseq=True)
            query_string = f"?{encoded}"
        url = f"{self.base_url}{path}{query_string}"
        data = None
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/json",
        }
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request) as response:
                payload = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise SystemExit(f"ThousandEyes API {method} {path} failed with status {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise SystemExit(f"Unable to reach ThousandEyes API for {method} {path}: {exc.reason}") from exc

        if not payload:
            return {}
        try:
            return json.loads(payload)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"ThousandEyes API returned unreadable JSON for {method} {path}: {payload}") from exc


@dataclass(frozen=True)
class SlotDefinition:
    slot: str
    test_path: str
    alert_type: str
    default_rule_name: str
    test_id_env: str
    test_name_env: str
    default_test_name: str
    rule_name: str
    description: str
    default_severity: str


SLOT_DEFINITIONS = [
    SlotDefinition(
        slot="broadcast",
        test_path="http-server",
        alert_type="http-server",
        default_rule_name="Default HTTP Alert Rule 2.0",
        test_id_env="TE_BROADCAST_TEST_ID",
        test_name_env="TE_BROADCAST_TEST_NAME",
        default_test_name="aleccham-broadcast-playback",
        rule_name="[demo] playback public path",
        description="Viewer-facing playback manifest failure on the public path.",
        default_severity="critical",
    ),
    SlotDefinition(
        slot="trace_map",
        test_path="http-server",
        alert_type="http-server",
        default_rule_name="Default HTTP Alert Rule 2.0",
        test_id_env="TE_TRACE_MAP_TEST_ID",
        test_name_env="TE_TRACE_MAP_TEST_NAME",
        default_test_name="aleccham-broadcast-trace-map",
        rule_name="[demo] trace-map public path",
        description="Public trace-map API degradation for the application pivot story.",
        default_severity="major",
    ),
    SlotDefinition(
        slot="rtsp",
        test_path="agent-to-server",
        alert_type="end-to-end-server",
        default_rule_name="Default Network Alert Rule 2.0",
        test_id_env="TE_RTSP_TCP_TEST_ID",
        test_name_env="TE_RTSP_TCP_TEST_NAME",
        default_test_name="RTSP-TCP-8554",
        rule_name="[demo] rtsp control path",
        description="RTSP control-path degradation for the media contribution story.",
        default_severity="major",
    ),
    SlotDefinition(
        slot="udp",
        test_path="agent-to-agent",
        alert_type="end-to-end-agent",
        default_rule_name="Default Agent to Agent Network Alert Rule 2.0",
        test_id_env="TE_UDP_MEDIA_TEST_ID",
        test_name_env="TE_UDP_MEDIA_TEST_NAME",
        default_test_name="UDP-Media-Path",
        rule_name="[demo] udp media path",
        description="Agent-to-agent media-path loss during the transport deep dive.",
        default_severity="minor",
    ),
    SlotDefinition(
        slot="rtp",
        test_path="voice",
        alert_type="voice",
        default_rule_name="Default Voice Alert Rule 2.0",
        test_id_env="TE_RTP_STREAM_TEST_ID",
        test_name_env="TE_RTP_STREAM_TEST_NAME",
        default_test_name="RTP-Stream-Proxy",
        rule_name="[demo] rtp media quality",
        description="RTP quality degradation for the media-quality deep dive.",
        default_severity="minor",
    ),
]


def resolve_test_name(slot: SlotDefinition) -> str:
    return os.environ.get(slot.test_name_env, slot.default_test_name).strip() or slot.default_test_name


def resolve_test_id(slot: SlotDefinition, api: JsonApi, account_group_id: str) -> str:
    configured = os.environ.get(slot.test_id_env, "").strip()
    if configured:
        return configured

    tests = api.request("GET", f"/tests/{slot.test_path}", query={"aid": account_group_id}).get("tests", [])
    expected_name = resolve_test_name(slot)
    matches = [test for test in tests if test.get("testName") == expected_name]
    if not matches:
        raise SystemExit(
            f"Unable to find {slot.slot} test named {expected_name!r}. "
            f"Set {slot.test_id_env} or create the test first."
        )
    if len(matches) > 1:
        ids = ", ".join(sorted(test.get("testId", "") for test in matches))
        raise SystemExit(
            f"Multiple {slot.test_path} tests named {expected_name!r} were found ({ids}). "
            f"Set {slot.test_id_env} explicitly."
        )
    return str(matches[0]["testId"])


def alert_notifications() -> dict[str, Any] | None:
    raw_json = os.environ.get("THOUSANDEYES_ALERT_NOTIFICATIONS_JSON", "").strip()
    if raw_json:
        try:
            parsed = json.loads(raw_json)
        except json.JSONDecodeError as exc:
            raise SystemExit("THOUSANDEYES_ALERT_NOTIFICATIONS_JSON is not valid JSON.") from exc
        if not isinstance(parsed, dict):
            raise SystemExit("THOUSANDEYES_ALERT_NOTIFICATIONS_JSON must be a JSON object.")
        return parsed

    recipients = env_csv("THOUSANDEYES_ALERT_EMAIL_RECIPIENTS")
    if not recipients:
        return None

    return {
        "email": {
            "message": os.environ.get("THOUSANDEYES_ALERT_EMAIL_MESSAGE", "").strip(),
            "recipients": recipients,
        }
    }


def default_rule_map(api: JsonApi, account_group_id: str) -> dict[tuple[str, str], dict[str, Any]]:
    rules = api.request("GET", "/alerts/rules", query={"aid": account_group_id}).get("alertRules", [])
    mapped: dict[tuple[str, str], dict[str, Any]] = {}
    for rule in rules:
        if not rule.get("isDefault"):
            continue
        key = (str(rule.get("alertType")), str(rule.get("ruleName")))
        mapped[key] = rule
    return mapped


def custom_rules_by_name(api: JsonApi, account_group_id: str) -> dict[str, dict[str, Any]]:
    rules = api.request("GET", "/alerts/rules", query={"aid": account_group_id}).get("alertRules", [])
    return {str(rule.get("ruleName")): rule for rule in rules if rule.get("ruleName")}


def per_slot_severity(slot: SlotDefinition) -> str:
    env_name = f"TE_{slot.slot.upper()}_ALERT_RULE_SEVERITY"
    return os.environ.get(env_name, slot.default_severity).strip().lower() or slot.default_severity


def per_slot_minimum_sources(slot: SlotDefinition) -> int:
    env_name = f"TE_{slot.slot.upper()}_ALERT_MINIMUM_SOURCES"
    return env_int(env_name, env_int("TE_ALERT_MINIMUM_SOURCES", 1))


def per_slot_notify_on_clear(slot: SlotDefinition) -> bool:
    env_name = f"TE_{slot.slot.upper()}_ALERT_NOTIFY_ON_CLEAR"
    return env_bool(env_name, env_bool("TE_ALERT_NOTIFY_ON_CLEAR", True))


def rule_payload(
    slot: SlotDefinition,
    test_id: str,
    template_rule: dict[str, Any],
    notifications: dict[str, Any] | None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "ruleName": slot.rule_name,
        "description": slot.description,
        "alertType": slot.alert_type,
        "expression": template_rule["expression"],
        "notifyOnClear": per_slot_notify_on_clear(slot),
        "severity": per_slot_severity(slot),
        "testIds": [test_id],
        "roundsViolatingOutOf": int(template_rule.get("roundsViolatingOutOf", 3)),
        "roundsViolatingRequired": int(template_rule.get("roundsViolatingRequired", 2)),
        "minimumSources": per_slot_minimum_sources(slot),
    }

    rounds_mode = template_rule.get("roundsViolatingMode")
    if rounds_mode:
        payload["roundsViolatingMode"] = rounds_mode
    direction = template_rule.get("direction")
    if direction:
        payload["direction"] = direction
    alert_group_type = template_rule.get("alertGroupType")
    if alert_group_type:
        payload["alertGroupType"] = alert_group_type
    sensitivity_level = template_rule.get("sensitivityLevel")
    if sensitivity_level:
        payload["sensitivityLevel"] = sensitivity_level
    if notifications:
        payload["notifications"] = notifications
    return payload


def test_update_payload(rule_id: str) -> dict[str, Any]:
    return {
        "alertsEnabled": True,
        "alertRules": [rule_id],
    }


def print_plan(slot: SlotDefinition, test_id: str, rule_payload_data: dict[str, Any], rule_exists: bool, rule_id: str | None) -> None:
    action = "update" if rule_exists else "create"
    print(f"{slot.slot}: {action} rule {slot.rule_name!r} for test {test_id}")
    if rule_id:
        print(f"  existing ruleId: {rule_id}")
    print(json.dumps(rule_payload_data, indent=2, sort_keys=True))
    print(f"  test assignment payload: {json.dumps(test_update_payload(rule_id or '<pending>'))}")


def sync_alert_rules(apply_changes: bool) -> None:
    token = require_value("THOUSANDEYES_BEARER_TOKEN")
    account_group_id = require_value("THOUSANDEYES_ACCOUNT_GROUP_ID")
    api = JsonApi(THOUSANDEYES_API_BASE_URL, token)
    notifications = alert_notifications()
    default_rules = default_rule_map(api, account_group_id)
    existing_rules = custom_rules_by_name(api, account_group_id)

    for slot in SLOT_DEFINITIONS:
        test_id = resolve_test_id(slot, api, account_group_id)
        template_key = (slot.alert_type, slot.default_rule_name)
        template_rule = default_rules.get(template_key)
        if template_rule is None:
            raise SystemExit(
                f"Unable to find default ThousandEyes rule {slot.default_rule_name!r} "
                f"for alert type {slot.alert_type!r}."
            )

        payload = rule_payload(slot, test_id, template_rule, notifications)
        existing = existing_rules.get(slot.rule_name)
        rule_id: str | None = None
        if existing is not None:
            rule_id = str(existing["ruleId"])

        if not apply_changes:
            print_plan(slot, test_id, payload, existing is not None, rule_id)
            continue

        if existing is None:
            response = api.request("POST", "/alerts/rules", query={"aid": account_group_id}, body=payload)
            rule_id = str(response.get("alertRuleId") or response.get("ruleId") or "")
            if not rule_id:
                raise SystemExit(f"Create response for {slot.rule_name!r} did not include a rule ID.")
            print(f"Created rule {slot.rule_name!r} ({rule_id})")
        else:
            response = api.request("PUT", f"/alerts/rules/{rule_id}", query={"aid": account_group_id}, body=payload)
            rule_id = str(response.get("ruleId") or rule_id)
            print(f"Updated rule {slot.rule_name!r} ({rule_id})")

        test_response = api.request(
            "PUT",
            f"/tests/{slot.test_path}/{test_id}",
            query={"aid": account_group_id},
            body=test_update_payload(rule_id),
        )
        assigned = test_response.get("alertRules", [])
        assigned_ids = [str(item.get("ruleId", item)) for item in assigned]
        print(f"Assigned rule {rule_id} to test {test_id} ({slot.test_path}); alertRules={assigned_ids or [rule_id]}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action",
        choices=("plan", "apply"),
        nargs="?",
        default="plan",
        help="Show the intended alert-rule changes or apply them live.",
    )
    parser.add_argument(
        "--env-file",
        default=os.environ.get("ENV_FILE", str(DEFAULT_ENV_FILE)),
        help="Path to an env file. Defaults to repo-root .env.",
    )
    args = parser.parse_args()

    load_env_file(Path(args.env_file))
    sync_alert_rules(apply_changes=args.action == "apply")


if __name__ == "__main__":
    main()
