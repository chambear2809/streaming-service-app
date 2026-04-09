import importlib.util
import os
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_demo_tests.py")
SPEC = importlib.util.spec_from_file_location("verify_demo_tests", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load demo-test verifier module from {MODULE_PATH}.")
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class VerifyDemoTestsTests(unittest.TestCase):
    def setUp(self):
        self.original_env = os.environ.copy()
        os.environ["TE_SOURCE_AGENT_IDS"] = "1660330,1660331"
        os.environ["TE_TARGET_AGENT_ID"] = "3"
        os.environ["TE_UDP_TARGET_AGENT_ID"] = "3"
        os.environ["TE_TRACE_MAP_TEST_URL"] = "https://demo.example.com/api/v1/demo/public/trace-map"
        os.environ["TE_BROADCAST_TEST_URL"] = "https://demo.example.com/api/v1/demo/public/broadcast/live/index.m3u8"
        os.environ["TE_RTSP_SERVER"] = "rtsp.example.com"
        os.environ["TE_RTSP_PORT"] = "8554"

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.original_env)

    def test_trace_map_validation_passes_for_expected_live_config(self):
        definition = next(item for item in module.TEST_DEFINITIONS if item["slot"] == "trace_map")
        errors = module.validation_errors(
            definition,
            test={
                "testId": "8400453",
                "testName": "aleccham-broadcast-trace-map",
                "type": "http-server",
                "url": "https://demo.example.com/api/v1/demo/public/trace-map",
                "distributedTracing": True,
                "networkMeasurements": True,
                "agents": [{"agentId": "1660330"}, {"agentId": "1660331"}],
            },
            agents_by_id={
                "1660330": {"agentId": "1660330", "agentType": "enterprise", "agentState": "online"},
                "1660331": {"agentId": "1660331", "agentType": "enterprise", "agentState": "online"},
            },
            streams=[
                {
                    "testMatch": [{"id": "8400453"}],
                    "filters": {"testTypes": {"values": ["http-server"]}},
                }
            ],
        )

        self.assertEqual(errors, [])

    def test_playback_validation_fails_when_source_agents_drift_and_assigned_agent_is_offline(self):
        definition = next(item for item in module.TEST_DEFINITIONS if item["slot"] == "broadcast_playback")
        errors = module.validation_errors(
            definition,
            test={
                "testId": "8400454",
                "testName": "aleccham-broadcast-playback",
                "type": "http-server",
                "url": "https://demo.example.com/api/v1/demo/public/broadcast/live/index.m3u8",
                "networkMeasurements": True,
                "agents": [{"agentId": "1639905"}],
            },
            agents_by_id={
                "1639905": {
                    "agentId": "1639905",
                    "agentType": "enterprise",
                    "agentState": "offline",
                    "lastSeen": "2026-04-01T23:16:34Z",
                },
                "1660330": {"agentId": "1660330", "agentType": "enterprise", "agentState": "online"},
                "1660331": {"agentId": "1660331", "agentType": "enterprise", "agentState": "online"},
            },
            streams=[
                {
                    "testMatch": [{"id": "8400454"}],
                    "filters": {"testTypes": {"values": ["http-server"]}},
                }
            ],
        )

        self.assertIn(
            "aleccham-broadcast-playback source agents drifted: expected 1660330,1660331, got 1639905.",
            errors,
        )
        self.assertIn(
            "aleccham-broadcast-playback references offline enterprise source agent 1639905 (last seen 2026-04-01T23:16:34Z).",
            errors,
        )

    def test_rtp_validation_fails_when_configured_target_agent_is_offline(self):
        definition = next(item for item in module.TEST_DEFINITIONS if item["slot"] == "rtp")
        os.environ["TE_TARGET_AGENT_ID"] = "1659237"
        errors = module.validation_errors(
            definition,
            test={
                "testId": "8405216",
                "testName": "RTP-Stream-Proxy",
                "type": "voice",
                "targetAgentId": "1659237",
                "agents": [{"agentId": "1660330"}, {"agentId": "1660331"}],
            },
            agents_by_id={
                "1659237": {
                    "agentId": "1659237",
                    "agentType": "enterprise",
                    "agentState": "offline",
                    "lastSeen": "2026-04-01T23:26:02Z",
                },
                "1660330": {"agentId": "1660330", "agentType": "enterprise", "agentState": "online"},
                "1660331": {"agentId": "1660331", "agentType": "enterprise", "agentState": "online"},
            },
            streams=[
                {
                    "testMatch": [{"id": "8405216"}],
                    "filters": {"testTypes": {"values": ["voice"]}},
                }
            ],
        )

        self.assertIn(
            "Configured target agent 1659237 for RTP-Stream-Proxy is offline (last seen 2026-04-01T23:26:02Z).",
            errors,
        )

    def test_udp_validation_fails_when_no_enabled_metric_stream_covers_the_test(self):
        definition = next(item for item in module.TEST_DEFINITIONS if item["slot"] == "udp")
        errors = module.validation_errors(
            definition,
            test={
                "testId": "8399994",
                "testName": "UDP-Media-Path",
                "type": "agent-to-agent",
                "targetAgentId": "3",
                "port": 5004,
                "agents": [{"agentId": "1660330"}, {"agentId": "1660331"}],
            },
            agents_by_id={
                "1660330": {"agentId": "1660330", "agentType": "enterprise", "agentState": "online"},
                "1660331": {"agentId": "1660331", "agentType": "enterprise", "agentState": "online"},
                "3": {"agentId": "3", "agentType": "cloud"},
            },
            streams=[
                {
                    "testMatch": [{"id": "8400453"}],
                    "filters": {"testTypes": {"values": ["http-server", "voice"]}},
                }
            ],
        )

        self.assertIn(
            "No enabled ThousandEyes OTel metric stream covers UDP-Media-Path (8399994).",
            errors,
        )

    def test_stream_matches_test_respects_explicit_test_match_before_test_type_filters(self):
        stream = {
            "testMatch": [{"id": "8400453"}],
            "filters": {"testTypes": {"values": ["agent-to-server", "voice"]}},
        }

        self.assertTrue(
            module.stream_matches_test(stream, {"testId": "8400453", "type": "http-server"})
        )
        self.assertFalse(
            module.stream_matches_test(stream, {"testId": "8399994", "type": "agent-to-agent"})
        )


if __name__ == "__main__":
    unittest.main()
