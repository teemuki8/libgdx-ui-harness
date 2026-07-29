#!/usr/bin/env python3
"""End-to-end qualification contract for the synthetic benchmark pipeline."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
QUALIFIER = HERE / "qualify-pipeline.py"
EXPECTED_CASES = {
    "conforming": ("success", "complete"),
    "uncompilable": ("success", "compile-failed"),
    "timeout": ("timed_out", "complete"),
    "malformed-omp-export": ("telemetry_failure", "complete"),
    "missing-capture": ("success", "runtime-failed"),
    "leaked-child": ("round_supervisor_failure", "complete"),
}


class QualificationTest(unittest.TestCase):
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
            for mutation in ("missing-capture", "mapping-tamper", "response-tamper"):
                self.assertEqual(
                    summary["mutations"][mutation],
                    {"rejected": True, "dataRetained": True},
                )
            self.assertTrue((output / "final-report.json").is_file())
            self.assertTrue((output / "review-lock.json").is_file())


if __name__ == "__main__":
    unittest.main()
