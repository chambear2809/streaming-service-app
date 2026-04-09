#!/usr/bin/env python3
"""Create or update the streaming-service-app Splunk demo dashboards.

This script uses the live ThousandEyes tests plus Splunk Observability
metrics to keep a numbered dashboard group that is easy to follow during the
demo:

01 Start Here: Network Symptoms
02 Pivot: User Impact To Root Cause
03 Backend Critical Path
04 Deep Dive: Trace Map Path
05 Deep Dive: Broadcast Playback Path
06 Deep Dive: RTP Media Quality
07 Explain: Are Media Services Still Talking?

The script reads the repo-root .env by default. Exported shell variables win.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
SIGNALFLOW_VALIDATOR = Path(__file__).resolve().with_name("validate-signalflow-metrics.js")
DEFAULT_GROUP_NAME = f"Streaming Service App ThousandEyes Tests {date.today().isoformat()}"
GROUP_NAME_PREFIX = "Streaming Service App ThousandEyes Tests"
DEFAULT_NAMESPACE = "streaming-service-app"
DEFAULT_RUM_APP = "streaming-app-frontend"
DEFAULT_APM_ENVIRONMENT = "streaming-app"
DEFAULT_BROADCAST_PAGE_FILTER = "*broadcast*"
MEDIA_PATH_SOURCE_WORKLOADS = ("streaming-frontend", "media-service-demo")
MEDIA_PATH_DEST_WORKLOADS = ("media-service-demo", "ad-service-demo")

TEST_TYPE_ENDPOINTS = (
    "agent-to-server",
    "agent-to-agent",
    "voice",
    "http-server",
)
NETWORK_METRICS = ("network.latency", "network.loss", "network.jitter")
RTP_METRICS = (
    "rtp.client.request.mos",
    "rtp.client.request.loss",
    "rtp.client.request.discards",
    "rtp.client.request.duration",
    "rtp.client.request.pdv",
)
PREFERRED_NETWORK_SLOTS = ("trace_map", "broadcast_playback")
LEGACY_NETWORK_SLOTS = ("rtsp", "udp")
NETWORK_SLOTS = PREFERRED_NETWORK_SLOTS + LEGACY_NETWORK_SLOTS
SIGNALFLOW_TIMEOUT_TEXT = "Timed out waiting for SignalFlow data"
SIGNALFLOW_TIMEOUT_RETRIES = 2
SIGNALFLOW_TIMEOUT_RETRY_DELAY_SECONDS = 2

TEST_LABELS = {
    "rtsp": "RTSP",
    "udp": "UDP media",
    "rtp": "RTP",
    "trace_map": "Trace map",
    "broadcast_playback": "Broadcast playback",
}

TEST_SLOT_CONFIG = {
    "rtsp": {
        "default_names": ("RTSP-TCP-8554", "RTSP-TCP-554"),
        "name_env": "TE_RTSP_TCP_TEST_NAME",
        "id_env": "TE_RTSP_TCP_TEST_ID",
    },
    "udp": {
        "default_names": ("UDP-Media-Path",),
        "name_env": "TE_UDP_MEDIA_TEST_NAME",
        "id_env": "TE_UDP_MEDIA_TEST_ID",
    },
    "rtp": {
        "default_names": ("RTP-Stream-Proxy",),
        "name_env": "TE_RTP_STREAM_TEST_NAME",
        "id_env": "TE_RTP_STREAM_TEST_ID",
    },
    "trace_map": {
        "default_names": ("aleccham-broadcast-trace-map",),
        "name_env": "TE_TRACE_MAP_TEST_NAME",
        "id_env": "TE_TRACE_MAP_TEST_ID",
    },
    "broadcast_playback": {
        "default_names": ("aleccham-broadcast-playback",),
        "name_env": "TE_BROADCAST_TEST_NAME",
        "id_env": "TE_BROADCAST_TEST_ID",
    },
}


@dataclass(frozen=True)
class ChartSpec:
    name: str
    description: str
    program_text: str
    publish_label: str
    options: Optional[Dict[str, Any]] = None


@dataclass(frozen=True)
class DashboardSpec:
    key: str
    aliases: tuple[str, ...]
    name: str
    description: str
    charts: tuple[ChartSpec, ...]


@dataclass(frozen=True)
class MetricValidationSpec:
    metric: str
    test_name: str
    test_id: str
    program_text: Optional[str] = None


def load_env_file(path: Path) -> Dict[str, str]:
    values: Dict[str, str] = {}
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


class JsonApi:
    def __init__(self, base_url: str, headers: Dict[str, str], dry_run: bool = False) -> None:
        self.base_url = base_url.rstrip("/")
        self.headers = headers
        self.dry_run = dry_run

    def request(
        self,
        method: str,
        path: str,
        payload: Optional[Dict[str, Any]] = None,
        query: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        url = self._build_url(path, query)
        body = None
        headers = dict(self.headers)
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self.dry_run and method.upper() != "GET":
            print(f"[dry-run] {method.upper()} {url}", file=sys.stderr)
            if payload is not None:
                print(json.dumps(payload, indent=2), file=sys.stderr)
            return payload or {}
        request = urllib.request.Request(url, data=body, headers=headers, method=method.upper())
        try:
            with urllib.request.urlopen(request) as response:
                response_body = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            response_body = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method.upper()} {url} failed: {error.code} {response_body}") from error
        if not response_body:
            return {}
        return json.loads(response_body)

    def _build_url(self, path: str, query: Optional[Dict[str, Any]]) -> str:
        url = f"{self.base_url}{path}"
        if not query:
            return url
        query_pairs: List[tuple[str, str]] = []
        for key, value in query.items():
            if value is None:
                continue
            query_pairs.append((key, str(value)))
        if not query_pairs:
            return url
        return f"{url}?{urllib.parse.urlencode(query_pairs)}"


def te_test_indexes(
    te_api: JsonApi,
    account_group_id: str,
) -> tuple[Dict[str, List[Dict[str, Any]]], Dict[str, Dict[str, Any]]]:
    tests_by_name: Dict[str, List[Dict[str, Any]]] = {}
    tests_by_id: Dict[str, Dict[str, Any]] = {}
    for endpoint in TEST_TYPE_ENDPOINTS:
        response = te_api.request("GET", f"/tests/{endpoint}", query={"aid": account_group_id})
        for test in response.get("tests", []):
            name = test.get("testName")
            if name:
                tests_by_name.setdefault(name, []).append(test)
            test_id = str(test.get("testId", "")).strip()
            if test_id:
                tests_by_id[test_id] = test
    return tests_by_name, tests_by_id


def te_metric_streams(
    te_api: JsonApi,
    account_group_id: str,
) -> List[Dict[str, Any]]:
    response = te_api.request("GET", "/stream", query={"aid": account_group_id})
    streams = response if isinstance(response, list) else response.get("streams", [])
    return [
        stream
        for stream in streams
        if stream.get("enabled") is True
        and stream.get("type") == "opentelemetry"
        and stream.get("signal") == "metric"
    ]


def stream_matches_test(stream: Dict[str, Any], test: Dict[str, Any]) -> bool:
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


def warn_if_missing_rtp_metric_stream(
    te_api: JsonApi,
    account_group_id: str,
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
) -> None:
    rtp_name, rtp_test = resolved_tests.get("rtp", (None, None))
    if not rtp_name or not rtp_test:
        return

    streams = te_metric_streams(te_api, account_group_id)
    if any(stream_matches_test(stream, rtp_test) for stream in streams):
        return

    rtp_id = str(rtp_test.get("testId", "<missing>"))
    print(
        "Warning: no enabled ThousandEyes OTel metric stream appears to cover "
        f"{rtp_name} ({rtp_id}). The RTP dashboard will stay empty until a stream "
        "includes that test via testMatch or exports the voice test type.",
        file=sys.stderr,
    )


def configured_test_slots(env_file: Dict[str, str]) -> Dict[str, tuple[str, ...]]:
    slots: Dict[str, tuple[str, ...]] = {}
    for slot, config in TEST_SLOT_CONFIG.items():
        configured_name = env_value(env_file, config["name_env"])
        if configured_name:
            slots[slot] = (configured_name,)
        else:
            slots[slot] = tuple(config["default_names"])
    return slots


def configured_test_id_overrides(env_file: Dict[str, str]) -> Dict[str, str]:
    overrides: Dict[str, str] = {}
    for slot, config in TEST_SLOT_CONFIG.items():
        configured_id = env_value(env_file, config["id_env"])
        if configured_id:
            overrides[slot] = configured_id.strip()
    return overrides


def duplicate_test_error(slot: str, test_name: str, candidates: List[Dict[str, Any]]) -> SystemExit:
    id_env = TEST_SLOT_CONFIG[slot]["id_env"]
    ids = ", ".join(str(candidate.get("testId", "<missing>")) for candidate in candidates)
    return SystemExit(
        f"Multiple ThousandEyes tests named {test_name!r} were found ({ids}). "
        f"Set {id_env} to the exact test ID the dashboard should use, or delete the duplicate tests."
    )


def resolve_test(
    tests_by_name: Dict[str, List[Dict[str, Any]]],
    tests_by_id: Dict[str, Dict[str, Any]],
    slot: str,
    slot_names: tuple[str, ...],
    configured_test_id: Optional[str],
) -> tuple[Optional[str], Optional[Dict[str, Any]]]:
    if configured_test_id:
        matched = tests_by_id.get(configured_test_id)
        if matched is None:
            raise SystemExit(
                f"{TEST_SLOT_CONFIG[slot]['id_env']}={configured_test_id!r} did not match any "
                "ThousandEyes test in the selected account group."
            )
        return str(matched.get("testName", configured_test_id)), matched

    for test_name in slot_names:
        candidates = tests_by_name.get(test_name, [])
        if not candidates:
            continue
        if len(candidates) > 1:
            raise duplicate_test_error(slot, test_name, candidates)
        return test_name, candidates[0]
    return None, None


def resolve_tests(
    tests_by_name: Dict[str, List[Dict[str, Any]]],
    tests_by_id: Dict[str, Dict[str, Any]],
    configured_slots: Dict[str, tuple[str, ...]],
    configured_test_ids: Dict[str, str],
) -> Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]]:
    resolved: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]] = {}
    for slot, slot_names in configured_slots.items():
        resolved[slot] = resolve_test(
            tests_by_name,
            tests_by_id,
            slot,
            slot_names,
            configured_test_ids.get(slot),
        )
    return resolved


def list_dashboard_groups(splunk_api: JsonApi) -> List[Dict[str, Any]]:
    response = splunk_api.request("GET", "/dashboardgroup", query={"limit": 200, "offset": 0})
    return response.get("results", [])


def choose_group(
    splunk_api: JsonApi,
    group_id: Optional[str],
    group_name: Optional[str],
) -> Dict[str, Any]:
    if group_id:
        return splunk_api.request("GET", f"/dashboardgroup/{group_id}")

    groups = list_dashboard_groups(splunk_api)
    if group_name:
        exact = [group for group in groups if group.get("name") == group_name]
        if len(exact) == 1:
            return splunk_api.request("GET", f"/dashboardgroup/{exact[0]['id']}")

    prefix_matches = [group for group in groups if str(group.get("name", "")).startswith(GROUP_NAME_PREFIX)]
    if len(prefix_matches) == 1:
        return splunk_api.request("GET", f"/dashboardgroup/{prefix_matches[0]['id']}")
    if len(prefix_matches) > 1:
        raise SystemExit(
            "Multiple matching dashboard groups found. Set SPLUNK_DEMO_DASHBOARD_GROUP_ID "
            "or pass --group-id so the script knows which group to update."
        )

    name = group_name or DEFAULT_GROUP_NAME
    created = splunk_api.request(
        "POST",
        "/dashboardgroup",
        payload={
            "name": name,
            "description": "Numbered demo dashboards for the streaming-service-app observability story.",
            "dashboards": [],
            "dashboardConfigs": [],
        },
    )
    return created


def load_dashboard_map(splunk_api: JsonApi, group: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    dashboards: Dict[str, Dict[str, Any]] = {}
    for dashboard_id in group.get("dashboards", []):
        dashboard = splunk_api.request("GET", f"/dashboard/{dashboard_id}")
        dashboards[dashboard_id] = dashboard
    return dashboards


def find_dashboard(
    dashboards: Dict[str, Dict[str, Any]],
    aliases: Iterable[str],
) -> Optional[Dict[str, Any]]:
    alias_set = set(aliases)
    for dashboard in dashboards.values():
        if dashboard.get("name") in alias_set:
            return dashboard
    return None


def load_chart_map(splunk_api: JsonApi, dashboard: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    charts: Dict[str, Dict[str, Any]] = {}
    for item in dashboard.get("charts", []):
        chart = splunk_api.request("GET", f"/chart/{item['chartId']}")
        charts[chart["name"]] = chart
    return charts


def chart_options(publish_label: str) -> Dict[str, Any]:
    return {
        "areaChartOptions": {"showDataMarkers": False},
        "axes": [
            {
                "highWatermark": None,
                "highWatermarkLabel": None,
                "label": "",
                "lowWatermark": None,
                "lowWatermarkLabel": None,
                "max": None,
                "min": None,
            },
            {
                "highWatermark": None,
                "highWatermarkLabel": None,
                "label": "",
                "lowWatermark": None,
                "lowWatermarkLabel": None,
                "max": None,
                "min": None,
            },
        ],
        "axisPrecision": None,
        "colorBy": "Dimension",
        "defaultPlotType": "LineChart",
        "eventPublishLabelOptions": [],
        "histogramChartOptions": {"colorThemeIndex": 16},
        "includeZero": False,
        "legendOptions": {"fields": None},
        "lineChartOptions": {"showDataMarkers": False},
        "noDataOptions": {"linkText": None, "linkUrl": None, "noDataMessage": None},
        "onChartLegendOptions": {"dimensionInLegend": None, "showLegend": False},
        "programOptions": {
            "disableSampling": False,
            "maxDelay": 0,
            "minimumResolution": 0,
            "timezone": None,
        },
        "publishLabelOptions": [
            {
                "displayName": publish_label,
                "label": publish_label,
                "paletteIndex": None,
                "plotType": None,
                "valuePrefix": None,
                "valueSuffix": None,
                "valueUnit": None,
                "yAxis": 0,
            }
        ],
        "showEventLines": False,
        "stacked": False,
        "time": {"range": 86400000, "rangeEnd": 0, "type": "relative"},
        "type": "TimeSeriesChart",
        "unitPrefix": "Metric",
    }


def column_chart_options(publish_label: str, value_suffix: Optional[str] = None) -> Dict[str, Any]:
    options = chart_options(publish_label)
    options["defaultPlotType"] = "ColumnChart"
    if value_suffix is not None:
        options["publishLabelOptions"][0]["valueSuffix"] = value_suffix
    return options


def line_chart_options(publish_label: str, value_suffix: Optional[str] = None) -> Dict[str, Any]:
    options = chart_options(publish_label)
    if value_suffix is not None:
        options["publishLabelOptions"][0]["valueSuffix"] = value_suffix
    return options


def table_chart_options(publish_labels: List[Dict[str, str]], sort_by: str) -> Dict[str, Any]:
    return {
        "columnSizes": {},
        "groupBy": ["workload"],
        "groupBySort": "Descending",
        "hideMissingValues": False,
        "legendOptions": {"fields": None},
        "maximumPrecision": None,
        "programOptions": {
            "disableSampling": True,
            "maxDelay": None,
            "minimumResolution": 0,
            "timezone": None,
        },
        "publishLabelOptions": publish_labels,
        "refreshInterval": None,
        "sortBy": sort_by,
        "time": {"range": 86400000, "rangeEnd": 0, "type": "relative"},
        "timestampHidden": False,
        "type": "TableChart",
        "unitPrefix": "Metric",
    }


def upsert_chart(
    splunk_api: JsonApi,
    existing: Optional[Dict[str, Any]],
    spec: ChartSpec,
) -> str:
    payload = {
        "name": spec.name,
        "description": spec.description,
        "programText": spec.program_text,
        "options": spec.options or chart_options(spec.publish_label),
    }
    if existing is None:
        created = splunk_api.request("POST", "/chart", payload=payload)
        return created.get("id", f"dry-run-chart-{spec.name}")

    updated = dict(existing)
    updated.update(payload)
    result = splunk_api.request("PUT", f"/chart/{existing['id']}", payload=updated)
    return result["id"]


def upsert_dashboard(
    splunk_api: JsonApi,
    group_id: str,
    existing: Optional[Dict[str, Any]],
    spec: DashboardSpec,
) -> str:
    chart_map = load_chart_map(splunk_api, existing) if existing else {}
    layouts: List[Dict[str, Any]] = []
    width = 6
    for index, chart_spec in enumerate(spec.charts):
        chart_id = upsert_chart(splunk_api, chart_map.get(chart_spec.name), chart_spec)
        layouts.append(
            {
                "chartId": chart_id,
                "column": 0 if index % 2 == 0 else width,
                "height": 1,
                "row": index // 2,
                "width": width,
            }
        )

    payload = {
        "name": spec.name,
        "description": spec.description,
        "charts": layouts,
        "filters": {"sources": [], "time": None, "variables": []},
    }
    if existing is None:
        create_payload = dict(payload)
        create_payload["groupId"] = group_id
        created = splunk_api.request("POST", "/dashboard", payload=create_payload)
        return created.get("id", f"dry-run-dashboard-{spec.key}")

    updated = dict(existing)
    updated.update(payload)
    result = splunk_api.request("PUT", f"/dashboard/{existing['id']}", payload=updated)
    return result["id"]


def update_group(
    splunk_api: JsonApi,
    group: Dict[str, Any],
    ordered_dashboard_ids: List[str],
    description: str,
) -> Dict[str, Any]:
    existing_configs = {config["dashboardId"]: config for config in group.get("dashboardConfigs", [])}
    final_dashboard_ids = list(ordered_dashboard_ids)
    for dashboard_id in group.get("dashboards", []):
        if dashboard_id in final_dashboard_ids:
            continue
        final_dashboard_ids.append(dashboard_id)

    configs: List[Dict[str, Any]] = []
    for dashboard_id in final_dashboard_ids:
        config = dict(existing_configs.get(dashboard_id, {}))
        config["dashboardId"] = dashboard_id
        config["nameOverride"] = None
        config["descriptionOverride"] = None
        config["filtersOverride"] = None
        configs.append(config)

    updated = dict(group)
    updated["description"] = description
    updated["dashboards"] = final_dashboard_ids
    updated["dashboardConfigs"] = configs
    return splunk_api.request("PUT", f"/dashboardgroup/{group['id']}", payload=updated)


def te_chart(
    title: str,
    description: str,
    metric: str,
    account_group_id: str,
    test_id: str,
    label: str,
) -> ChartSpec:
    return ChartSpec(
        name=title,
        description=description,
        program_text=(
            f"A = data('{metric}', filter=filter('thousandeyes.account.id', '{account_group_id}') "
            f"and filter('thousandeyes.test.id', '{test_id}')).publish(label='{label}')"
        ),
        publish_label=label,
    )


def matched_test(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
    slot: str,
) -> tuple[Optional[str], Optional[Dict[str, Any]]]:
    return resolved_tests.get(slot, (None, None))


def present_tests(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
    *slots: str,
) -> List[tuple[str, str, Dict[str, Any]]]:
    matched: List[tuple[str, str, Dict[str, Any]]] = []
    for slot in slots:
        name, test = matched_test(resolved_tests, slot)
        if name and test:
            matched.append((slot, name, test))
    return matched


def te_network_triplet(
    slot: str,
    test_name: str,
    account_group_id: str,
    test_id: str,
    suffix: str = "(24h)",
) -> List[ChartSpec]:
    label = TEST_LABELS[slot]
    return [
        te_chart(
            f"{label} latency {suffix}",
            f"network.latency for {test_name}.",
            "network.latency",
            account_group_id,
            test_id,
            test_name,
        ),
        te_chart(
            f"{label} network loss {suffix}",
            f"network.loss for {test_name}.",
            "network.loss",
            account_group_id,
            test_id,
            test_name,
        ),
        te_chart(
            f"{label} jitter {suffix}",
            f"network.jitter for {test_name}.",
            "network.jitter",
            account_group_id,
            test_id,
            test_name,
        ),
    ]


def signalflow_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def filter_any(dimension: str, values: Iterable[str]) -> str:
    clauses = [f"filter('{signalflow_value(dimension)}', '{signalflow_value(value)}')" for value in values]
    if not clauses:
        raise ValueError(f"No values were provided for dimension {dimension}.")
    if len(clauses) == 1:
        return clauses[0]
    return f"({' or '.join(clauses)})"


def filter_all(*clauses: str) -> str:
    wrapped: List[str] = []
    for clause in clauses:
        stripped = clause.strip()
        if not stripped:
            continue
        if stripped.startswith("(") and stripped.endswith(")"):
            wrapped.append(stripped)
        else:
            wrapped.append(f"({stripped})")
    return " and ".join(wrapped)


def signalflow_chart(
    title: str,
    description: str,
    program_text: str,
    publish_label: str,
    options: Optional[Dict[str, Any]] = None,
) -> ChartSpec:
    return ChartSpec(
        name=title,
        description=description,
        program_text=program_text,
        publish_label=publish_label,
        options=options,
    )


def media_path_hubble_filter(namespace: str) -> str:
    return filter_all(
        filter_any("source_namespace", (namespace,)),
        filter_any("destination_namespace", (namespace,)),
        filter_any("source_workload", MEDIA_PATH_SOURCE_WORKLOADS),
        filter_any("destination_workload", MEDIA_PATH_DEST_WORKLOADS),
    )


def media_path_tetragon_filter() -> str:
    return filter_all(
        filter_any("workload", MEDIA_PATH_SOURCE_WORKLOADS),
        filter_any("dstworkload", MEDIA_PATH_DEST_WORKLOADS),
    )


def media_path_tetragon_destination_filter() -> str:
    return filter_any("dstworkload", MEDIA_PATH_DEST_WORKLOADS)


def media_path_drop_filter(namespace: str) -> str:
    return filter_all(
        filter_any("destination_namespace", (namespace,)),
        filter_any("destination_workload", MEDIA_PATH_DEST_WORKLOADS),
        filter_any("source_workload", MEDIA_PATH_SOURCE_WORKLOADS),
    )


def service_filter(service_name: str, environment: str) -> str:
    return (
        f"filter('sf_environment', '{environment}') and "
        f"filter('sf_service', '{service_name}') and "
        "(not filter('sf_dimensionalized', '*'))"
    )


def weighted_service_latency_chart(title: str, description: str, service_name: str, environment: str) -> ChartSpec:
    filter_expr = service_filter(service_name, environment)
    return ChartSpec(
        name=title,
        description=description,
        program_text=(
            "def weighted_duration(base, p, filter_, groupby):\n"
            "    error_durations = data(base + '.duration.ns.' + p, filter=filter_ and filter('sf_error', 'true'), rollup='max').mean(by=groupby, allow_missing=['sf_httpMethod'])\n"
            "    non_error_durations = data(base + '.duration.ns.' + p, filter=filter_ and filter('sf_error', 'false'), rollup='max').mean(by=groupby, allow_missing=['sf_httpMethod'])\n"
            "    error_counts = data(base + '.count', filter=filter_ and filter('sf_error', 'true'), rollup='sum').sum(by=groupby, allow_missing=['sf_httpMethod'])\n"
            "    non_error_counts = data(base + '.count', filter=filter_ and filter('sf_error', 'false'), rollup='sum').sum(by=groupby, allow_missing=['sf_httpMethod'])\n"
            "    error_weight = (error_durations * error_counts).sum(over='1m')\n"
            "    non_error_weight = (non_error_durations * non_error_counts).sum(over='1m')\n"
            "    total_weight = combine((error_weight if error_weight is not None else 0) + (non_error_weight if non_error_weight is not None else 0))\n"
            "    total = combine((error_counts if error_counts is not None else 0) + (non_error_counts if non_error_counts is not None else 0)).sum(over='1m')\n"
            "    return (total_weight / total)\n"
            f"filter_ = {filter_expr}\n"
            "groupby = ['sf_service', 'sf_environment']\n"
            f"A = (weighted_duration('service.request', 'p90', filter_, groupby) / 1000000000).mean().publish(label='{service_name}')"
        ),
        publish_label=service_name,
    )


def service_request_rate_chart(title: str, description: str, service_name: str, environment: str) -> ChartSpec:
    return ChartSpec(
        name=title,
        description=description,
        program_text=(
            f"A = data('service.request.count', filter={service_filter(service_name, environment)}, "
            f"rollup='rate').sum().publish(label='{service_name}')"
        ),
        publish_label=service_name,
    )


def service_error_rate_chart(title: str, description: str, service_name: str, environment: str) -> ChartSpec:
    filter_expr = service_filter(service_name, environment)
    return ChartSpec(
        name=title,
        description=description,
        program_text=(
            f"errors = data('service.request.count', filter={filter_expr} and filter('sf_error', 'true'), rollup='rate').sum()\n"
            f"total = data('service.request.count', filter={filter_expr}, rollup='rate').sum()\n"
            f"A = ((errors / total) * 100).publish(label='{service_name}')"
        ),
        publish_label=service_name,
    )


def rum_page_load_chart(
    title: str,
    description: str,
    app_name: str,
    environment: str,
    page_filter: str,
) -> ChartSpec:
    return ChartSpec(
        name=title,
        description=description,
        program_text=(
            "A = data('rum.node.page_view.time.ns.p75', "
            f"filter=filter('app', '{app_name}') and "
            f"filter('sf_environment', '{environment}') and "
            f"filter('sf_node_name', '{page_filter}') and "
            "filter('sf_product', 'web')).mean().publish(label='A', enable=False)\n"
            "B = (A / 1000000000).publish(label='broadcast')"
        ),
        publish_label="broadcast",
    )


def deployment_cpu_chart(title: str, description: str, namespace: str, deployment: str) -> ChartSpec:
    return ChartSpec(
        name=title,
        description=description,
        program_text=(
            "A = data('container_cpu_utilization', "
            "filter=filter('k8s.workload.kind', 'Deployment') and "
            f"filter('k8s.namespace.name', '{namespace}') and "
            f"filter('k8s.deployment.name', '{deployment}')).sum().scale(0.01).publish(label='{deployment}')"
        ),
        publish_label=deployment,
    )


def isovalent_media_path_dashboard(namespace: str) -> DashboardSpec:
    del namespace
    tetragon_filter = media_path_tetragon_destination_filter()
    tetragon_5xx_filter = filter_all(tetragon_filter, "filter('code', '5*')")
    tetragon_4xx_filter = filter_all(tetragon_filter, "filter('code', '4*')")
    tetragon_2xx_filter = filter_all(tetragon_filter, "filter('code', '2*')")
    frontend_viewer_egress_filter = filter_all(
        filter_any("namespace", ("streaming-service-app",)),
        filter_any("workload", ("streaming-frontend",)),
        filter_any("binary", ("/usr/sbin/nginx",)),
        "not filter('dstnamespace', '*')",
        "not filter('dstworkload', '*')",
    )
    media_to_frontend_filter = filter_all(
        filter_any("namespace", ("streaming-service-app",)),
        filter_any("workload", ("media-service-demo",)),
        filter_any("dstworkload", ("streaming-frontend",)),
    )
    external_rtsp_ingest_filter = filter_all(
        filter_any("namespace", ("streaming-service-app",)),
        filter_any("workload", ("media-service-demo",)),
        filter_any("binary", ("/mediamtx",)),
        "not filter('dstnamespace', '*')",
        "not filter('dstworkload', '*')",
    )
    return DashboardSpec(
        key="isovalent_media_path",
        aliases=("07 Explain: Are Media Services Still Talking?",),
        name="07 Explain: Are Media Services Still Talking?",
        description=(
            "Use this after 03 Backend Critical Path to explain the media path in plain language: "
            "are requests still arriving, mostly succeeding, merely slowing down, or actually failing "
            "or getting blocked? This dashboard only populates when Isovalent Hubble and Tetragon "
            "telemetry is flowing from the deployed cluster."
        ),
        charts=(
            signalflow_chart(
                "Are media and ad services still receiving replies?",
                "Tetragon HTTP reply volume for the media path, grouped by destination workload.",
                (
                    f"A = data('tetragon_http_response_total', filter={tetragon_filter})"
                    ".sum(by=['dstworkload'], allow_missing=True)"
                    ".sum(over=Args.get('ui.dashboard_window', '15m'))"
                    ".publish(label='Requests by destination')"
                ),
                "Requests by destination",
                column_chart_options("Requests by destination"),
            ),
            signalflow_chart(
                "Are 2xx replies still dominant?",
                "Tetragon 2xx HTTP reply volume for the media path, grouped by destination workload.",
                (
                    f"A = data('tetragon_http_response_total', filter={tetragon_2xx_filter})"
                    ".sum(by=['dstworkload'], allow_missing=True)"
                    ".sum(over=Args.get('ui.dashboard_window', '15m'))"
                    ".publish(label='2xx by destination')"
                ),
                "2xx by destination",
                column_chart_options("2xx by destination"),
            ),
            signalflow_chart(
                "Is the frontend still sending viewer traffic out?",
                "Average transmitted megabits per second from the frontend nginx process toward external viewers over the dashboard window.",
                (
                    f"A = data('tetragon_socket_stats_txbytes_total', filter={frontend_viewer_egress_filter}, rollup='rate')"
                    ".sum()"
                    ".mean(over=Args.get('ui.dashboard_window', '15m'))"
                    ".scale(0.000008)"
                    ".publish(label='Viewer egress Mbps')"
                ),
                "Viewer egress Mbps",
                line_chart_options("Viewer egress Mbps", value_suffix=" Mbps"),
            ),
            signalflow_chart(
                "Is media-service still feeding the frontend at stream rate?",
                "Average transmitted megabits per second from media-service-demo into streaming-frontend over the dashboard window.",
                (
                    f"A = data('tetragon_socket_stats_txbytes_total', filter={media_to_frontend_filter}, rollup='rate')"
                    ".sum()"
                    ".mean(over=Args.get('ui.dashboard_window', '15m'))"
                    ".scale(0.000008)"
                    ".publish(label='Media to frontend Mbps')"
                ),
                "Media to frontend Mbps",
                line_chart_options("Media to frontend Mbps", value_suffix=" Mbps"),
            ),
            signalflow_chart(
                "Is the external RTSP feed still arriving?",
                "Average received megabits per second into the media-service-demo RTSP ingress over the dashboard window.",
                (
                    f"A = data('tetragon_socket_stats_rxbytes_total', filter={external_rtsp_ingest_filter}, rollup='rate')"
                    ".sum()"
                    ".mean(over=Args.get('ui.dashboard_window', '15m'))"
                    ".scale(0.000008)"
                    ".publish(label='External RTSP ingress Mbps')"
                ),
                "External RTSP ingress Mbps",
                line_chart_options("External RTSP ingress Mbps", value_suffix=" Mbps"),
            ),
            signalflow_chart(
                "Which workload pair is carrying the media path?",
                "Tetragon workload-pair response totals for media and ad destinations over the dashboard window.",
                (
                    f"A = data('tetragon_http_response_total', filter={tetragon_5xx_filter})"
                    ".sum(by=['workload', 'dstworkload'], allow_missing=True)"
                    ".sum(over=Args.get('ui.dashboard_window', '15m')).top(count=499).publish(label='A')\n"
                    f"B = data('tetragon_http_response_total', filter={tetragon_2xx_filter})"
                    ".sum(by=['workload', 'dstworkload'], allow_missing=True)"
                    ".sum(over=Args.get('ui.dashboard_window', '15m')).top(count=499).publish(label='B')\n"
                    f"C = data('tetragon_http_response_total', filter={tetragon_4xx_filter})"
                    ".sum(by=['workload', 'dstworkload'], allow_missing=True)"
                    ".sum(over=Args.get('ui.dashboard_window', '15m')).top(count=499).publish(label='C')"
                ),
                "HTTP replies by workload pair",
                table_chart_options(
                    [
                        {"displayName": "HTTP response 5xx", "label": "A", "valuePrefix": "", "valueSuffix": "", "valueUnit": None},
                        {"displayName": "HTTP response 2xx", "label": "B", "valuePrefix": "", "valueSuffix": "", "valueUnit": None},
                        {"displayName": "HTTP response 4xx", "label": "C", "valuePrefix": "", "valueSuffix": "", "valueUnit": None},
                    ],
                    "-HTTP response 2xx",
                ),
            ),
            signalflow_chart(
                "Are 5xx responses climbing for media and ad services?",
                "Tetragon 5xx HTTP reply volume for the media path, grouped by destination workload.",
                (
                    f"A = data('tetragon_http_response_total', filter={tetragon_5xx_filter})"
                    ".sum(by=['dstworkload'], allow_missing=True)"
                    ".sum(over=Args.get('ui.dashboard_window', '15m'))"
                    ".publish(label='5xx by destination')"
                ),
                "5xx by destination",
                column_chart_options("5xx by destination"),
            ),
            signalflow_chart(
                "Are 4xx responses climbing instead?",
                "Tetragon 4xx HTTP reply volume for the media path, grouped by destination workload.",
                (
                    f"A = data('tetragon_http_response_total', filter={tetragon_4xx_filter})"
                    ".sum(by=['dstworkload'], allow_missing=True)"
                    ".sum(over=Args.get('ui.dashboard_window', '15m'))"
                    ".publish(label='4xx by destination')"
                ),
                "4xx by destination",
                column_chart_options("4xx by destination"),
            ),
            signalflow_chart(
                "Is anything being blocked instead of slowed?",
                "Hubble flow verdict mix for the cluster. Use this to separate allowed traffic from denied traffic while the media-path charts explain response behavior.",
                (
                    "A = data('hubble_flows_processed_total').sum(by=['verdict'])"
                    ".publish(label='Traffic by verdict')"
                ),
                "Traffic by verdict",
            ),
        ),
    )


def dashboard_specs(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
    account_group_id: str,
    apm_environment: str,
    rum_app_name: str,
    namespace: str,
    broadcast_page_filter: str,
    include_isovalent_dashboard: bool,
    rtp_placeholder_description: Optional[str] = None,
) -> List[DashboardSpec]:
    specs: List[DashboardSpec] = []
    trace_map_name, trace_map = matched_test(resolved_tests, "trace_map")
    broadcast_name, broadcast = matched_test(resolved_tests, "broadcast_playback")
    rtsp_name, rtsp = matched_test(resolved_tests, "rtsp")
    udp_name, udp = matched_test(resolved_tests, "udp")
    preferred_network_tests = present_tests(resolved_tests, "trace_map", "broadcast_playback")
    legacy_network_tests = present_tests(resolved_tests, "rtsp", "udp")
    network_tests = preferred_network_tests or legacy_network_tests

    overview_charts: List[ChartSpec] = []
    for slot, name, test in network_tests:
        overview_charts.extend(te_network_triplet(slot, name, account_group_id, str(test["testId"])))

    if overview_charts:
        specs.append(
            DashboardSpec(
                key="overview",
                aliases=("Overview", "01 Network Overview", "01 Start Here: Network Symptoms"),
                name="01 Start Here: Network Symptoms",
                description=(
                    "Start here. Confirm whether the current issue looks transport-related before you pivot "
                    "into user impact and backend latency."
                ),
                charts=tuple(overview_charts),
            )
        )

    timeline_charts: List[ChartSpec] = []
    if broadcast:
        timeline_charts.append(
            te_chart(
                "Did broadcast playback latency spike?",
                "Broadcast playback network latency from ThousandEyes.",
                "network.latency",
                account_group_id,
                str(broadcast["testId"]),
                broadcast_name,
            )
        )
    if trace_map:
        timeline_charts.append(
            te_chart(
                "Is trace map latency rising too?",
                "Trace map network latency from ThousandEyes.",
                "network.latency",
                account_group_id,
                str(trace_map["testId"]),
                trace_map_name,
            )
        )
    if not preferred_network_tests and rtsp:
        timeline_charts.append(
            te_chart(
                "Did RTSP latency spike?",
                "RTSP network latency from ThousandEyes.",
                "network.latency",
                account_group_id,
                str(rtsp["testId"]),
                rtsp_name,
            )
        )
    if not preferred_network_tests and udp:
        timeline_charts.append(
            te_chart(
                "Is UDP packet loss rising?",
                "UDP media-path loss from ThousandEyes.",
                "network.loss",
                account_group_id,
                str(udp["testId"]),
                udp_name,
            )
        )
    timeline_charts.extend(
        [
            rum_page_load_chart(
                "Are viewers seeing slower broadcast loads?",
                "Browser RUM p75 page load time for the broadcast surface.",
                rum_app_name,
                apm_environment,
                broadcast_page_filter,
            ),
            weighted_service_latency_chart(
                "Is frontend request latency rising too?",
                "APM p90 latency for the streaming-frontend service.",
                "streaming-frontend",
                apm_environment,
            ),
            weighted_service_latency_chart(
                "Is media-service the backend bottleneck?",
                "APM p90 latency for media-service-demo.",
                "media-service-demo",
                apm_environment,
            ),
            deployment_cpu_chart(
                "Are media pods saturated?",
                "Kubernetes CPU usage for the media-service-demo deployment.",
                namespace,
                "media-service-demo",
            ),
        ]
    )
    specs.append(
        DashboardSpec(
            key="timeline",
            aliases=("02 Symptom To Root Cause Timeline", "02 Pivot: User Impact To Root Cause"),
            name="02 Pivot: User Impact To Root Cause",
            description=(
                "Use this during live troubleshooting to line up transport symptoms, viewer impact, "
                "frontend latency, media-service latency, and workload saturation on the same time range."
            ),
            charts=tuple(timeline_charts),
        )
    )

    specs.append(
        DashboardSpec(
            key="critical_path",
            aliases=("03 Stream Session Critical Path", "03 Backend Critical Path"),
            name="03 Backend Critical Path",
            description=(
                "Use this when the issue looks backend-driven. Compare latency and error rate across the "
                "frontend -> media -> user/content/billing/ad path to see which dependency is stretching the request."
            ),
            charts=(
                service_request_rate_chart(
                    "Is media request volume dropping?",
                    "APM request rate for media-service-demo.",
                    "media-service-demo",
                    apm_environment,
                ),
                service_error_rate_chart(
                    "Is media-service error rate rising?",
                    "APM error-rate percentage for media-service-demo.",
                    "media-service-demo",
                    apm_environment,
                ),
                weighted_service_latency_chart(
                    "Is latency concentrated in media-service?",
                    "APM p90 latency for media-service-demo.",
                    "media-service-demo",
                    apm_environment,
                ),
                weighted_service_latency_chart(
                    "Is frontend latency following backend issues?",
                    "APM p90 latency for streaming-frontend.",
                    "streaming-frontend",
                    apm_environment,
                ),
                weighted_service_latency_chart(
                    "Is user-service part of the slowdown?",
                    "APM p90 latency for user-service-demo.",
                    "user-service-demo",
                    apm_environment,
                ),
                weighted_service_latency_chart(
                    "Is content-service part of the slowdown?",
                    "APM p90 latency for content-service-demo.",
                    "content-service-demo",
                    apm_environment,
                ),
                weighted_service_latency_chart(
                    "Is billing-service part of the slowdown?",
                    "APM p90 latency for billing-service.",
                    "billing-service",
                    apm_environment,
                ),
                weighted_service_latency_chart(
                    "Is ad-service part of the slowdown?",
                    "APM p90 latency for ad-service-demo.",
                    "ad-service-demo",
                    apm_environment,
                ),
            ),
        )
    )

    if trace_map:
        trace_map_id = str(trace_map["testId"])
        specs.append(
            DashboardSpec(
                key="trace_map_detail",
                aliases=(
                    "aleccham-broadcast-trace-map",
                    "04 Deep Dive: Trace Map Path",
                    "RTSP-TCP-8554",
                    "RTSP-TCP-554",
                    "04 RTSP-TCP-8554",
                    "04 RTSP-TCP-554",
                    "04 Deep Dive: RTSP Control Path",
                ),
                name="04 Deep Dive: Trace Map Path",
                description=(
                    f"Deep dive for {trace_map_name} test {trace_map_id}. Use this when the public trace-map path "
                    "looks suspicious and you need the frontend network measurements in Splunk."
                ),
                charts=(
                    te_chart("Trace map latency trend", f"network.latency for {trace_map_name}.", "network.latency", account_group_id, trace_map_id, trace_map_name),
                    te_chart("Trace map jitter trend", f"network.jitter for {trace_map_name}.", "network.jitter", account_group_id, trace_map_id, trace_map_name),
                    te_chart("Trace map packet loss trend", f"network.loss for {trace_map_name}.", "network.loss", account_group_id, trace_map_id, trace_map_name),
                ),
            )
        )

    if broadcast:
        broadcast_id = str(broadcast["testId"])
        specs.append(
            DashboardSpec(
                key="broadcast_detail",
                aliases=(
                    "aleccham-broadcast-playback",
                    "05 Deep Dive: Broadcast Playback Path",
                    "UDP-Media-Path",
                    "05 UDP-Media-Path",
                    "05 Deep Dive: UDP Media Path",
                ),
                name="05 Deep Dive: Broadcast Playback Path",
                description=(
                    f"Deep dive for {broadcast_name} test {broadcast_id}. Use this when the public playback path "
                    "looks suspicious and you need the frontend network measurements in Splunk."
                ),
                charts=(
                    te_chart("Broadcast playback latency trend", f"network.latency for {broadcast_name}.", "network.latency", account_group_id, broadcast_id, broadcast_name),
                    te_chart("Broadcast playback jitter trend", f"network.jitter for {broadcast_name}.", "network.jitter", account_group_id, broadcast_id, broadcast_name),
                    te_chart("Broadcast playback packet loss trend", f"network.loss for {broadcast_name}.", "network.loss", account_group_id, broadcast_id, broadcast_name),
                ),
            )
        )

    if not preferred_network_tests and rtsp:
        rtsp_id = str(rtsp["testId"])
        specs.append(
            DashboardSpec(
                key="rtsp_detail",
                aliases=(
                    "RTSP-TCP-8554",
                    "RTSP-TCP-554",
                    "04 RTSP-TCP-8554",
                    "04 RTSP-TCP-554",
                    "04 Deep Dive: RTSP Control Path",
                ),
                name="04 Deep Dive: RTSP Control Path",
                description=(
                    f"Deep dive for {rtsp_name} test {rtsp_id}. Use this when the RTSP control path looks suspicious "
                    "and you need the full network and BGP context."
                ),
                charts=(
                    te_chart("RTSP latency trend", f"network.latency for {rtsp_name}.", "network.latency", account_group_id, rtsp_id, rtsp_name),
                    te_chart("RTSP jitter trend", f"network.jitter for {rtsp_name}.", "network.jitter", account_group_id, rtsp_id, rtsp_name),
                    te_chart("RTSP packet loss trend", f"network.loss for {rtsp_name}.", "network.loss", account_group_id, rtsp_id, rtsp_name),
                    te_chart("Did the path change?", f"bgp.path_changes.count for {rtsp_name}.", "bgp.path_changes.count", account_group_id, rtsp_id, rtsp_name),
                    te_chart("Did BGP reachability change?", f"bgp.reachability for {rtsp_name}.", "bgp.reachability", account_group_id, rtsp_id, rtsp_name),
                    te_chart("Did BGP updates spike?", f"bgp.updates.count for {rtsp_name}.", "bgp.updates.count", account_group_id, rtsp_id, rtsp_name),
                ),
            )
        )

    if not preferred_network_tests and udp:
        udp_id = str(udp["testId"])
        specs.append(
            DashboardSpec(
                key="udp_detail",
                aliases=("UDP-Media-Path", "05 UDP-Media-Path", "05 Deep Dive: UDP Media Path"),
                name="05 Deep Dive: UDP Media Path",
                description=(
                    f"Deep dive for {udp_name} test {udp_id}. Use this when the media path looks suspicious "
                    "and you need transport detail for the UDP flow."
                ),
                charts=(
                    te_chart("UDP latency trend", f"network.latency for {udp_name}.", "network.latency", account_group_id, udp_id, udp_name),
                    te_chart("UDP jitter trend", f"network.jitter for {udp_name}.", "network.jitter", account_group_id, udp_id, udp_name),
                    te_chart("UDP packet loss trend", f"network.loss for {udp_name}.", "network.loss", account_group_id, udp_id, udp_name),
                ),
            )
        )

    rtp_name, rtp = matched_test(resolved_tests, "rtp")
    if rtp:
        rtp_id = str(rtp["testId"])
        rtp_charts: tuple[ChartSpec, ...]
        rtp_description = (
            rtp_placeholder_description
            if rtp_placeholder_description is not None
            else (
                f"Deep dive for {rtp_name} test {rtp_id}. Use this when the RTP proxy path looks suspicious "
                "and you need voice-quality telemetry for MOS, loss, discards, duration, and packet delay variation."
            )
        )
        if rtp_placeholder_description is not None:
            rtp_charts = ()
        else:
            rtp_charts = (
                te_chart("RTP MOS trend", f"rtp.client.request.mos for {rtp_name}.", "rtp.client.request.mos", account_group_id, rtp_id, rtp_name),
                te_chart("RTP frame loss trend", f"rtp.client.request.loss for {rtp_name}.", "rtp.client.request.loss", account_group_id, rtp_id, rtp_name),
                te_chart("RTP discards trend", f"rtp.client.request.discards for {rtp_name}.", "rtp.client.request.discards", account_group_id, rtp_id, rtp_name),
                te_chart("RTP stream duration", f"rtp.client.request.duration for {rtp_name}.", "rtp.client.request.duration", account_group_id, rtp_id, rtp_name),
                te_chart("RTP packet delay variation", f"rtp.client.request.pdv for {rtp_name}.", "rtp.client.request.pdv", account_group_id, rtp_id, rtp_name),
            )
        specs.append(
            DashboardSpec(
                key="rtp_detail",
                aliases=("RTP-Stream-Proxy", "06 RTP-Stream-Proxy", "06 Deep Dive: RTP Media Quality"),
                name="06 Deep Dive: RTP Media Quality",
                description=rtp_description,
                charts=rtp_charts,
            )
        )

    if include_isovalent_dashboard:
        specs.append(isovalent_media_path_dashboard(namespace))

    return specs


def summary_description(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
    configured_slots: Dict[str, tuple[str, ...]],
    has_isovalent_dashboard: bool,
    tests_without_recent_data: Optional[List[str]] = None,
) -> str:
    present: List[str] = []
    missing: List[str] = []
    for slot, names in configured_slots.items():
        matched_name, matched = matched_test(resolved_tests, slot)
        if matched_name and matched:
            present.append(f"{matched_name} ({matched['testId']})")
        else:
            missing.append(names[0])
    demo_flow = (
        "Open the numbered troubleshooting flow in order: 01 Start Here: Network Symptoms, "
        "02 Pivot: User Impact To Root Cause, 03 Backend Critical Path, "
        + (
            "then 07 Explain: Are Media Services Still Talking? for the plain-language media-path view, "
            if has_isovalent_dashboard
            else ""
        )
        + "then the protocol deep dives."
    )
    presence = f"Present ThousandEyes tests: {', '.join(present)}." if present else "No matching repo ThousandEyes tests were found."
    missing_text = f" Missing and not rendered as detail dashboards: {', '.join(missing)}." if missing else ""
    missing_data_text = (
        " Present but skipped because Splunk had no recent metric data: "
        + ", ".join(tests_without_recent_data)
        + "."
        if tests_without_recent_data
        else ""
    )
    frontend_missing = [configured_slots[key][0] for key in ("trace_map", "broadcast_playback") if not matched_test(resolved_tests, key)[0]]
    frontend_hint = (
        " Frontend HTTP tests are missing, so Splunk may need the Demo Monkey HTTP tests before the dashboard group fully populates."
        if frontend_missing
        else ""
    )
    return f"{demo_flow} {presence}{missing_text}{missing_data_text}{frontend_hint}"


def has_resolved_thousandeyes_tests(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
) -> bool:
    return any(test for _, test in resolved_tests.values())


def rtp_placeholder_dashboard_description(
    test_name: str,
    test_id: str,
    missing_metrics: List[str],
    validation_window_hours: int,
) -> str:
    metric_list = ", ".join(missing_metrics)
    return (
        f"RTP test {test_name} ({test_id}) is still configured, but Splunk did not return recent telemetry for "
        f"{metric_list} in the last {validation_window_hours}h. This placeholder intentionally replaces the old "
        "deep-dive charts so stale RTP visuals do not linger after sync. Repair the ThousandEyes voice test or "
        "metric stream, then rerun this sync to restore the full dashboard."
    )


def metric_validation_specs(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
) -> List[MetricValidationSpec]:
    network_tests = present_tests(resolved_tests, *NETWORK_SLOTS)
    validations: List[MetricValidationSpec] = []
    seen: set[tuple[str, str]] = set()
    for _, test_name, test in network_tests:
        test_id = str(test["testId"])
        for metric in NETWORK_METRICS:
            key = (metric, test_id)
            if key in seen:
                continue
            validations.append(MetricValidationSpec(metric=metric, test_name=test_name, test_id=test_id))
            seen.add(key)
    return validations


def select_network_tests_with_recent_data(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
    validation_results: List[Dict[str, Any]],
) -> tuple[set[str], List[Dict[str, Any]]]:
    has_data_by_metric = {
        (str(result.get("testId", "")), str(result.get("metric", ""))): bool(result.get("hasData"))
        for result in validation_results
    }

    available_slots: set[str] = set()
    missing: List[Dict[str, Any]] = []
    for slot, test_name, test in present_tests(resolved_tests, *NETWORK_SLOTS):
        test_id = str(test["testId"])
        missing_metrics = [
            metric for metric in NETWORK_METRICS if not has_data_by_metric.get((test_id, metric), False)
        ]
        if missing_metrics:
            missing.append({"slot": slot, "name": test_name, "test_id": test_id, "metrics": missing_metrics})
            continue
        available_slots.add(slot)

    preferred_available = {slot for slot in PREFERRED_NETWORK_SLOTS if slot in available_slots}
    legacy_available = {slot for slot in LEGACY_NETWORK_SLOTS if slot in available_slots}
    return preferred_available or legacy_available, missing


def without_unavailable_network_tests(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
    allowed_slots: set[str],
) -> Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]]:
    filtered = dict(resolved_tests)
    for slot in NETWORK_SLOTS:
        if slot not in allowed_slots and slot in filtered:
            filtered[slot] = (None, None)
    return filtered


def format_missing_metric_tests(missing_tests: List[Dict[str, Any]]) -> List[str]:
    return [
        f"{item['name']} ({item['test_id']}) [{', '.join(item['metrics'])}]"
        for item in missing_tests
    ]


def isovalent_metric_validation_specs() -> List[MetricValidationSpec]:
    return [
        MetricValidationSpec(
            metric="hubble_http_requests_total",
            test_name="Isovalent Hubble HTTP telemetry",
            test_id="cluster",
            program_text=(
                "A = data('hubble_http_requests_total').mean(over='1m').sum()"
                ".publish(label='dashboard_validation')"
            ),
        ),
        MetricValidationSpec(
            metric="tetragon_http_response_total",
            test_name="Isovalent Tetragon HTTP telemetry",
            test_id="cluster",
            program_text=(
                "A = data('tetragon_http_response_total').sum()"
                ".publish(label='dashboard_validation')"
            ),
        ),
        MetricValidationSpec(
            metric="tetragon_socket_stats_txbytes_total",
            test_name="Isovalent Tetragon socket byte telemetry",
            test_id="cluster",
            program_text=(
                "A = data('tetragon_socket_stats_txbytes_total', rollup='rate').sum()"
                ".publish(label='dashboard_validation')"
            ),
        ),
        MetricValidationSpec(
            metric="tetragon_socket_stats_rxbytes_total",
            test_name="Isovalent Tetragon socket receive telemetry",
            test_id="cluster",
            program_text=(
                "A = data('tetragon_socket_stats_rxbytes_total', rollup='rate').sum()"
                ".publish(label='dashboard_validation')"
            ),
        ),
    ]


def rtp_metric_validation_specs(
    resolved_tests: Dict[str, tuple[Optional[str], Optional[Dict[str, Any]]]],
) -> List[MetricValidationSpec]:
    rtp_name, rtp = matched_test(resolved_tests, "rtp")
    if not rtp_name or not rtp:
        return []
    rtp_id = str(rtp["testId"])
    return [
        MetricValidationSpec(metric=metric, test_name=rtp_name, test_id=rtp_id)
        for metric in RTP_METRICS
    ]


def validate_metric_data(
    splunk_realm: str,
    validation_token: str,
    account_group_id: str,
    validations: List[MetricValidationSpec],
    validation_window_hours: int,
) -> List[Dict[str, Any]]:
    if not validations:
        return []
    if not SIGNALFLOW_VALIDATOR.exists():
        raise SystemExit(
            f"Missing SignalFlow validator helper at {SIGNALFLOW_VALIDATOR}. "
            "Restore the file or rerun with --skip-te-metric-validation."
        )
    payload = {
        "token": validation_token,
        "realm": splunk_realm,
        "accountGroupId": account_group_id,
        "validationWindowHours": validation_window_hours,
        "validations": [
            {
                "metric": validation.metric,
                "testName": validation.test_name,
                "testId": validation.test_id,
                "program": validation.program_text,
            }
            for validation in validations
        ],
    }
    for attempt in range(SIGNALFLOW_TIMEOUT_RETRIES + 1):
        try:
            completed = subprocess.run(
                ["node", str(SIGNALFLOW_VALIDATOR)],
                input=json.dumps(payload),
                text=True,
                capture_output=True,
                check=False,
            )
        except FileNotFoundError as error:
            raise SystemExit(
                "ThousandEyes metric validation requires Node.js because it uses the Splunk SignalFlow websocket API. "
                "Install node or rerun with --skip-te-metric-validation if you need to update dashboards without the live data check."
            ) from error

        stdout = completed.stdout.strip()
        stderr = completed.stderr.strip()
        if completed.returncode == 2:
            raise SystemExit(
                "ThousandEyes metric validation could not authenticate against Splunk SignalFlow APIs. "
                "Set SPLUNK_VALIDATION_TOKEN to a Splunk API token with API scope, or rerun "
                "with --skip-te-metric-validation if you need to update dashboards without the live data check."
            )
        if completed.returncode == 0:
            try:
                results = json.loads(stdout)
            except json.JSONDecodeError as error:
                raise SystemExit(
                    "ThousandEyes metric validation returned an unreadable SignalFlow response. "
                    f"stdout={stdout!r} stderr={stderr!r}"
                ) from error
            if not isinstance(results, list):
                raise SystemExit(
                    "ThousandEyes metric validation returned an unexpected SignalFlow payload. "
                    f"stdout={stdout!r}"
                )
            return results

        detail = stderr or stdout or f"validator exited with status {completed.returncode}"
        if SIGNALFLOW_TIMEOUT_TEXT in detail and attempt < SIGNALFLOW_TIMEOUT_RETRIES:
            print(
                f"Warning: SignalFlow validator timed out (attempt {attempt + 1}/{SIGNALFLOW_TIMEOUT_RETRIES + 1}). Retrying...",
                file=sys.stderr,
            )
            time.sleep(SIGNALFLOW_TIMEOUT_RETRY_DELAY_SECONDS)
            continue
        raise SystemExit(f"ThousandEyes metric validation failed while querying Splunk SignalFlow: {detail}")

    raise AssertionError("SignalFlow validator retry loop should always return or raise before this point.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default=str(DEFAULT_ENV_FILE), help="Path to the env file. Defaults to the repo-root .env.")
    parser.add_argument("--group-id", default=None, help="Existing Splunk dashboard group ID to update.")
    parser.add_argument("--group-name", default=None, help="Create or match a dashboard group by exact name.")
    parser.add_argument("--namespace", default=None, help="Kubernetes namespace for infra charts. Defaults to STREAMING_K8S_NAMESPACE or streaming-service-app.")
    parser.add_argument("--broadcast-page-filter", default=None, help="RUM sf_node_name filter used for the broadcast page. Defaults to *broadcast*.")
    parser.add_argument(
        "--validation-window-hours",
        type=int,
        default=24,
        help="Hours of Splunk data to inspect when validating ThousandEyes metrics. Defaults to 24.",
    )
    parser.add_argument(
        "--skip-te-metric-validation",
        action="store_true",
        help="Skip the post-sync check that verifies ThousandEyes metric data exists in Splunk for the dashboard test IDs.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print the planned API mutations without writing them.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    env_file = load_env_file(Path(args.env_file))

    splunk_realm = require_value(env_file, "SPLUNK_REALM")
    splunk_token = require_value(env_file, "SPLUNK_ACCESS_TOKEN")
    validation_token = env_value(env_file, "SPLUNK_VALIDATION_TOKEN") or splunk_token
    thousandeyes_token = require_value(env_file, "THOUSANDEYES_BEARER_TOKEN")
    thousandeyes_account_group_id = require_value(env_file, "THOUSANDEYES_ACCOUNT_GROUP_ID")

    group_id = args.group_id or env_value(env_file, "SPLUNK_DEMO_DASHBOARD_GROUP_ID")
    namespace = args.namespace or env_value(env_file, "STREAMING_K8S_NAMESPACE", DEFAULT_NAMESPACE)
    apm_environment = env_value(env_file, "SPLUNK_DEPLOYMENT_ENVIRONMENT", DEFAULT_APM_ENVIRONMENT) or DEFAULT_APM_ENVIRONMENT
    rum_app_name = env_value(env_file, "SPLUNK_RUM_APP_NAME", DEFAULT_RUM_APP) or DEFAULT_RUM_APP
    broadcast_page_filter = (
        args.broadcast_page_filter
        or env_value(env_file, "SPLUNK_BROADCAST_PAGE_FILTER", DEFAULT_BROADCAST_PAGE_FILTER)
        or DEFAULT_BROADCAST_PAGE_FILTER
    )

    splunk_api = JsonApi(
        base_url=f"https://api.{splunk_realm}.signalfx.com/v2",
        headers={"X-SF-Token": splunk_token},
        dry_run=args.dry_run,
    )
    thousandeyes_api = JsonApi(
        base_url="https://api.thousandeyes.com/v7",
        headers={"Authorization": f"Bearer {thousandeyes_token}"},
        dry_run=args.dry_run,
    )

    configured_slots = configured_test_slots(env_file)
    configured_test_ids = configured_test_id_overrides(env_file)
    tests_by_name, tests_by_id = te_test_indexes(thousandeyes_api, thousandeyes_account_group_id)
    resolved_tests = resolve_tests(tests_by_name, tests_by_id, configured_slots, configured_test_ids)
    if not has_resolved_thousandeyes_tests(resolved_tests):
        raise SystemExit(
            "No matching repo ThousandEyes tests were found in the selected account group. "
            "Create the tests first, then rerun the dashboard sync."
        )
    warn_if_missing_rtp_metric_stream(thousandeyes_api, thousandeyes_account_group_id, resolved_tests)
    include_isovalent_dashboard = True
    include_rtp_dashboard = True
    validation_results: List[Dict[str, Any]] = []
    rtp_validation_results: List[Dict[str, Any]] = []
    isovalent_validation_results: List[Dict[str, Any]] = []
    tests_without_recent_data: List[str] = []
    rtp_placeholder_description = None
    dashboard_resolved_tests = resolved_tests
    if not args.skip_te_metric_validation:
        validation_results = validate_metric_data(
            splunk_realm,
            validation_token,
            thousandeyes_account_group_id,
            metric_validation_specs(resolved_tests),
            args.validation_window_hours,
        )
        active_network_slots, missing_metric_tests = select_network_tests_with_recent_data(
            resolved_tests,
            validation_results,
        )
        tests_without_recent_data = format_missing_metric_tests(missing_metric_tests)
        if not active_network_slots:
            missing_text = "; ".join(tests_without_recent_data)
            raise SystemExit(
                "ThousandEyes dashboard metric validation failed. Splunk did not return the required "
                "network metrics for any matched network-backed ThousandEyes test in the last "
                f"{args.validation_window_hours}h: {missing_text}."
            )
        dashboard_resolved_tests = without_unavailable_network_tests(
            resolved_tests,
            active_network_slots,
        )
        if tests_without_recent_data:
            print(
                "Warning: skipping dashboard sections for ThousandEyes tests without recent Splunk "
                f"network metrics in the last {args.validation_window_hours}h: "
                + "; ".join(tests_without_recent_data),
                file=sys.stderr,
            )
        rtp_validation_results = validate_metric_data(
            splunk_realm,
            validation_token,
            thousandeyes_account_group_id,
            rtp_metric_validation_specs(resolved_tests),
            args.validation_window_hours,
        )
        missing_rtp = [result for result in rtp_validation_results if not result["hasData"]]
        if missing_rtp:
            include_rtp_dashboard = False
            tests_without_recent_data.extend(
                format_missing_metric_tests(
                    [
                        {
                            "slot": "rtp",
                            "name": result["testName"],
                            "test_id": result["testId"],
                            "metrics": [result["metric"]],
                        }
                        for result in missing_rtp
                    ]
                )
            )
            missing_text = "; ".join(
                f"{result['metric']} for {result['testName']} ({result['testId']})"
                for result in missing_rtp
            )
            rtp_name, rtp_test = matched_test(resolved_tests, "rtp")
            if rtp_name and rtp_test:
                rtp_placeholder_description = rtp_placeholder_dashboard_description(
                    rtp_name,
                    str(rtp_test["testId"]),
                    [result["metric"] for result in missing_rtp],
                    args.validation_window_hours,
                )
            print(
                "Warning: skipping 06 Deep Dive: RTP Media Quality because Splunk did not "
                f"return RTP telemetry for {missing_text} in the last "
                f"{args.validation_window_hours}h.",
                file=sys.stderr,
            )
        isovalent_validation_results = validate_metric_data(
            splunk_realm,
            validation_token,
            thousandeyes_account_group_id,
            isovalent_metric_validation_specs(),
            args.validation_window_hours,
        )
        missing_isovalent = [result for result in isovalent_validation_results if not result["hasData"]]
        if missing_isovalent:
            include_isovalent_dashboard = False
            missing_text = "; ".join(
                f"{result['metric']} for {result['testName']} ({result['testId']})"
                for result in missing_isovalent
            )
            print(
                "Warning: skipping 07 Explain: Are Media Services Still Talking? because Splunk did not "
                f"return core Isovalent telemetry for {missing_text} in the last "
                f"{args.validation_window_hours}h. This usually means Isovalent is not deployed in the "
                "target cluster yet or Splunk is not receiving the Hubble and Tetragon metrics that power "
                "the custom dashboard.",
                file=sys.stderr,
            )

    specs = dashboard_specs(
        dashboard_resolved_tests,
        thousandeyes_account_group_id,
        apm_environment,
        rum_app_name,
        namespace,
        broadcast_page_filter,
        include_isovalent_dashboard=include_isovalent_dashboard,
        rtp_placeholder_description=rtp_placeholder_description,
    )
    if not specs:
        raise SystemExit("No dashboards were generated from the current ThousandEyes and Splunk configuration.")

    group = choose_group(splunk_api, group_id=group_id, group_name=args.group_name)
    dashboards = load_dashboard_map(splunk_api, group)

    ordered_ids: List[str] = []
    dashboard_results: List[Dict[str, str]] = []
    for spec in specs:
        existing = find_dashboard(dashboards, spec.aliases)
        dashboard_id = upsert_dashboard(splunk_api, group["id"], existing, spec)
        ordered_ids.append(dashboard_id)
        dashboard_results.append(
            {
                "name": spec.name,
                "id": dashboard_id,
                "status": "updated" if existing else "created",
            }
        )
        if dashboard_id.startswith("dry-run-dashboard-"):
            dashboards[dashboard_id] = {"id": dashboard_id, "name": spec.name, "charts": []}
        else:
            dashboards[dashboard_id] = splunk_api.request("GET", f"/dashboard/{dashboard_id}")

    group_description = summary_description(
        resolved_tests,
        configured_slots,
        has_isovalent_dashboard=include_isovalent_dashboard,
        tests_without_recent_data=tests_without_recent_data,
    )
    updated_group = update_group(splunk_api, group, ordered_ids, group_description)

    output = {
        "group": {
            "id": updated_group.get("id", group["id"]),
            "name": updated_group.get("name", group["name"]),
        },
        "dashboards": dashboard_results,
        "metricValidation": validation_results,
        "rtpMetricValidation": rtp_validation_results,
        "isovalentMetricValidation": isovalent_validation_results,
        "rtpDashboardIncluded": include_rtp_dashboard,
        "rtpDashboardPlaceholder": rtp_placeholder_description is not None,
        "isovalentDashboardIncluded": include_isovalent_dashboard,
        "presentTests": {
            name: test["testId"]
            for name, test in (matched_test(resolved_tests, slot) for slot in configured_slots)
            if name and test
        },
        "missingTests": [names[0] for slot, names in configured_slots.items() if not matched_test(resolved_tests, slot)[0]],
        "testsWithoutRecentData": tests_without_recent_data,
        "namespace": namespace,
        "rumApp": rum_app_name,
        "apmEnvironment": apm_environment,
    }
    print(json.dumps(output, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
