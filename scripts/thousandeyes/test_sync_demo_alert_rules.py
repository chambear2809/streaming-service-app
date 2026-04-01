import importlib.util
import os
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("sync-demo-alert-rules.py")
SPEC = importlib.util.spec_from_file_location("sync_demo_alert_rules", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load alert-rule sync module from {MODULE_PATH}.")
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class SyncDemoAlertRulesTests(unittest.TestCase):
    def setUp(self):
        self.original_env = os.environ.copy()

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.original_env)

    def test_rule_payload_uses_story_defaults_and_template_fields(self):
        slot = module.SLOT_DEFINITIONS[0]
        template_rule = {
            "expression": '((probDetail != ""))',
            "roundsViolatingOutOf": 3,
            "roundsViolatingRequired": 2,
            "roundsViolatingMode": "exact",
            "alertGroupType": "cloud-enterprise",
        }

        payload = module.rule_payload(
            slot=slot,
            test_id="8400454",
            template_rule=template_rule,
            notifications=None,
        )

        self.assertEqual(payload["ruleName"], "[demo] playback public path")
        self.assertEqual(payload["severity"], "critical")
        self.assertEqual(payload["minimumSources"], 1)
        self.assertTrue(payload["notifyOnClear"])
        self.assertEqual(payload["testIds"], ["8400454"])
        self.assertEqual(payload["expression"], '((probDetail != ""))')
        self.assertEqual(payload["roundsViolatingMode"], "exact")
        self.assertEqual(payload["alertGroupType"], "cloud-enterprise")

    def test_rule_payload_honors_per_slot_overrides(self):
        os.environ["TE_UDP_ALERT_MINIMUM_SOURCES"] = "2"
        os.environ["TE_UDP_ALERT_RULE_SEVERITY"] = "major"
        os.environ["TE_UDP_ALERT_NOTIFY_ON_CLEAR"] = "false"

        slot = next(defn for defn in module.SLOT_DEFINITIONS if defn.slot == "udp")
        payload = module.rule_payload(
            slot=slot,
            test_id="8399994",
            template_rule={
                "expression": "((loss >= 10%))",
                "roundsViolatingOutOf": 3,
                "roundsViolatingRequired": 2,
                "roundsViolatingMode": "exact",
                "direction": "to-target",
            },
            notifications=None,
        )

        self.assertEqual(payload["minimumSources"], 2)
        self.assertEqual(payload["severity"], "major")
        self.assertFalse(payload["notifyOnClear"])
        self.assertEqual(payload["direction"], "to-target")

    def test_alert_notifications_supports_email_or_raw_json(self):
        os.environ["THOUSANDEYES_ALERT_EMAIL_RECIPIENTS"] = "alice@example.com,bob@example.com"
        os.environ["THOUSANDEYES_ALERT_EMAIL_MESSAGE"] = "Demo alert"
        self.assertEqual(
            module.alert_notifications(),
            {
                "email": {
                    "message": "Demo alert",
                    "recipients": ["alice@example.com", "bob@example.com"],
                }
            },
        )

        os.environ["THOUSANDEYES_ALERT_NOTIFICATIONS_JSON"] = '{"webhook":[{"integrationId":"wb-1234","integrationType":"webhook"}]}'
        self.assertEqual(
            module.alert_notifications(),
            {"webhook": [{"integrationId": "wb-1234", "integrationType": "webhook"}]},
        )

    def test_test_update_payload_replaces_default_rule_assignment(self):
        self.assertEqual(
            module.test_update_payload("123456"),
            {"alertsEnabled": True, "alertRules": ["123456"]},
        )


if __name__ == "__main__":
    unittest.main()
