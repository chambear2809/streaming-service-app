import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("sync-o11y-apm-integration.py")
SPEC = importlib.util.spec_from_file_location("sync_o11y_apm_integration", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load APM sync module from {MODULE_PATH}.")
apm_sync = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = apm_sync
SPEC.loader.exec_module(apm_sync)


class SyncO11yApmIntegrationTests(unittest.TestCase):
    def test_splunk_api_url_prefers_secondary_override_for_rc0(self):
        env_file = {
            "SPLUNK_REALM": "us1",
            "SPLUNK_OTEL_SECONDARY_REALM": "rc0",
            "SPLUNK_OTEL_SECONDARY_API_URL": "https://external-api.rc0.signalfx.com/",
        }

        self.assertEqual(
            apm_sync.splunk_api_url(env_file),
            "https://external-api.rc0.signalfx.com",
        )

    def test_splunk_api_token_prefers_explicit_apm_token_then_secondary(self):
        self.assertEqual(
            apm_sync.splunk_api_token(
                {
                    "THOUSANDEYES_APM_API_TOKEN": "apm-token",
                    "SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN": "secondary-token",
                    "SPLUNK_ACCESS_TOKEN": "primary-token",
                }
            ),
            "apm-token",
        )
        self.assertEqual(
            apm_sync.splunk_api_token(
                {
                    "SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN": "secondary-token",
                    "SPLUNK_ACCESS_TOKEN": "primary-token",
                }
            ),
            "secondary-token",
        )

    def test_splunk_api_token_matches_primary_or_secondary_target(self):
        env_file = {
            "SPLUNK_REALM": "us1",
            "SPLUNK_ACCESS_TOKEN": "primary-token",
            "SPLUNK_OTEL_SECONDARY_REALM": "rc0",
            "SPLUNK_OTEL_SECONDARY_API_URL": "https://external-api.rc0.signalfx.com",
            "SPLUNK_OTEL_SECONDARY_ACCESS_TOKEN": "secondary-token",
        }

        self.assertEqual(
            apm_sync.splunk_api_token(env_file, "https://api.us1.signalfx.com"),
            "primary-token",
        )
        self.assertEqual(
            apm_sync.splunk_api_token(env_file, "https://external-api.rc0.signalfx.com"),
            "secondary-token",
        )

    def test_choose_apm_operation_selects_single_operation_when_unambiguous(self):
        operation = apm_sync.choose_apm_operation(
            {
                "operations": [
                    {"id": "op-1", "name": "Splunk APM", "type": "splunk-observability-apm", "enabled": True}
                ]
            },
            operation_id=None,
            operation_name=None,
        )

        self.assertEqual(operation["id"], "op-1")

    def test_choose_connector_uses_name_and_target_without_overwriting_assigned_connector(self):
        connector = apm_sync.choose_connector(
            [
                {"id": "connector-1", "name": "demo", "target": "https://api.us1.signalfx.com"},
                {"id": "connector-2", "name": "demo", "target": "https://external-api.rc0.signalfx.com"},
            ],
            connector_id=None,
            connector_name="demo",
            target_url="https://external-api.rc0.signalfx.com",
        )

        self.assertEqual(connector["id"], "connector-2")

    def test_choose_connector_selects_explicit_connector_id(self):
        connector = apm_sync.choose_connector(
            [
                {"id": "connector-1", "name": "demo", "target": "https://api.us1.signalfx.com"},
                {"id": "connector-2", "name": "demo-rc0", "target": "https://external-api.rc0.signalfx.com"},
            ],
            connector_id="connector-1",
            connector_name="demo-rc0",
            target_url="https://external-api.rc0.signalfx.com",
        )

        self.assertEqual(connector["id"], "connector-1")

    def test_choose_connector_falls_back_to_unique_target(self):
        connector = apm_sync.choose_connector(
            [
                {"id": "connector-1", "name": "renamed-by-api", "target": "https://api.us1.signalfx.com"},
                {"id": "connector-2", "name": "other", "target": "https://api.eu0.signalfx.com"},
            ],
            connector_id=None,
            connector_name="demo",
            target_url="https://api.us1.signalfx.com",
        )

        self.assertEqual(connector["id"], "connector-1")

    def test_assigned_connector_returns_current_operation_connector(self):
        connector = apm_sync.assigned_connector(
            [
                {"id": "connector-1", "name": "demo", "target": "https://api.us1.signalfx.com"},
                {"id": "connector-2", "name": "demo-rc0", "target": "https://external-api.rc0.signalfx.com"},
            ],
            ["connector-1"],
        )

        self.assertEqual(connector["id"], "connector-1")

    def test_connector_payload_uses_x_sf_token_header(self):
        payload = apm_sync.connector_payload(
            "demo",
            "https://external-api.rc0.signalfx.com/",
            "secret-token",
        )

        self.assertEqual(
            payload,
            {
                "type": "generic",
                "name": "demo",
                "target": "https://external-api.rc0.signalfx.com",
                "headers": [{"name": "X-SF-Token", "value": "secret-token"}],
            },
        )

    def test_redacted_hides_x_sf_token_header_values(self):
        self.assertEqual(
            apm_sync.redacted(
                {
                    "headers": [
                        {"name": "X-SF-Token", "value": "secret-token"},
                        {"name": "Content-Type", "value": "application/json"},
                    ]
                }
            ),
            {
                "headers": [
                    {"name": "X-SF-Token", "value": "<redacted>"},
                    {"name": "Content-Type", "value": "application/json"},
                ]
            },
        )


if __name__ == "__main__":
    unittest.main()
