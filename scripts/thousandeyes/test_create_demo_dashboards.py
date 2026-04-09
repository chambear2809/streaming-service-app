import importlib.util
import sys
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("create-demo-dashboards.py")
SPEC = importlib.util.spec_from_file_location("create_demo_dashboards", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load dashboard module from {MODULE_PATH}.")
dashboards = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = dashboards
SPEC.loader.exec_module(dashboards)


def sample_resolved_tests():
    return {
        "trace_map": ("aleccham-broadcast-trace-map", {"testId": "8400453"}),
        "broadcast_playback": ("aleccham-broadcast-playback", {"testId": "8400454"}),
        "rtp": ("RTP-Stream-Proxy", {"testId": "8405216"}),
        "rtsp": (None, None),
        "udp": (None, None),
    }


class CreateDemoDashboardsTests(unittest.TestCase):
    def test_update_group_preserves_existing_dashboards_not_in_current_render(self):
        class FakeApi:
            def __init__(self):
                self.calls = []

            def request(self, method, path, payload=None, query=None):
                self.calls.append((method, path, payload, query))
                return payload

        api = FakeApi()
        group = {
            "id": "group-1",
            "dashboards": ["dash-1", "dash-2", "dash-3"],
            "dashboardConfigs": [
                {"configId": "cfg-1", "dashboardId": "dash-1"},
                {"configId": "cfg-2", "dashboardId": "dash-2"},
                {"configId": "cfg-3", "dashboardId": "dash-3"},
            ],
        }

        result = dashboards.update_group(
            api,
            group,
            ordered_dashboard_ids=["dash-1", "dash-3"],
            description="updated",
        )

        self.assertEqual(result["dashboards"], ["dash-1", "dash-3", "dash-2"])
        self.assertEqual(
            [config["dashboardId"] for config in result["dashboardConfigs"]],
            ["dash-1", "dash-3", "dash-2"],
        )
        self.assertEqual(result["description"], "updated")

    def test_metric_validation_specs_cover_all_present_network_tests(self):
        resolved_tests = {
            "trace_map": ("aleccham-broadcast-trace-map", {"testId": "8400453"}),
            "broadcast_playback": ("aleccham-broadcast-playback", {"testId": "8400454"}),
            "rtsp": ("RTSP-TCP-8554", {"testId": "8399993"}),
            "udp": ("UDP-Media-Path", {"testId": "8399994"}),
            "rtp": ("RTP-Stream-Proxy", {"testId": "8405216"}),
        }

        validations = dashboards.metric_validation_specs(resolved_tests)

        self.assertEqual(len(validations), 12)
        self.assertEqual(
            {
                (validation.metric, validation.test_id)
                for validation in validations
            },
            {
                ("network.latency", "8400453"),
                ("network.loss", "8400453"),
                ("network.jitter", "8400453"),
                ("network.latency", "8400454"),
                ("network.loss", "8400454"),
                ("network.jitter", "8400454"),
                ("network.latency", "8399993"),
                ("network.loss", "8399993"),
                ("network.jitter", "8399993"),
                ("network.latency", "8399994"),
                ("network.loss", "8399994"),
                ("network.jitter", "8399994"),
            },
        )

    def test_select_network_tests_with_recent_data_prefers_http_tests_but_skips_stale_ones(self):
        resolved_tests = {
            "trace_map": ("aleccham-broadcast-trace-map", {"testId": "8400453"}),
            "broadcast_playback": ("aleccham-broadcast-playback", {"testId": "8400454"}),
            "rtsp": ("RTSP-TCP-8554", {"testId": "8399993"}),
            "udp": ("UDP-Media-Path", {"testId": "8399994"}),
        }
        validation_results = [
            {"metric": "network.latency", "testId": "8400453", "hasData": True},
            {"metric": "network.loss", "testId": "8400453", "hasData": True},
            {"metric": "network.jitter", "testId": "8400453", "hasData": True},
            {"metric": "network.latency", "testId": "8400454", "hasData": False},
            {"metric": "network.loss", "testId": "8400454", "hasData": False},
            {"metric": "network.jitter", "testId": "8400454", "hasData": False},
            {"metric": "network.latency", "testId": "8399993", "hasData": True},
            {"metric": "network.loss", "testId": "8399993", "hasData": True},
            {"metric": "network.jitter", "testId": "8399993", "hasData": True},
            {"metric": "network.latency", "testId": "8399994", "hasData": True},
            {"metric": "network.loss", "testId": "8399994", "hasData": True},
            {"metric": "network.jitter", "testId": "8399994", "hasData": True},
        ]

        active_slots, missing = dashboards.select_network_tests_with_recent_data(
            resolved_tests,
            validation_results,
        )
        filtered = dashboards.without_unavailable_network_tests(resolved_tests, active_slots)
        formatted = dashboards.format_missing_metric_tests(missing)

        self.assertEqual(active_slots, {"trace_map"})
        self.assertIsNotNone(filtered["trace_map"][1])
        self.assertEqual(filtered["broadcast_playback"], (None, None))
        self.assertEqual(filtered["rtsp"], (None, None))
        self.assertEqual(filtered["udp"], (None, None))
        self.assertEqual(
            formatted,
            [
                "aleccham-broadcast-playback (8400454) [network.latency, network.loss, network.jitter]",
            ],
        )

    def test_isovalent_dashboard_uses_windowed_counts_and_supported_chart_types(self):
        dashboard = dashboards.isovalent_media_path_dashboard("streaming-service-app")

        chart_names = [chart.name for chart in dashboard.charts]
        self.assertEqual(
            chart_names,
            [
                "Are media and ad services still receiving replies?",
                "Are 2xx replies still dominant?",
                "Is the frontend still sending viewer traffic out?",
                "Is media-service still feeding the frontend at stream rate?",
                "Is the external RTSP feed still arriving?",
                "Which workload pair is carrying the media path?",
                "Are 5xx responses climbing for media and ad services?",
                "Are 4xx responses climbing instead?",
                "Is anything being blocked instead of slowed?",
            ],
        )
        self.assertNotIn("Are most media-path replies still healthy?", chart_names)
        self.assertNotIn("Where is the wait time growing first?", chart_names)

        for chart in dashboard.charts[:7]:
            self.assertIn("Args.get('ui.dashboard_window', '15m')", chart.program_text)

        self.assertEqual(dashboard.charts[0].options["defaultPlotType"], "ColumnChart")
        self.assertEqual(dashboard.charts[1].options["defaultPlotType"], "ColumnChart")
        self.assertEqual(dashboard.charts[2].options["defaultPlotType"], "LineChart")
        self.assertEqual(dashboard.charts[3].options["defaultPlotType"], "LineChart")
        self.assertEqual(dashboard.charts[4].options["defaultPlotType"], "LineChart")
        self.assertEqual(dashboard.charts[5].options["type"], "TableChart")
        self.assertEqual(dashboard.charts[6].options["defaultPlotType"], "ColumnChart")
        self.assertEqual(dashboard.charts[7].options["defaultPlotType"], "ColumnChart")
        self.assertIsNone(dashboard.charts[8].options)

        self.assertIn("filter('code', '2*')", dashboard.charts[1].program_text)
        self.assertIn("tetragon_socket_stats_txbytes_total", dashboard.charts[2].program_text)
        self.assertIn("filter('binary', '/usr/sbin/nginx')", dashboard.charts[2].program_text)
        self.assertIn("not filter('dstnamespace', '*')", dashboard.charts[2].program_text)
        self.assertIn("rollup='rate'", dashboard.charts[2].program_text)
        self.assertIn(".scale(0.000008)", dashboard.charts[2].program_text)
        self.assertIn("tetragon_socket_stats_txbytes_total", dashboard.charts[3].program_text)
        self.assertIn("filter('dstworkload', 'streaming-frontend')", dashboard.charts[3].program_text)
        self.assertIn("tetragon_socket_stats_rxbytes_total", dashboard.charts[4].program_text)
        self.assertIn("filter('binary', '/mediamtx')", dashboard.charts[4].program_text)
        self.assertIn("not filter('dstnamespace', '*')", dashboard.charts[4].program_text)
        self.assertIn("rollup='rate'", dashboard.charts[4].program_text)
        self.assertIn(".scale(0.000008)", dashboard.charts[4].program_text)
        self.assertIn("filter('code', '5*')", dashboard.charts[6].program_text)
        self.assertIn("filter('code', '4*')", dashboard.charts[7].program_text)
        self.assertIn("sum(by=['verdict'])", dashboard.charts[8].program_text)

    def test_dashboard_specs_only_include_isovalent_dashboard_when_enabled(self):
        resolved_tests = sample_resolved_tests()

        with_isovalent = dashboards.dashboard_specs(
            resolved_tests=resolved_tests,
            account_group_id="2114135",
            apm_environment="streaming-app",
            rum_app_name="streaming-app-frontend",
            namespace="streaming-service-app",
            broadcast_page_filter="*broadcast*",
            include_isovalent_dashboard=True,
        )
        without_isovalent = dashboards.dashboard_specs(
            resolved_tests=resolved_tests,
            account_group_id="2114135",
            apm_environment="streaming-app",
            rum_app_name="streaming-app-frontend",
            namespace="streaming-service-app",
            broadcast_page_filter="*broadcast*",
            include_isovalent_dashboard=False,
        )

        self.assertEqual(with_isovalent[-1].name, "07 Explain: Are Media Services Still Talking?")
        self.assertFalse(
            any(spec.name == "07 Explain: Are Media Services Still Talking?" for spec in without_isovalent)
        )

    def test_dashboard_specs_render_rtp_placeholder_when_telemetry_is_missing(self):
        resolved_tests = sample_resolved_tests()

        specs = dashboards.dashboard_specs(
            resolved_tests=resolved_tests,
            account_group_id="2114135",
            apm_environment="streaming-app",
            rum_app_name="streaming-app-frontend",
            namespace="streaming-service-app",
            broadcast_page_filter="*broadcast*",
            include_isovalent_dashboard=False,
            rtp_placeholder_description=(
                "RTP test RTP-Stream-Proxy (8405216) is still configured, but Splunk did not return recent telemetry."
            ),
        )

        rtp_dashboard = next(spec for spec in specs if spec.name == "06 Deep Dive: RTP Media Quality")
        self.assertEqual(rtp_dashboard.charts, ())
        self.assertIn("still configured", rtp_dashboard.description)

    def test_summary_description_mentions_plain_language_step_only_when_present(self):
        resolved_tests = sample_resolved_tests()
        configured_slots = {
            slot: tuple(config["default_names"])
            for slot, config in dashboards.TEST_SLOT_CONFIG.items()
        }

        with_isovalent = dashboards.summary_description(
            resolved_tests,
            configured_slots,
            has_isovalent_dashboard=True,
        )
        without_isovalent = dashboards.summary_description(
            resolved_tests,
            configured_slots,
            has_isovalent_dashboard=False,
        )

        self.assertIn("then 07 Explain: Are Media Services Still Talking?", with_isovalent)
        self.assertNotIn("then 07 Explain: Are Media Services Still Talking?", without_isovalent)

    def test_summary_description_mentions_tests_skipped_for_missing_recent_data(self):
        resolved_tests = sample_resolved_tests()
        configured_slots = {
            slot: tuple(config["default_names"])
            for slot, config in dashboards.TEST_SLOT_CONFIG.items()
        }

        summary = dashboards.summary_description(
            resolved_tests,
            configured_slots,
            has_isovalent_dashboard=True,
            tests_without_recent_data=[
                "aleccham-broadcast-playback (8400454) [network.latency, network.loss, network.jitter]",
            ],
        )

        self.assertIn("Present but skipped because Splunk had no recent metric data", summary)
        self.assertIn("aleccham-broadcast-playback (8400454)", summary)

    def test_isovalent_metric_validation_specs_cover_hubble_and_tetragon(self):
        validations = dashboards.isovalent_metric_validation_specs()

        self.assertEqual(
            [(validation.metric, validation.test_name) for validation in validations],
            [
                ("hubble_http_requests_total", "Isovalent Hubble HTTP telemetry"),
                ("tetragon_http_response_total", "Isovalent Tetragon HTTP telemetry"),
                ("tetragon_socket_stats_txbytes_total", "Isovalent Tetragon socket byte telemetry"),
                ("tetragon_socket_stats_rxbytes_total", "Isovalent Tetragon socket receive telemetry"),
            ],
        )
        self.assertIn("data('hubble_http_requests_total')", validations[0].program_text)
        self.assertIn("data('tetragon_http_response_total')", validations[1].program_text)
        self.assertIn("data('tetragon_socket_stats_txbytes_total'", validations[2].program_text)
        self.assertIn("data('tetragon_socket_stats_rxbytes_total'", validations[3].program_text)

    def test_rtp_metric_validation_specs_cover_dashboard_metrics(self):
        validations = dashboards.rtp_metric_validation_specs(sample_resolved_tests())

        self.assertEqual(
            [(validation.metric, validation.test_name, validation.test_id) for validation in validations],
            [
                ("rtp.client.request.mos", "RTP-Stream-Proxy", "8405216"),
                ("rtp.client.request.loss", "RTP-Stream-Proxy", "8405216"),
                ("rtp.client.request.discards", "RTP-Stream-Proxy", "8405216"),
                ("rtp.client.request.duration", "RTP-Stream-Proxy", "8405216"),
                ("rtp.client.request.pdv", "RTP-Stream-Proxy", "8405216"),
            ],
        )

    def test_rtp_placeholder_description_mentions_window_and_stale_visuals(self):
        description = dashboards.rtp_placeholder_dashboard_description(
            "RTP-Stream-Proxy",
            "8405216",
            ["rtp.client.request.loss", "rtp.client.request.pdv"],
            24,
        )

        self.assertIn("rtp.client.request.loss, rtp.client.request.pdv", description)
        self.assertIn("last 24h", description)
        self.assertIn("stale RTP visuals", description)

    def test_validate_metric_data_retries_signalflow_timeout(self):
        validations = [
            dashboards.MetricValidationSpec(
                metric="network.latency",
                test_name="aleccham-broadcast-trace-map",
                test_id="8400453",
            )
        ]
        timeout = dashboards.subprocess.CompletedProcess(
            args=["node", "validator"],
            returncode=1,
            stdout="",
            stderr="Timed out waiting for SignalFlow data for network.latency (8400453).",
        )
        success = dashboards.subprocess.CompletedProcess(
            args=["node", "validator"],
            returncode=0,
            stdout='[{"metric":"network.latency","testId":"8400453","hasData":true}]',
            stderr="",
        )

        with mock.patch.object(dashboards.subprocess, "run", side_effect=[timeout, success]) as run_mock:
            with mock.patch.object(dashboards.time, "sleep") as sleep_mock:
                results = dashboards.validate_metric_data(
                    splunk_realm="us1",
                    validation_token="token",
                    account_group_id="2114135",
                    validations=validations,
                    validation_window_hours=24,
                )

        self.assertEqual(run_mock.call_count, 2)
        sleep_mock.assert_called_once_with(dashboards.SIGNALFLOW_TIMEOUT_RETRY_DELAY_SECONDS)
        self.assertEqual(
            results,
            [{"metric": "network.latency", "testId": "8400453", "hasData": True}],
        )


if __name__ == "__main__":
    unittest.main()
