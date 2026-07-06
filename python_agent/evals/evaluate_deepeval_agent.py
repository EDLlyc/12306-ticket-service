from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass
from datetime import datetime
import json
import os
from pathlib import Path
from typing import Any

from python_agent.app.agent_service import PythonActionAgentService
from python_agent.app.planner import ActionPlanner
from python_agent.app.schemas import AskRequest, JavaResult
from python_agent.app.settings import Settings
from python_agent.app.tool_executor import ToolExecutor
from python_agent.app.weather_mcp import WeatherMcpClient


DEFAULT_CASES = Path("python_agent/evals/agent_golden_cases.jsonl")
DEFAULT_REPORT = Path(".run/deepeval-agent-report.json")
ZHIPU_OPENAI_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/"


class EvalJavaClient:
    async def login(self, username: str) -> JavaResult:
        return JavaResult(code=200, message="ok", data=f"token-{username}")

    async def query_train(self, train_number: str) -> JavaResult:
        return JavaResult(code=200, message="ok", data=f"{train_number} 余票 8 张")

    async def list_trains(self) -> list[dict[str, Any]]:
        return [
            {
                "trainNumber": "G1",
                "startStation": "北京",
                "endStation": "上海",
                "startTime": "2026-07-06T08:00:00",
                "endTime": "2026-07-06T12:30:00",
                "availableStock": 8,
            },
            {
                "trainNumber": "G2",
                "startStation": "南京",
                "endStation": "杭州",
                "startTime": "2026-07-06T09:30:00",
                "endTime": "2026-07-06T11:00:00",
                "availableStock": 3,
            },
        ]

    async def book_ticket(self, train_number: str, token: str) -> JavaResult:
        return JavaResult(code=200, message="ok", data=f"已受理 {train_number} {token}")

    async def query_orders(self, token: str) -> JavaResult:
        return JavaResult(
            code=200,
            message="ok",
            data=[
                {
                    "order_sn": "11111111-1111-1111-1111-111111111111",
                    "train_number": "G1",
                    "status": "PENDING_PAYMENT",
                }
            ],
        )

    async def refund_order(self, order_sn: str, token: str) -> JavaResult:
        return JavaResult(code=200, message="ok", data=f"已退票 {order_sn}")


@dataclass(frozen=True)
class CaseResult:
    case_id: str
    question: str
    passed: bool
    score: float
    checks: dict[str, bool]
    answer: str
    trace: dict[str, Any]
    judge: dict[str, Any] | None = None


