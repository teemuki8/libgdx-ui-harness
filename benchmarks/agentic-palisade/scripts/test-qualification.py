#!/usr/bin/env python3
"""End-to-end qualification contract for the synthetic benchmark pipeline."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
QUALIFIER = HERE / "qualify-pipeline.py"


def load_qualifier():
    spec = importlib.util.spec_from_file_location(
        "qualification_pipeline", QUALIFIER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


QUALIFICATION = load_qualifier()
EXPECTED_CASES = {
    "conforming": ("success", "complete"),
    "uncompilable": ("success", "compile-failed"),
    "timeout": ("timed_out", "complete"),
    "malformed-omp-export": ("telemetry_failure", "complete"),
    "missing-capture": ("success", "runtime-failed"),
    "leaked-child": ("round_supervisor_failure", "complete"),
}


class QualificationTest(unittest.TestCase):
    def test_channel_continuity_rejects_a_drop_from_every_final_channel(self):
        run_ids = [f"run-{index}" for index in range(6)]
        report = {"channels": {}}
        for channel in QUALIFICATION.FINAL_CHANNELS:
            raw = []
            for run_id in run_ids:
                item = {"runId": run_id}
                if channel in ("automatedVisual", "structuralUsability"):
                    item["outcomes"] = []
                elif channel == "telemetryTreatment":
                    item["metrics"] = {
                        name: None for name in QUALIFICATION.TOKEN_METRICS}
                raw.append(item)
            report["channels"][channel] = {"raw": raw}
        for channel in QUALIFICATION.FINAL_CHANNELS:
            with self.subTest(channel=channel):
                mutated = copy.deepcopy(report)
                mutated["channels"][channel]["raw"].pop()
                with self.assertRaisesRegex(ValueError, channel):
                    QUALIFICATION.validate_final_channels(
                        mutated, set(run_ids))

    def test_rejected_input_retention_detects_destructive_handling(self):
        with tempfile.TemporaryDirectory() as temporary:
            tampered = Path(temporary) / "human-ratings.json"
            tampered.write_bytes(b"{\"tampered\":true}\\n")
            before = tampered.read_bytes()
            rejected = subprocess.CompletedProcess(
                [], 2, stdout="", stderr="ratings rejected")
            self.assertTrue(QUALIFICATION.rejected_input_retained(
                tampered, before, rejected, "rejected"))
            tampered.unlink()
            self.assertFalse(QUALIFICATION.rejected_input_retained(
                tampered, before, rejected, "rejected"))


    def test_protocol_amendment_gate_requires_exact_identity(self):
        amended = {
            "protocolAmendment":
                "agentic-palisade/task-8-auth-broker-amendment-v1"
        }
        self.assertTrue(QUALIFICATION.require_protocol_amendment(amended))
        for manifest in ({}, {"protocolAmendment": "agentic-palisade/wrong"}):
            with self.subTest(manifest=manifest):
                with self.assertRaisesRegex(ValueError, "protocol amendment"):
                    QUALIFICATION.require_protocol_amendment(manifest)

    def test_exact_production_pipeline_qualifies_expected_failures_and_tamper_rejection(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "qualification"
            completed = subprocess.run(
                [sys.executable, str(QUALIFIER), "--output", str(output)],
                cwd=HERE.parent,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=360,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)

            summary = json.loads((output / "qualification-summary.json").read_text())
            self.assertEqual(summary["schemaVersion"], "agentic-palisade/qualification-v1")
            self.assertEqual(summary["status"], "qualified")
            self.assertEqual(summary["runnerExit"], 1)
            self.assertEqual(summary["runCount"], 6)
            self.assertEqual(summary["retainedRunRecords"], 6)
            self.assertEqual(
                {name: (case["runClassification"], case["evaluationStatus"])
                 for name, case in summary["cases"].items()},
                EXPECTED_CASES,
            )
            self.assertTrue(summary["identityContinuity"])
            self.assertTrue(summary["treatmentSymmetry"])
            self.assertTrue(summary["noProcessLeaks"])
            self.assertGreater(summary["visualSeparation"]["ssimDelta"], 0.10)
            self.assertTrue(summary["deterministicRerunHashes"])
            self.assertEqual(
                summary["finalChannelRunIds"],
                sorted(case["runId"] for case in summary["cases"].values()),
            )
            for mutation in (
                    "missing-capture", "mapping-tamper", "response-tamper",
                    "final-channel-drop"):
                self.assertEqual(
                    summary["mutations"][mutation],
                    {"rejected": True, "dataRetained": True},
                )
            self.assertTrue((output / "final-report.json").is_file())
            self.assertTrue((output / "review-lock.json").is_file())


if __name__ == "__main__":
    unittest.main()
