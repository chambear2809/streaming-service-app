#!/usr/bin/env python3
"""Verify the Isovalent-related Splunk dashboard groups used by the demo.

The script reads the repo-root .env by default. Exported shell variables win.
It exits non-zero if any expected dashboard group or dashboard name is missing.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.parse
from pathlib import Path
from typing import Any


DOCS_DIR = Path(__file__).resolve().parent
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
DEFAULT_CATALOG = DOCS_DIR / "expected_dashboards.json"


def load_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def env_value(env_file: dict[str, str], name: str) -> str | None:
    value = os.environ.get(name)
    if value:
        return value
    value = env_file.get(name)
    if value:
        return value
    return None


def require_value(env_file: dict[str, str], name: str) -> str:
    value = env_value(env_file, name)
    if value is None:
        raise SystemExit(
            f"Missing required setting {name}. Set it in the repo-root .env or export it in your shell."
        )
    return value


class JsonApi:
    def __init__(self, base_url: str, headers: dict[str, str]) -> None:
        self.base_url = base_url.rstrip("/")
        self.headers = headers

    def request(self, path: str, query: dict[str, Any] | None = None) -> dict[str, Any]:
        url = self._build_url(path, query)
        try:
            result = subprocess.run(
                [
                    "curl",
                    "-sSf",
                    *sum((["-H", f"{name}: {value}"] for name, value in self.headers.items()), []),
                    url,
                ],
                check=True,
                capture_output=True,
                text=True,
            )
        except subprocess.CalledProcessError as error:
            stderr = error.stderr.strip()
            stdout = error.stdout.strip()
            details = stderr or stdout or f"curl exited with status {error.returncode}"
            raise RuntimeError(f"GET {url} failed: {details}") from error
        return json.loads(result.stdout)

    def _build_url(self, path: str, query: dict[str, Any] | None) -> str:
        url = f"{self.base_url}/{path.lstrip('/')}"
        if not query:
            return url
        encoded = urllib.parse.urlencode(query)
        return f"{url}?{encoded}"


def load_catalog(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def list_dashboard_groups(api: JsonApi) -> list[dict[str, Any]]:
    groups: list[dict[str, Any]] = []
    limit = 200
    offset = 0
    while True:
        response = api.request("/dashboardgroup", query={"limit": limit, "offset": offset})
        results = response.get("results", [])
        groups.extend(results)
        if len(results) < limit:
            break
        offset += limit
    return groups


def dashboard_ids_for_group(group: dict[str, Any]) -> list[str]:
    ids = group.get("dashboards") or group.get("dashboardIds")
    if ids:
        return [str(item) for item in ids]
    configs = group.get("dashboardConfigs", [])
    return [str(item["dashboardId"]) for item in configs if item.get("dashboardId")]


def fetch_dashboard_map(api: JsonApi, dashboard_ids: list[str]) -> dict[str, str]:
    dashboards: dict[str, str] = {}
    for dashboard_id in dashboard_ids:
        payload = api.request(f"/dashboard/{dashboard_id}")
        name = str(payload.get("name", dashboard_id))
        dashboards[name] = dashboard_id
    return dashboards


def build_report(api: JsonApi, catalog: dict[str, Any]) -> dict[str, Any]:
    live_groups = list_dashboard_groups(api)
    live_by_name = {str(group.get("name", "")): group for group in live_groups}

    groups_report: list[dict[str, Any]] = []
    ok = True

    for expected_group in catalog.get("groups", []):
        expected_name = str(expected_group["name"])
        live_group = live_by_name.get(expected_name)
        if live_group is None:
            groups_report.append(
                {
                    "name": expected_name,
                    "status": "missing_group",
                    "group_id": None,
                    "dashboards": [],
                    "missing_dashboards": list(expected_group.get("dashboards", [])),
                }
            )
            ok = False
            continue

        dashboard_map = fetch_dashboard_map(api, dashboard_ids_for_group(live_group))
        expected_dashboards = [str(item) for item in expected_group.get("dashboards", [])]
        missing_dashboards = [name for name in expected_dashboards if name not in dashboard_map]
        if missing_dashboards:
            ok = False

        groups_report.append(
            {
                "name": expected_name,
                "status": "ok" if not missing_dashboards else "missing_dashboards",
                "group_id": str(live_group.get("id", "")),
                "dashboards": [
                    {"name": name, "dashboard_id": dashboard_map[name]}
                    for name in expected_dashboards
                    if name in dashboard_map
                ],
                "missing_dashboards": missing_dashboards,
            }
        )

    return {"ok": ok, "groups": groups_report}


def print_text_report(report: dict[str, Any]) -> None:
    for group in report["groups"]:
        prefix = "[ok]" if group["status"] == "ok" else "[warn]"
        group_id = group["group_id"] or "missing"
        print(f"{prefix} {group['name']} ({group_id})")
        for dashboard in group["dashboards"]:
            print(f"  - {dashboard['name']} ({dashboard['dashboard_id']})")
        for dashboard_name in group["missing_dashboards"]:
            print(f"  - missing: {dashboard_name}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=DEFAULT_CATALOG,
        help="Path to the expected dashboard catalog JSON.",
    )
    parser.add_argument(
        "--env-file",
        type=Path,
        default=DEFAULT_ENV_FILE,
        help="Path to the env file. Exported shell variables still win.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print the report as JSON instead of plain text.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    env_file = load_env_file(args.env_file)
    realm = require_value(env_file, "SPLUNK_REALM")
    token = require_value(env_file, "SPLUNK_ACCESS_TOKEN")
    catalog = load_catalog(args.catalog)

    api = JsonApi(
        base_url=f"https://api.{realm}.signalfx.com/v2",
        headers={"X-SF-Token": token},
    )
    try:
        report = build_report(api, catalog)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_text_report(report)

    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