async def run_eval(
    cases_path: Path,
    *,
    enable_deepeval_judge: bool,
    require_deepeval_judge: bool = False,
    judge_runner=None,
    judge_config: dict[str, Any] | None = None,
) -> dict[str, Any]:
    cases = load_cases(cases_path)
    service = PythonActionAgentService(
        ActionPlanner(),
        ToolExecutor(EvalJavaClient(), WeatherMcpClient(Settings())),
    )
    results: list[CaseResult] = []
    for case in cases:
        request = AskRequest(
            session_id=f"eval-{case['id']}",
            username=case.get("username"),
            question=case["question"],
        )
        agent_result = await service.answer(request)
        first_turn_answer = agent_result.answer
        first_turn_trace = agent_result.trace()
        if case.get("follow_up_question"):
            request = AskRequest(
                session_id=f"eval-{case['id']}",
                username=case.get("username"),
                question=case["follow_up_question"],
            )
            agent_result = await service.answer(request)
        trace = agent_result.trace()
        trace["_firstTurn"] = first_turn_trace
        trace["_firstAnswer"] = first_turn_answer
        checks = deterministic_checks(case, trace, agent_result.answer)
        score = sum(1 for passed in checks.values() if passed) / max(len(checks), 1)
        judge = (
            judge_runner(case, agent_result.answer, trace)
            if enable_deepeval_judge and judge_runner is not None
            else run_deepeval_judge(case, agent_result.answer, trace, judge_config or {})
            if enable_deepeval_judge
            else None
        )
        if judge is not None and judge.get("enabled") and "score" in judge:
            score = round((score + float(judge["score"])) / 2, 4)
        if require_deepeval_judge and (judge is None or not judge.get("enabled")):
            checks["deepeval_judge_available"] = False
        results.append(
            CaseResult(
                case_id=case["id"],
                question=case["question"],
                passed=score >= 0.8
                and all_required_checks_passed(checks)
                and (not require_deepeval_judge or checks.get("deepeval_judge_available", True)),
                score=round(score, 4),
                checks=checks,
                answer=agent_result.answer,
                trace=trace,
                judge=judge,
            )
        )

    passed = sum(1 for result in results if result.passed)
    judge_available = sum(1 for result in results if result.judge is not None and result.judge.get("enabled"))
    return {
        "createdAt": datetime.now().isoformat(timespec="seconds"),
        "framework": "deepeval_optional_with_deterministic_agent_metrics",
        "deepevalJudgeEnabled": enable_deepeval_judge,
        "deepevalJudgeRequired": require_deepeval_judge,
        "deepevalJudgeAvailableCount": judge_available,
        "caseCount": len(results),
        "passed": passed,
        "failed": len(results) - passed,
        "passRate": round(passed / max(len(results), 1), 4),
        "averageScore": round(sum(result.score for result in results) / max(len(results), 1), 4),
        "results": [result.__dict__ for result in results],
    }


def deterministic_checks(case: dict[str, Any], trace: dict[str, Any], answer: str) -> dict[str, bool]:
    first_tool_step = first_tool(trace)
    expected_tool = case.get("expected_tool")
    checks = {
        "goal": trace.get("goal") == case.get("expected_goal"),
        "tool": tool_matches(expected_tool, first_tool_step),
        "args": expected_args_match(case.get("expected_args") or {}, first_tool_step.get("args") or {}),
        "answer_contains": all(fragment in answer for fragment in case.get("answer_must_contain", [])),
    }
    forbidden_tools = set(case.get("forbidden_tools", []))
    if forbidden_tools:
        checks["forbidden_tools_not_called"] = not any_tool_called(trace, forbidden_tools)
    if case.get("requires_confirmation"):
        checks["confirmation_required"] = first_turn_code(trace) == "CONFIRM_REQUIRED"
    if case.get("requires_mcp"):
        checks["mcp_trace"] = has_mcp_trace(trace)
    if case.get("forbidden_duplicate_side_effect"):
        checks["no_duplicate_side_effect"] = not has_duplicate_side_effect_block(trace)
    return checks


def first_tool(trace: dict[str, Any]) -> dict[str, Any]:
    for step in trace.get("steps", []):
        if step.get("type") == "TOOL":
            return step
    return {}


def first_turn_code(trace: dict[str, Any]) -> str | None:
    first_turn = trace.get("_firstTurn")
    if not isinstance(first_turn, dict):
        return None
    final_observation = first_turn.get("finalObservation")
    if not isinstance(final_observation, dict):
        return None
    return final_observation.get("code")


def tool_matches(expected_tool: Any, first_tool_step: dict[str, Any]) -> bool:
    if expected_tool is None:
        return True
    if str(expected_tool) in {"", "none", "None", "NO_TOOL"}:
        return not first_tool_step
    return first_tool_step.get("tool") == expected_tool


def any_tool_called(trace: dict[str, Any], tool_names: set[str]) -> bool:
    return any(
        step.get("type") == "TOOL" and step.get("tool") in tool_names
        for step in trace.get("steps", [])
        if isinstance(step, dict)
    )


def expected_args_match(expected: dict[str, Any], actual: dict[str, Any]) -> bool:
    for key, expected_value in expected.items():
        if str(actual.get(key)) != str(expected_value):
            return False
    return True


