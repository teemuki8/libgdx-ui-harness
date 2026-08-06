#!/usr/bin/env python3
"""Run the hidden Palisade evaluator over every completed run in a run root.

Each run directory must contain ``run-record.json`` and a frozen workspace
under ``repository/`` (as produced by ``run-benchmark.py --execute-prepared``).
For every run without an existing ``evaluation/evaluation.json`` the evaluator
is invoked on a private Xvfb display, writing ``runs/<id>/evaluation/``
(evaluation.json, evaluation.sha256, captures/, evidence/) exactly as
``build-blind-review.py`` and the repeatability gate consume them.

The evaluator is the installed distribution under
``benchmarks/agentic-palisade/evaluator/build/install/agentic-palisade-evaluator``;
rebuild it with ``(cd evaluator && ../gradlew installDist)`` if missing.
"""

import argparse
import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
BENCHMARK_ROOT = SCRIPT_DIR.parent
EVALUATOR_BIN = (
    BENCHMARK_ROOT / "evaluator/build/install/agentic-palisade-evaluator/bin"
    / "agentic-palisade-evaluator")


def evaluator_command(run_root, run_dir, manifest_path, run_id):
    return [
        str(EVALUATOR_BIN), "evaluate",
        "--benchmark-manifest", str(manifest_path),
        "--candidate", str(run_dir / "repository/benchmarks/agentic-palisade/template"),
        "--corpus", str(run_root / "corpus"),
        "--output", str(run_dir / "evaluation"),
        "--candidate-id", run_id,
    ]


def evaluate_one(run_root, run_dir, manifest_path, run_id):
    evaluation_dir = run_dir / "evaluation"
    if (evaluation_dir / "evaluation.json").exists():
        return {"runId": run_id, "status": "already-evaluated"}
    environment = {
        "PALISADE_GRADLE": str(run_dir / "repository/gradlew"),
        "GRADLE_USER_HOME": str(run_dir / "cache/gradle"),
        "JAVA_TOOL_OPTIONS": "",
    }
    try:
        completed = subprocess.run(
            ["xvfb-run", "-a", "-s", "-screen 0 1920x1080x24", *evaluator_command(
                run_root, run_dir, manifest_path, run_id)],
            capture_output=True, text=True, timeout=30 * 60, env=environment)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"runId": run_id, "status": "launch-failed", "error": str(error)}
    if completed.returncode != 0:
        return {"runId": run_id, "status": "evaluator-failed",
                "error": completed.stderr.strip()[-500:]}
    try:
        evaluation = json.loads(
            (evaluation_dir / "evaluation.json").read_text(encoding="utf-8"))
        return {
            "runId": run_id,
            "status": evaluation.get("status"),
            "functional": (
                f"{evaluation['functional']['passed']}/"
                f"{evaluation['functional']['total']}"),
            "visual": len(evaluation.get("visual", [])),
            "structural": len(evaluation.get("structural", [])),
        }
    except (OSError, json.JSONDecodeError, KeyError) as error:
        return {"runId": run_id, "status": "unreadable-evaluation",
                "error": str(error)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=6)
    arguments = parser.parse_args(argv)
    try:
        run_root = arguments.run_root.resolve()
        manifest_path = run_root / "benchmark-manifest.json"
        if not manifest_path.is_file():
            raise ValueError(f"no prepared benchmark manifest at {manifest_path}")
        run_dirs = sorted(run_root.glob("runs/*/"))
        records = []
        for run_dir in run_dirs:
            record_path = run_dir / "run-record.json"
            if record_path.is_file():
                record = json.loads(record_path.read_text(encoding="utf-8"))
                records.append((run_dir, record["runId"]))
        if not records:
            raise ValueError(f"no completed runs under {run_root / 'runs'}")
        results = []
        with ThreadPoolExecutor(max_workers=arguments.jobs,
                                thread_name_prefix="palisade-evaluate") as executor:
            futures = {
                executor.submit(evaluate_one, run_root, run_dir, manifest_path, run_id)
                for run_dir, run_id in records}
            for future in futures:
                results.append(future.result())
        results.sort(key=lambda item: item["runId"])
        print(json.dumps({"evaluated": results, "total": len(results)}))
        return 0 if all(item["status"] not in (
            "launch-failed", "evaluator-failed", "unreadable-evaluation")
            for item in results) else 1
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"evaluate-runs: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
