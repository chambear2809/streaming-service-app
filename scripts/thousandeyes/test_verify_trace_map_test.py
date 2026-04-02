import importlib.util
import os
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_trace_map_test.py")
SPEC = importlib.util.spec_from_file_location("verify_trace_map_test", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load trace-map verifier module from {MODULE_PATH}.")
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class VerifyTraceMapTestTests(unittest.TestCase):
    def test_validation_passes_for_expected_live_config(self):
        errors = module.validation_errors(
            test={
                "testId": "8400453",
                "url": "https://demo.example.com/api/v1/demo/public/trace-map",
                "distributedTracing": True,
                "agents": [{"agentId": "1660330"}, {"agentId": "1660331"}],
            },
            agents_by_id={
                "1660330": {"agentId": "1660330", "agentState": "online"},
                "1660331": {"agentId": "1660331", "agentState": "online"},
            },
            expected_url="https://demo.example.com/api/v1/demo/public/trace-map",
            expected_agent_ids=["1660330", "1660331"],
        )

        self.assertEqual(errors, [])

    def test_validation_fails_when_distributed_tracing_is_disabled(self):
        errors = module.validation_errors(
            test={
                "url": "https://demo.example.com/api/v1/demo/public/trace-map",
                "distributedTracing": False,
                "agents": [{"agentId": "1660330"}],
            },
            agents_by_id={"1660330": {"agentId": "1660330", "agentState": "online"}},
            expected_url="https://demo.example.com/api/v1/demo/public/trace-map",
            expected_agent_ids=["1660330"],
        )

        self.assertIn(
            "Trace-map test must set distributedTracing=true.",
            errors,
        )

    def test_validation_fails_when_configured_source_agent_is_offline(self):
        errors = module.validation_errors(
            test={
                "url": "https://demo.example.com/api/v1/demo/public/trace-map",
                "distributedTracing": True,
                "agents": [{"agentId": "1639905"}],
            },
            agents_by_id={
                "1639905": {
                    "agentId": "1639905",
                    "agentState": "offline",
                    "lastSeen": "2026-04-01T23:16:34Z",
                }
            },
            expected_url="https://demo.example.com/api/v1/demo/public/trace-map",
            expected_agent_ids=["1639905"],
        )

        self.assertIn(
            "Trace-map test has no online assigned agents.",
            errors,
        )
        self.assertIn(
            "Configured source agent 1639905 is offline (last seen 2026-04-01T23:16:34Z).",
            errors,
        )

    def test_validation_fails_when_assigned_agents_drift_from_env(self):
        errors = module.validation_errors(
            test={
                "url": "https://demo.example.com/api/v1/demo/public/trace-map",
                "distributedTracing": True,
                "agents": [{"agentId": "1660330"}],
            },
            agents_by_id={"1660330": {"agentId": "1660330", "agentState": "online"}},
            expected_url="https://demo.example.com/api/v1/demo/public/trace-map",
            expected_agent_ids=["1660330", "1660331"],
        )

        self.assertIn(
            "Trace-map test agents drifted: expected 1660330,1660331, got 1660330.",
            errors,
        )
        self.assertIn(
            "Configured source agent 1660331 is not visible in the account group.",
            errors,
        )

    def test_load_env_file_populates_missing_values_without_overriding_process_env(self):
        original_env = os.environ.copy()
        try:
            env_file = Path(__file__).with_name("verify_trace_map_test.env")
            env_file.write_text(
                "TE_TRACE_MAP_TEST_ID=8400453\n"
                "TE_TRACE_MAP_TEST_URL=https://demo.example.com/api/v1/demo/public/trace-map\n",
                encoding="utf-8",
            )
            os.environ["TE_TRACE_MAP_TEST_ID"] = "existing-value"

            module.load_env_file(env_file)

            self.assertEqual(os.environ["TE_TRACE_MAP_TEST_ID"], "existing-value")
            self.assertEqual(
                os.environ["TE_TRACE_MAP_TEST_URL"],
                "https://demo.example.com/api/v1/demo/public/trace-map",
            )
        finally:
            if env_file.exists():
                env_file.unlink()
            os.environ.clear()
            os.environ.update(original_env)


if __name__ == "__main__":
    unittest.main()
