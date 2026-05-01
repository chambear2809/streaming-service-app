import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("sync-o11y-metric-stream.py")
SPEC = importlib.util.spec_from_file_location("sync_o11y_metric_stream", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load stream sync module from {MODULE_PATH}.")
stream_sync = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = stream_sync
SPEC.loader.exec_module(stream_sync)


class SyncO11yMetricStreamTests(unittest.TestCase):
    def test_build_endpoint_url_uses_http_datapoint_path(self):
        self.assertEqual(
            stream_sync.build_endpoint_url("http", "us1", None),
            "https://ingest.us1.signalfx.com/v2/datapoint/otlp",
        )
        self.assertEqual(
            stream_sync.build_endpoint_url("grpc", "eu0", None),
            "https://ingest.eu0.signalfx.com:443",
        )

    def test_choose_target_stream_prefers_unique_highest_overlap(self):
        desired = {"8400453", "8400454", "8399994", "8405216", "8399993"}
        candidates = [
            {"id": "stream-a", "testMatch": [{"id": "8400453", "domain": "cea"}]},
            {
                "id": "stream-b",
                "testMatch": [
                    {"id": "8400453", "domain": "cea"},
                    {"id": "8400454", "domain": "cea"},
                    {"id": "8399994", "domain": "cea"},
                    {"id": "8405216", "domain": "cea"},
                ],
            },
            {"id": "stream-c", "testMatch": []},
        ]

        chosen = stream_sync.choose_target_stream(candidates, desired)

        self.assertIsNotNone(chosen)
        self.assertEqual(chosen["id"], "stream-b")

    def test_build_payload_expands_existing_type_filters_and_unions_test_match(self):
        existing = {
            "id": "stream-1",
            "tagMatch": [{"key": "team", "value": "demo"}],
            "filters": {"testTypes": {"values": ["dns-server", "agent-to-server"]}},
            "testMatch": [
                {"id": "8400453", "domain": "cea"},
                {"id": "9999999", "domain": "cea"},
            ],
        }
        resolved = [
            stream_sync.DesiredTest(slot="trace_map", name="trace", test_id="8400453"),
            stream_sync.DesiredTest(slot="broadcast_playback", name="broadcast", test_id="8400454"),
            stream_sync.DesiredTest(slot="rtp", name="rtp", test_id="8405216"),
            stream_sync.DesiredTest(slot="udp", name="udp", test_id="8399994"),
        ]

        payload = stream_sync.build_payload(
            existing_stream=existing,
            resolved_tests=resolved,
            endpoint_url="https://ingest.us1.signalfx.com/v2/datapoint/otlp",
            endpoint_type="http",
            splunk_token="secret",
            enabled=True,
            signal="metric",
            data_model_version="v2",
            content_type="application/x-protobuf",
        )

        self.assertEqual(payload["tagMatch"], existing["tagMatch"])
        self.assertEqual(
            payload["filters"],
            {
                "testTypes": {
                    "values": [
                        "dns-server",
                        "agent-to-server",
                        "http-server",
                        "voice",
                        "agent-to-agent",
                    ]
                }
            },
        )
        self.assertEqual(
            payload["testMatch"],
            [
                {"id": "8400453", "domain": "cea"},
                {"id": "9999999", "domain": "cea"},
                {"id": "8400454", "domain": "cea"},
                {"id": "8405216", "domain": "cea"},
                {"id": "8399994", "domain": "cea"},
            ],
        )
        self.assertEqual(payload["customHeaders"]["X-SF-Token"], "secret")

    def test_build_payload_does_not_add_type_filter_to_new_exact_stream(self):
        payload = stream_sync.build_payload(
            existing_stream=None,
            resolved_tests=[
                stream_sync.DesiredTest(slot="trace_map", name="trace", test_id="8400453"),
                stream_sync.DesiredTest(slot="rtp", name="rtp", test_id="8405216"),
            ],
            endpoint_url="https://ingest.us1.signalfx.com/v2/datapoint/otlp",
            endpoint_type="http",
            splunk_token="secret",
            enabled=True,
            signal="metric",
            data_model_version="v2",
            content_type="application/x-protobuf",
        )

        self.assertNotIn("filters", payload)

    def test_build_update_payload_omits_create_only_fields(self):
        payload = {
            "type": "opentelemetry",
            "signal": "metric",
            "endpointType": "http",
            "dataModelVersion": "v2",
            "streamEndpointUrl": "https://ingest.us1.signalfx.com/v2/datapoint/otlp",
            "customHeaders": {"X-SF-Token": "secret"},
            "enabled": True,
            "testMatch": [{"id": "8400453", "domain": "cea"}],
        }

        update_payload = stream_sync.build_update_payload(payload)

        self.assertNotIn("type", update_payload)
        self.assertNotIn("signal", update_payload)
        self.assertNotIn("endpointType", update_payload)
        self.assertNotIn("dataModelVersion", update_payload)
        self.assertEqual(
            update_payload,
            {
                "streamEndpointUrl": "https://ingest.us1.signalfx.com/v2/datapoint/otlp",
                "customHeaders": {"X-SF-Token": "secret"},
                "enabled": True,
                "testMatch": [{"id": "8400453", "domain": "cea"}],
            },
        )

    def test_env_value_prefers_explicit_ingest_token_when_present(self):
        env_file = {
            "THOUSANDEYES_O11Y_INGEST_TOKEN": "ingest-token",
            "SPLUNK_ACCESS_TOKEN": "api-token",
        }

        token = (
            stream_sync.env_value(env_file, "THOUSANDEYES_O11Y_INGEST_TOKEN")
            or stream_sync.env_value(env_file, "SPLUNK_ACCESS_TOKEN")
        )

        self.assertEqual(token, "ingest-token")


if __name__ == "__main__":
    unittest.main()
