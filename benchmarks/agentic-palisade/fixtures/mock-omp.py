#!/usr/bin/env python3
"""Deterministic OMP v3 fixture; never performs a model call."""

import json
import os
from pathlib import Path
import subprocess
import sys
import time


CASES = {
    (1, "baseline"): "conforming",
    (1, "harness"): "uncompilable",
    (2, "baseline"): "timeout",
    (2, "harness"): "malformed-omp-export",
    (3, "baseline"): "missing-capture",
    (3, "harness"): "leaked-child",
}
FIXTURE_ROOT = Path(__file__).resolve().parent


def apply_patch(name):
    completed = subprocess.run(
        ["patch", "--batch", "--forward", "-p1", "--input", str(FIXTURE_ROOT / name)],
        cwd=Path.cwd(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"fixture patch failed: {completed.stderr.strip()}")


def write_session(path, case):
    events = [
        {"type": "session", "version": 3, "id": f"qualification-{case}",
         "timestamp": "2026-07-29T00:00:00Z", "cwd": "/synthetic-candidate"},
        {"type": "message", "id": "assistant-1", "message": {
            "role": "assistant", "content": [
                {"type": "toolCall", "id": "edit-1", "name": "edit",
                 "arguments": {"path": "src/main/java/benchmark/palisade/SkirmishConfigurationUi.java"}},
                {"type": "toolCall", "id": "build-1", "name": "bash",
                 "arguments": {"command": "./gradlew classes"}},
                {"type": "toolCall", "id": "capture-1", "name": "bash",
                 "arguments": {"command": "ui_screenshot qualification"}}],
            "usage": {"input": 100, "output": 20, "cacheRead": 10, "cacheWrite": 2,
                      "reasoningTokens": 5, "totalTokens": 137}}},
        {"type": "message", "id": "result-edit", "message": {
            "role": "toolResult", "toolCallId": "edit-1", "toolName": "edit",
            "isError": False, "content": []}},
        {"type": "message", "id": "result-build", "message": {
            "role": "toolResult", "toolCallId": "build-1", "toolName": "bash",
            "isError": case == "uncompilable", "content": []}},
        {"type": "message", "id": "result-capture", "message": {
            "role": "toolResult", "toolCallId": "capture-1", "toolName": "bash",
            "isError": case == "missing-capture", "content": []}},
    ]
    if case == "malformed-omp-export":
        path.write_text(json.dumps(events[0]) + "\n{\"type\":\"message\"", encoding="utf-8")
    else:
        path.write_text("".join(json.dumps(event, sort_keys=True) + "\n" for event in events),
                        encoding="utf-8")


def main():
    pair = int(os.environ["BENCHMARK_PAIR"])
    treatment = os.environ["BENCHMARK_TREATMENT"]
    case = CASES[(pair, treatment)]
    patch_name = "broken-candidate.patch" if case in {
        "uncompilable", "malformed-omp-export"} else "good-candidate.patch"
    apply_patch(patch_name)

    if case == "uncompilable":
        broken = Path("src/main/java/benchmark/palisade/QualificationCompileFailure.java")
        broken.write_text("package benchmark.palisade; public class QualificationCompileFailure { not Java }\n",
                          encoding="utf-8")
    elif case == "missing-capture":
        marker = Path("src/main/resources/missing-capture.fixture")
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text("deterministic\n", encoding="utf-8")

    gate = os.environ["BENCHMARK_ROUND_GATE"]
    for round_number in (1, 2, 3):
        completed = subprocess.run([gate, str(round_number)], cwd=Path.cwd())
        if completed.returncode != 0:
            return completed.returncode

    sessions = Path(os.environ["BENCHMARK_SESSION_ROOT"])
    sessions.mkdir(parents=True, exist_ok=True)
    write_session(sessions / "session.jsonl", case)

    if case == "timeout":
        time.sleep(30)
    elif case == "leaked-child":
        child = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(30)"],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL)
        (Path(os.environ["BENCHMARK_ARTIFACT_ROOT"]) / "leaked-child.pid").write_text(
            str(child.pid), encoding="ascii")
    print(json.dumps({"type": "result", "case": case, "ok": True}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
