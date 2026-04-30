#!/usr/bin/env python3
"""Reconcile the ThousandEyes Integrations 2.0 Splunk APM connector.

The ThousandEyes -> Splunk APM trace-link integration is an Integrations 2.0
operation in ThousandEyes. The public API can manage the generic connector and
its assignment to an existing Splunk Observability APM operation. It does not
currently expose operation creation for that operation type, so create the
operation once in the UI if it is missing.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
DEFAULT_API_BASE_URL = "https://api.thousandeyes.com/v7"
APM_OPERATION_TYPE = "splunk-observability-apm"


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
        payload: Optional[Any] = None,
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
            print(json.dumps(redacted(payload), indent=2), file=sys.stderr)
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
        pairs = [(key, str(value)) for key, value in query.items() if value is not None]
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


def normalize_url(url: str) -> str:
    return url.rstrip("/")


def splunk_api_url(env_file: Dict[str, str]) -> str:
    explicit = env_value(env_file, "THOUSANDEYES_APM_TARGET_URL")
    if explicit:
        return normalize_url(explicit)
    secondary_api = env_value(env_file, "SPLUNK_OTEL_SECONDARY_API_URL")
    if secondary_api:
        return normalize_url(secondary_api)
    secondary_realm = env_value(env_file, "SPLUNK_OTEL_SECONDARY_REALM")
    if secondary_realm:
        return f"https://api.{secondary_realm}.signalfx.com"
    realm = require_value(env_file, "SPLUNK_REALM")
    return f"https://api.{realm}.signalfx.com"


def splunk_api_token(env_file: Dict[str, str], target_url: Optional[str] = None) -> str:
    explicit = env_value(env_file, "THOUSANDEYES_APM_API_TOKEN")
    if explicit is not None:
        return explicit

    normalized_target = normalize_url(target_url or "")
    primary_realm = env_value(env_file, "SPLUNK_REALM")
    primary_target = f"https://api.{primary_realm}.signalfx.com" if primary_realm else None
    secondary_target = env_value(env_file, "SPLUNK_OTEL_SECONDARY_API_URL")
    secondary_realm = env_value(env_file, "SPLUNK_OTEL_SECONDARY_REALM")
    if secondary_target is None and secondary_realm:
        secondary_target = f"https://api.{secondary_realm}.signalfx.com"

    primary_token = env_value(env_file, "SPLUNK_ACCESS_TOKEN")
    secondary_token = env_value(env_file, "SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN")

    if primary_target and normalized_target == normalize_url(primary_target) and primary_token is not None:
        return primary_token
    if secondary_target and normalized_target == normalize_url(secondary_target) and secondary_token is not None:
        return secondary_token

    token = secondary_token or primary_token
    if token is None:
        raise SystemExit(
            "Missing required setting THOUSANDEYES_APM_API_TOKEN, "
            "SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN, or SPLUNK_ACCESS_TOKEN."
        )
    return token


def connector_payload(name: str, target_url: str, token: str) -> Dict[str, Any]:
    return {
        "type": "generic",
        "name": name,
        "target": normalize_url(target_url),
        "headers": [{"name": "X-SF-Token", "value": token}],
    }


def redacted(value: Any) -> Any:
    if isinstance(value, dict):
        result: Dict[str, Any] = {}
        for key, item in value.items():
            if key.lower() in {"token", "authorization"}:
                result[key] = "<redacted>"
            elif key == "headers" and isinstance(item, list):
                result[key] = [
                    {**header, "value": "<redacted>"}
                    if isinstance(header, dict) and str(header.get("name", "")).lower() in {"x-sf-token", "authorization"}
                    else redacted(header)
                    for header in item
                ]
            else:
                result[key] = redacted(item)
        return result
    if isinstance(value, list):
        return [redacted(item) for item in value]
    return value


def list_integrations(api: JsonApi, account_group_id: str) -> Dict[str, Any]:
    response = api.request("GET", "/integrations", query={"aid": account_group_id})
    if not isinstance(response, dict):
        raise SystemExit(f"Unexpected integrations response: {response!r}")
    return response


def list_connectors(api: JsonApi, account_group_id: str) -> List[Dict[str, Any]]:
    response = api.request("GET", "/connectors/generic", query={"aid": account_group_id})
    if isinstance(response, dict):
        return response.get("items", [])
    if isinstance(response, list):
        return response
    raise SystemExit(f"Unexpected connectors response: {response!r}")


def operation_connector_ids(api: JsonApi, account_group_id: str, operation_id: str) -> List[str]:
    response = api.request(
        "GET",
        f"/operations/{APM_OPERATION_TYPE}/{urllib.parse.quote(operation_id)}/connectors",
        query={"aid": account_group_id},
    )
    return [str(item) for item in response.get("items", [])]


def choose_apm_operation(
    integrations_response: Dict[str, Any],
    *,
    operation_id: Optional[str],
    operation_name: Optional[str],
) -> Dict[str, Any]:
    operations = [
        operation
        for operation in integrations_response.get("operations", [])
        if operation.get("type") == APM_OPERATION_TYPE
    ]
    if operation_id:
        matches = [operation for operation in operations if str(operation.get("id")) == operation_id]
        if len(matches) != 1:
            raise SystemExit(f"No ThousandEyes {APM_OPERATION_TYPE} operation with id {operation_id!r} was found.")
        return matches[0]
    if operation_name:
        matches = [operation for operation in operations if operation.get("name") == operation_name]
        if len(matches) != 1:
            names = ", ".join(str(operation.get("name", "<unnamed>")) for operation in operations)
            raise SystemExit(
                f"Expected exactly one ThousandEyes {APM_OPERATION_TYPE} operation named "
                f"{operation_name!r}; found {len(matches)}. Available operations: {names}"
            )
        return matches[0]
    if len(operations) == 1:
        return operations[0]
    if not operations:
        raise SystemExit(
            "No ThousandEyes Splunk Observability APM operation was found. "
            "Create it once in ThousandEyes under Manage > Integrations > Integrations 2.0, "
            "then rerun this helper."
        )
    names = ", ".join(f"{operation.get('name', '<unnamed>')} ({operation.get('id')})" for operation in operations)
    raise SystemExit(
        "Multiple ThousandEyes Splunk Observability APM operations were found. "
        f"Set THOUSANDEYES_APM_OPERATION_ID or THOUSANDEYES_APM_OPERATION_NAME. Available operations: {names}"
    )


def choose_connector(
    connectors: Sequence[Dict[str, Any]],
    *,
    connector_id: Optional[str],
    connector_name: str,
    target_url: str,
) -> Optional[Dict[str, Any]]:
    if connector_id:
        matches = [connector for connector in connectors if str(connector.get("id")) == connector_id]
        if len(matches) != 1:
            raise SystemExit(f"No ThousandEyes generic connector with id {connector_id!r} was found.")
        return matches[0]
    same_name_and_target = [
        connector
        for connector in connectors
        if connector.get("name") == connector_name and normalize_url(str(connector.get("target", ""))) == target_url
    ]
    if len(same_name_and_target) == 1:
        return same_name_and_target[0]
    if len(same_name_and_target) > 1:
        ids = ", ".join(str(connector.get("id", "<missing>")) for connector in same_name_and_target)
        raise SystemExit(f"Multiple matching connectors named {connector_name!r} target {target_url!r}: {ids}")
    same_target = [
        connector
        for connector in connectors
        if normalize_url(str(connector.get("target", ""))) == target_url
    ]
    if len(same_target) == 1:
        return same_target[0]
    if len(same_target) > 1:
        ids = ", ".join(str(connector.get("id", "<missing>")) for connector in same_target)
        raise SystemExit(f"Multiple connectors target {target_url!r}; set THOUSANDEYES_APM_CONNECTOR_ID: {ids}")
    return None


def assigned_connector(
    connectors: Sequence[Dict[str, Any]],
    assigned_connector_ids: Sequence[str],
) -> Optional[Dict[str, Any]]:
    assigned_id_set = {str(connector_id) for connector_id in assigned_connector_ids}
    if not assigned_id_set:
        return None
    assigned = [connector for connector in connectors if str(connector.get("id")) in assigned_id_set]
    if len(assigned) == 1:
        return assigned[0]
    if len(assigned) > 1:
        ids = ", ".join(str(connector.get("id", "<missing>")) for connector in assigned)
        raise SystemExit(f"Multiple assigned connectors were found for the operation: {ids}")
    missing = ", ".join(sorted(assigned_id_set))
    raise SystemExit(f"Operation assignment references connector(s) that were not returned by ThousandEyes: {missing}")


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create or update the ThousandEyes Integrations 2.0 connector for Splunk Observability APM."
    )
    parser.add_argument(
        "--env-file",
        default=str(DEFAULT_ENV_FILE),
        help="Path to the repo env file. Defaults to the repo-root .env.",
    )
    parser.add_argument(
        "--operation-id",
        help="Exact Splunk Observability APM operation ID. Overrides THOUSANDEYES_APM_OPERATION_ID.",
    )
    parser.add_argument(
        "--operation-name",
        help="Splunk Observability APM operation name. Overrides THOUSANDEYES_APM_OPERATION_NAME.",
    )
    parser.add_argument(
        "--connector-name",
        help="Generic connector name. Defaults to THOUSANDEYES_APM_CONNECTOR_NAME, then the operation name.",
    )
    parser.add_argument(
        "--connector-id",
        help="Exact generic connector ID to update. Overrides THOUSANDEYES_APM_CONNECTOR_ID.",
    )
    parser.add_argument(
        "--target-url",
        help="Splunk API target URL. Defaults to THOUSANDEYES_APM_TARGET_URL, SPLUNK_OTEL_SECONDARY_API_URL, or realm-derived URL.",
    )
    assignment_group = parser.add_mutually_exclusive_group()
    assignment_group.add_argument(
        "--assign-operation",
        action="store_true",
        help="Assign the connector to a Splunk Observability APM operation.",
    )
    assignment_group.add_argument(
        "--skip-operation-assignment",
        action="store_true",
        help="Create or update the connector only. Use this when staging a second O11y instance connector.",
    )
    parser.add_argument(
        "--replace-operation-connector",
        action="store_true",
        help="Allow this run to replace an operation's existing connector assignment.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print intended writes without changing ThousandEyes.",
    )
    parser.add_argument(
        "--print-json",
        action="store_true",
        help="Print the final connector JSON response to stdout.",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    env_file = load_env_file(Path(args.env_file))

    te_token = require_value(env_file, "THOUSANDEYES_BEARER_TOKEN")
    account_group_id = require_value(env_file, "THOUSANDEYES_ACCOUNT_GROUP_ID")
    target_url = normalize_url(args.target_url or splunk_api_url(env_file))
    token = splunk_api_token(env_file, target_url)
    operation_id = args.operation_id or env_value(env_file, "THOUSANDEYES_APM_OPERATION_ID")
    operation_name = args.operation_name or env_value(env_file, "THOUSANDEYES_APM_OPERATION_NAME")
    connector_id = args.connector_id or env_value(env_file, "THOUSANDEYES_APM_CONNECTOR_ID")
    api_base_url = env_value(env_file, "THOUSANDEYES_API_BASE_URL", DEFAULT_API_BASE_URL) or DEFAULT_API_BASE_URL
    assign_operation = bool_value(env_value(env_file, "THOUSANDEYES_APM_ASSIGN_OPERATION"), default=True)
    if args.assign_operation:
        assign_operation = True
    if args.skip_operation_assignment:
        assign_operation = False

    api = JsonApi(api_base_url, te_token, dry_run=args.dry_run)
    operation: Optional[Dict[str, Any]] = None
    assigned_ids: List[str] = []
    if assign_operation:
        integrations = list_integrations(api, account_group_id)
        operation = choose_apm_operation(
            integrations,
            operation_id=operation_id,
            operation_name=operation_name,
        )
        if operation.get("enabled") is False:
            print(
                f"Warning: operation {operation.get('name')} ({operation.get('id')}) is disabled. "
                "Enable it in the ThousandEyes Integrations 2.0 UI.",
                file=sys.stderr,
            )
        assigned_ids = operation_connector_ids(api, account_group_id, str(operation["id"]))

    connector_name = (
        args.connector_name
        or env_value(env_file, "THOUSANDEYES_APM_CONNECTOR_NAME")
        or str(operation.get("name") if operation else "Splunk Observability APM")
    )
    connectors = list_connectors(api, account_group_id)
    connector = choose_connector(
        connectors,
        connector_id=connector_id,
        connector_name=connector_name,
        target_url=target_url,
    )
    current_assigned = assigned_connector(connectors, assigned_ids) if assign_operation else None
    if current_assigned is not None:
        assigned_target = normalize_url(str(current_assigned.get("target", "")))
        if assigned_target == target_url:
            if connector is None:
                connector = current_assigned
        elif not args.replace_operation_connector:
            raise SystemExit(
                f"Operation {operation.get('name')} ({operation.get('id')}) is already assigned to connector "
                f"{current_assigned.get('name')} ({current_assigned.get('id')}) targeting {assigned_target}. "
                "Refusing to replace that assignment implicitly. Use --skip-operation-assignment to create or "
                "update a separate connector, or pass --replace-operation-connector to move this operation."
            )
    payload = connector_payload(connector_name, target_url, token)

    if connector is None:
        response = api.request("POST", "/connectors/generic", payload=payload, query={"aid": account_group_id})
        connector_id = str(response.get("id", "<missing>"))
        action = "created"
    else:
        connector_id = str(connector["id"])
        response = api.request(
            "PUT",
            f"/connectors/generic/{urllib.parse.quote(connector_id)}",
            payload=payload,
            query={"aid": account_group_id},
        )
        action = "updated"

    if assign_operation:
        assert operation is not None
        api.request(
            "PUT",
            f"/operations/{APM_OPERATION_TYPE}/{urllib.parse.quote(str(operation['id']))}/connectors",
            payload=[connector_id],
            query={"aid": account_group_id},
        )

    print(f"Connector {connector_id} {action}.")
    if assign_operation:
        assert operation is not None
        print(f"Operation: {operation.get('name')} ({operation.get('id')})")
        print(f"Operation enabled: {operation.get('enabled')}")
    else:
        print("Operation assignment: skipped")
    print(f"Target: {target_url}")
    if args.print_json:
        print(json.dumps(redacted(response), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
