import importlib.util
import sys
import unittest
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
