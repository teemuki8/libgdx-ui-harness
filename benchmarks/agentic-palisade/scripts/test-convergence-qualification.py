#!/usr/bin/env python3
"""Contract tests for convergence/cost qualification."""

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("convergence-qualification.py")
SPEC = importlib.util.spec_from_file_location("convergence_qualification", SCRIPT)
QUALIFICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALIFICATION)


class ConvergenceQualificationTest(unittest.TestCase):
    def test_matched_controls_preserve_quality_and_report_five_costs(self):
        report = QUALIFICATION.qualify()

        self.assertEqual(
            report["schemaVersion"],
            "agentic-palisade/convergence-qualification-v1")
        self.assertEqual(report["status"], "qualified")
        self.assertEqual(report["runCount"], 12)
        self.assertEqual(
            set(report["costMetrics"]),
            {"wallTimeMillis", "inputTokens", "edits", "builds", "launches"})
        self.assertTrue(all(
            set(run["quality"]) == set(QUALIFICATION.QUALITY_CHANNELS)
            and all(channel["status"] == "pass"
                    for channel in run["quality"].values())
            for run in report["runs"]))
        self.assertTrue(all(
            item["delta"]["inputTokens"] < 0
            for item in report["contrasts"]["actionableDiagnostic"]
            ["treatmentMinusControl"]))
        self.assertTrue(all(
            item["delta"]["builds"] == -2
            and item["delta"]["launches"] == -2
            for item in report["contrasts"]["safeReuse"]
            ["treatmentMinusControl"]))
        self.assertRegex(report["digest"], r"^[0-9a-f]{64}$")

    def test_generic_control_retains_terminal_loop_record(self):
        report = QUALIFICATION.qualify()
        generic = [
            run for run in report["runs"]
            if run["treatment"] == "generic-control"]

        self.assertEqual(len(generic), 3)
        self.assertTrue(all(
            run["terminal"]["code"] == "LOOP_DETECTED"
            and run["terminal"]["remaining"] == 0
            and len(run["terminal"]["digest"]) == 64
            for run in generic))


if __name__ == "__main__":
    unittest.main()
