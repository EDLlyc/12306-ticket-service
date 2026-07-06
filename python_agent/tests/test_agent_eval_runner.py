import unittest
from argparse import Namespace
from unittest.mock import patch

from python_agent.evals.evaluate_deepeval_agent import DEFAULT_CASES, ZHIPU_OPENAI_BASE_URL, build_judge_expected_output, resolve_judge_config, run_eval


class AgentEvalRunnerTest(unittest.IsolatedAsyncioTestCase):
    async def test_agent_golden_eval_passes_without_deepeval_judge(self):
        report = await run_eval(DEFAULT_CASES, enable_deepeval_judge=False)

        self.assertEqual(report["caseCount"], 16)
        self.assertEqual(report["failed"], 0)
        self.assertEqual(report["passRate"], 1.0)
        self.assertGreaterEqual(report["averageScore"], 0.9)

    async def test_agent_golden_eval_uses_deepeval_judge_runner_when_enabled(self):
        calls = []

        def fake_judge(case, answer, trace):
            calls.append((case["id"], answer, trace["goal"]))
            return {
                "enabled": True,
                "metric": "fake.GEval",
                "score": 0.9,
                "reason": "fake pass",
            }

        report = await run_eval(
            DEFAULT_CASES,
            enable_deepeval_judge=True,
            judge_runner=fake_judge,
        )

        self.assertEqual(len(calls), report["caseCount"])
        self.assertEqual(report["failed"], 0)
        self.assertTrue(all(result["judge"]["enabled"] for result in report["results"]))

    async def test_agent_golden_eval_fails_when_required_judge_unavailable(self):
        def unavailable_judge(case, answer, trace):
            return {
                "enabled": False,
                "reason": "not_configured",
            }

        report = await run_eval(
            DEFAULT_CASES,
            enable_deepeval_judge=True,
            require_deepeval_judge=True,
            judge_runner=unavailable_judge,
        )

        self.assertEqual(report["failed"], report["caseCount"])
        self.assertTrue(all(not result["checks"]["deepeval_judge_available"] for result in report["results"]))

    def test_judge_expected_output_includes_tool_trace_summary(self):
        expected = build_judge_expected_output(
            {
                "expected_output": "结合车次和天气给出建议",
                "expected_goal": "travel_advice",
                "expected_tool": "travel_advice",
                "expected_args": {"fromStation": "北京", "toStation": "上海"},
                "answer_must_contain": ["出行建议"],
            },
            {
                "goal": "travel_advice",
                "steps": [
                    {
                        "type": "TOOL",
                        "tool": "travel_advice",
                        "args": {"fromStation": "北京", "toStation": "上海"},
                    }
                ],
                "finalObservation": {
                    "code": "TRAVEL_ADVICE_READY",
                    "message": "出行建议...",
                    "data": {"matchCount": 1},
                },
            },
        )

        self.assertIn("tool_trace_summary", expected)
        self.assertIn("TRAVEL_ADVICE_READY", expected)

    def test_resolve_zhipu_judge_config_from_env(self):
        args = Namespace(
            judge_provider="zhipu",
            judge_model=None,
            judge_base_url=None,
            judge_api_key=None,
        )
        with patch.dict(
            "os.environ",
            {
                "ZHIPU_API_KEY": "zhipu-test-key",
                "ZHIPU_POLICY_MODEL": "glm-4.5-air",
            },
            clear=False,
        ):
            config = resolve_judge_config(args)

        self.assertEqual(config["provider"], "zhipu")
        self.assertEqual(config["api_key"], "zhipu-test-key")
        self.assertEqual(config["model"], "glm-4.5-air")
        self.assertEqual(config["base_url"], ZHIPU_OPENAI_BASE_URL)


if __name__ == "__main__":
    unittest.main()
