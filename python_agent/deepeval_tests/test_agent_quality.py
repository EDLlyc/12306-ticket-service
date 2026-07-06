from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


def test_agent_golden_cases_pass_without_external_services() -> None:
    report_path = Path(".run/deepeval-agent-report-test.json")
    command = [
        sys.executable,
        "-m",
        "python_agent.evals.evaluate_deepeval_agent",
        "--out",
        str(report_path),
    ]
    subprocess.run(command, check=True)
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["failed"] == 0
    assert report["passRate"] == 1.0
    assert report["averageScore"] >= 0.9
