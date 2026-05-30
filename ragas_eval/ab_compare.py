import json
import os
import re
import subprocess
import sys
from datetime import datetime


BASE_DIR = os.path.dirname(__file__)
DEFAULT_PROFILE_A = os.environ.get("PROFILE_A", "baseline-v1")
DEFAULT_PROFILE_B = os.environ.get("PROFILE_B", "strict-v2")
AB_REPORT_PATH = os.environ.get("AB_REPORT_PATH", os.path.join(BASE_DIR, "ab_report.json"))


def now_ts():
    return datetime.now().isoformat(timespec="seconds")


def slugify(text: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]+", "-", text.strip()).strip("-").lower() or "profile"


def run_one_profile(profile: str):
    slug = slugify(profile)
    report_path = os.path.join(BASE_DIR, f"report_{slug}.json")
    detail_path = os.path.join(BASE_DIR, f"report_detailed_{slug}.json")
    collected_path = os.path.join(BASE_DIR, f"collected_data_{slug}.json")

    env = os.environ.copy()
    env["RAG_EVAL_PROFILE"] = profile
    env["REPORT_PATH"] = report_path
    env["DETAIL_REPORT_PATH"] = detail_path
    env["COLLECTED_DATA_PATH"] = collected_path

    cmd = [sys.executable, os.path.join(BASE_DIR, "evaluate.py")]
    print(f"\n===== Running profile: {profile} =====")
    proc = subprocess.run(cmd, env=env, cwd=BASE_DIR)

    report = {}
    if os.path.exists(report_path):
        with open(report_path, "r", encoding="utf-8") as f:
            report = json.load(f)

    return {
        "profile": profile,
        "exit_code": proc.returncode,
        "report_path": report_path,
        "detail_report_path": detail_path,
        "collected_data_path": collected_path,
        "report": report,
    }


def get_score(run_result: dict, key: str) -> float:
    report = run_result.get("report", {})
    scores = report.get("scores", {})
    try:
        return float(scores.get(key, 0.0))
    except Exception:
        return 0.0


def main():
    profile_a = DEFAULT_PROFILE_A
    profile_b = DEFAULT_PROFILE_B

    result_a = run_one_profile(profile_a)
    result_b = run_one_profile(profile_b)

    metrics = [
        "faithfulness",
        "nv_context_relevance",
        "context_precision",
        "context_recall",
        "answer_relevancy",
    ]
    deltas = {}
    for metric in metrics:
        a = get_score(result_a, metric)
        b = get_score(result_b, metric)
        deltas[metric] = {
            "profile_a": a,
            "profile_b": b,
            "delta_b_minus_a": b - a,
        }

    ab_report = {
        "status": "success",
        "timestamp": now_ts(),
        "profile_a": profile_a,
        "profile_b": profile_b,
        "run_a": {
            "exit_code": result_a["exit_code"],
            "report_path": result_a["report_path"],
            "detail_report_path": result_a["detail_report_path"],
        },
        "run_b": {
            "exit_code": result_b["exit_code"],
            "report_path": result_b["report_path"],
            "detail_report_path": result_b["detail_report_path"],
        },
        "deltas": deltas,
    }

    with open(AB_REPORT_PATH, "w", encoding="utf-8") as f:
        json.dump(ab_report, f, ensure_ascii=False, indent=2)

    print("\n===== A/B Summary =====")
    for metric in metrics:
        d = deltas[metric]
        print(
            f"{metric:22s} A={d['profile_a']:.4f}  B={d['profile_b']:.4f}  Δ={d['delta_b_minus_a']:+.4f}"
        )
    print(f"\nAB report written to: {AB_REPORT_PATH}")


if __name__ == "__main__":
    main()