def has_mcp_trace(trace: dict[str, Any]) -> bool:
    final_observation = trace.get("finalObservation") or {}
    data = final_observation.get("data") if isinstance(final_observation, dict) else {}
    tools = ((data or {}).get("mcp") or {}).get("tools") if isinstance(data, dict) else None
    return isinstance(tools, list) and any(tool.get("method") == "tools/call" for tool in tools if isinstance(tool, dict))


def has_duplicate_side_effect_block(trace: dict[str, Any]) -> bool:
    return any(
        ((step.get("observation") or {}).get("code") == "DUPLICATE_SIDE_EFFECT_STEP")
        for step in trace.get("steps", [])
        if isinstance(step, dict)
    )


def all_required_checks_passed(checks: dict[str, bool]) -> bool:
    return all(checks.values())


def run_deepeval_judge(
    case: dict[str, Any],
    answer: str,
    trace: dict[str, Any],
    judge_config: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    try:
        from deepeval.metrics import GEval
        from deepeval.test_case import LLMTestCase
        from deepeval.models import GPTModel
        try:
            from deepeval.test_case import SingleTurnParams as EvalParams
        except ImportError:
            from deepeval.test_case import LLMTestCaseParams as EvalParams
    except ImportError:
        return {"enabled": False, "reason": "deepeval_not_installed"}

    judge_config = judge_config or {}
    provider = str(judge_config.get("provider") or "openai")
    judge_expected_output = build_judge_expected_output(case, trace)
    try:
        model = build_judge_model(GPTModel, judge_config)
        metric = GEval(
            name="AgentTaskSuccess",
            criteria=(
                "You are judging a 12306 travel Agent. Score whether the actual output completes the user's task, "
                "is faithful to the provided tool trace, correctly reflects tool observations, avoids unsupported claims, "
                "and gives practical Chinese travel guidance when the task asks for advice."
            ),
            evaluation_params=[
                EvalParams.INPUT,
                EvalParams.ACTUAL_OUTPUT,
                EvalParams.EXPECTED_OUTPUT,
            ],
            model=model,
        )
        test_case = LLMTestCase(
            input=case["question"],
            actual_output=answer,
            expected_output=judge_expected_output,
        )
        metric.measure(test_case)
        return {
            "enabled": True,
            "metric": "GEval.AgentTaskSuccess",
            "provider": provider,
            "model": judge_config.get("model"),
            "baseUrl": judge_config.get("base_url"),
            "score": metric.score,
            "reason": metric.reason,
        }
    except Exception as exc:
        return {
            "enabled": False,
            "reason": "deepeval_judge_error",
            "provider": provider,
            "model": judge_config.get("model"),
            "baseUrl": judge_config.get("base_url"),
            "error": str(exc),
        }


def build_judge_model(gpt_model_cls, judge_config: dict[str, Any]):
    provider = str(judge_config.get("provider") or "openai").lower()
    model = judge_config.get("model")
    api_key = judge_config.get("api_key")
    base_url = judge_config.get("base_url")
    if provider == "zhipu":
        if not api_key:
            raise ValueError("ZHIPU_API_KEY is required for --judge-provider zhipu")
        return gpt_model_cls(
            model=str(model or "glm-4.5-air"),
            api_key=str(api_key),
            base_url=str(base_url or ZHIPU_OPENAI_BASE_URL),
            temperature=0,
        )
    if provider == "openai-compatible":
        if not api_key:
            raise ValueError("OPENAI_API_KEY or --judge-api-key is required for openai-compatible judge")
        return gpt_model_cls(
            model=str(model or "gpt-4o-mini"),
            api_key=str(api_key),
            base_url=str(base_url),
            temperature=0,
        )
    if model or api_key or base_url:
        return gpt_model_cls(
            model=str(model) if model else None,
            api_key=str(api_key) if api_key else None,
            base_url=str(base_url) if base_url else None,
            temperature=0,
        )
    return None


def resolve_judge_config(args: argparse.Namespace) -> dict[str, Any]:
    provider = (args.judge_provider or os.getenv("DEEPEVAL_JUDGE_PROVIDER") or "openai").lower()
    model = args.judge_model or os.getenv("DEEPEVAL_JUDGE_MODEL")
    base_url = args.judge_base_url or os.getenv("DEEPEVAL_JUDGE_BASE_URL")
    api_key = args.judge_api_key or os.getenv("DEEPEVAL_JUDGE_API_KEY")
    if provider == "zhipu":
        model = model or os.getenv("ZHIPU_POLICY_MODEL") or os.getenv("ZHIPU_AGENT_MODEL") or "glm-4.5-air"
        base_url = base_url or os.getenv("ZHIPU_OPENAI_BASE_URL") or ZHIPU_OPENAI_BASE_URL
        api_key = api_key or os.getenv("ZHIPU_API_KEY")
    elif provider == "openai":
        api_key = api_key or os.getenv("OPENAI_API_KEY")
        base_url = base_url or os.getenv("OPENAI_BASE_URL")
    elif provider == "openai-compatible":
        api_key = api_key or os.getenv("OPENAI_API_KEY")
        base_url = base_url or os.getenv("OPENAI_BASE_URL")
    return {
        "provider": provider,
        "model": model,
        "base_url": base_url,
        "api_key": api_key,
    }


def build_judge_expected_output(case: dict[str, Any], trace: dict[str, Any]) -> str:
    first_tool_step = first_tool(trace)
    final_observation = trace.get("finalObservation") or {}
    final_data = final_observation.get("data") if isinstance(final_observation, dict) else None
    payload = {
        "expected_task": case.get("expected_output", ""),
        "expected_goal": case.get("expected_goal"),
        "expected_tool": case.get("expected_tool"),
        "expected_args": case.get("expected_args", {}),
        "required_answer_fragments": case.get("answer_must_contain", []),
        "tool_trace_summary": {
            "goal": trace.get("goal"),
            "selected_tool": first_tool_step.get("tool"),
            "selected_args": first_tool_step.get("args"),
            "final_tool_code": final_observation.get("code") if isinstance(final_observation, dict) else None,
            "final_tool_message": final_observation.get("message") if isinstance(final_observation, dict) else None,
            "final_tool_data": final_data,
        },
        "judge_rules": [
            "Do not reward answers that contradict the tool trace.",
            "Do not require information outside the expected task.",
            "For weather travel-advice cases, check whether the answer combines train availability and origin/destination weather risk.",
            "For side-effect cases, check whether the answer reflects only one intended action and does not imply duplicate execution.",
        ],
    }
    return json.dumps(payload, ensure_ascii=False, default=str)


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(json.loads(line))
    return cases


def write_report(report: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate Python Agent golden cases with deterministic metrics and optional DeepEval judge.")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--out", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--deepeval-judge", action="store_true", help="Enable optional DeepEval G-Eval judge. Requires deepeval and a configured judge model.")
    parser.add_argument("--require-deepeval-judge", action="store_true", help="Fail if the DeepEval judge is unavailable or errors.")
    parser.add_argument("--judge-provider", choices=("openai", "zhipu", "openai-compatible"), default=None)
    parser.add_argument("--judge-model", default=None)
    parser.add_argument("--judge-base-url", default=None)
    parser.add_argument("--judge-api-key", default=None)
    return parser.parse_args()


async def async_main() -> None:
    args = parse_args()
    report = await run_eval(
        args.cases,
        enable_deepeval_judge=args.deepeval_judge or args.require_deepeval_judge,
        require_deepeval_judge=args.require_deepeval_judge,
        judge_config=resolve_judge_config(args),
    )
    write_report(report, args.out)
    print(json.dumps({key: report[key] for key in ("caseCount", "passed", "failed", "passRate", "averageScore")}, ensure_ascii=False))
    print(f"report: {args.out}")
    if report["failed"] > 0:
        raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(async_main())
