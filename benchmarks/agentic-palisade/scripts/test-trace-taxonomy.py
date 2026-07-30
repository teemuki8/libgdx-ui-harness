#!/usr/bin/env python3
"""Deterministic contracts for joined trace identities and failure taxonomy."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


HERE = Path(__file__).resolve().parent


def load_module(filename, name):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TraceTaxonomyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.trace = load_module("trace-taxonomy.py", "trace_taxonomy")

    def test_retained_storage_forms_reconstruct_three_plus_two_plus_three(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            request = {
                "operation": "ui_screenshot",
                "arguments": {"maxBytes": 1000},
            }
            (workspace / "a-cycle.ndjson").write_text(json.dumps(request) + "\n")
            (workspace / "c-cycle.ndjson").write_text(json.dumps(request) + "\n")
            cases = {
                "A": (
                    {"command": (
                        f"HARNESS_COMMANDS='{json.dumps(request)}' ./harness; "
                        "./harness < a-cycle.ndjson; ./harness < a-cycle.ndjson")},
                    3,
                ),
                "C": (
                    {"command": (
                        f"REQUEST='{json.dumps(request)}' ./harness; "
                        "./harness < c-cycle.ndjson")},
                    2,
                ),
                "F": (
                    {"command": " ".join(json.dumps({
                        "operation": "ui_screenshot",
                        "arguments": value,
                    }) for value in (
                        {"width": 1280, "height": 720},
                        {"maxPixels": 1000},
                        {"maxPixelCount": 1000, "maxPngBytes": 1000},
                    ))},
                    3,
                ),
            }

            total = 0
            for candidate, (arguments, expected) in cases.items():
                with self.subTest(candidate=candidate):
                    attempts = self.trace.capture_attempts_from_arguments(
                        "session-1", 1, "call-1", arguments, workspace)
                    self.assertEqual(expected, len(attempts))
                    self.assertEqual(
                        expected, len({item["attemptId"] for item in attempts}))
                    if candidate in ("A", "C"):
                        self.assertEqual(
                            1, len({item["requestId"] for item in attempts}))
                    self.assertTrue(all(
                        item["operation"] == "ui_screenshot"
                        and item["payloadSha256"]
                        and item["source"]["sha256"]
                        for item in attempts))
                    total += len(attempts)
            self.assertEqual(8, total)

    def test_capture_lifecycle_is_mutually_attributed_and_launcher_is_separate(self):
        attempts = [
            {"attemptId": "a", "operation": "ui_screenshot",
             "result": {"isError": True, "code": "invalid-arguments"}},
            {"attemptId": "b", "operation": "ui_screenshot",
             "result": {"isError": True, "code": "io-failed"}},
            {"attemptId": "c", "operation": "ui_screenshot",
             "result": {"isError": False, "succeeded": True, "artifactCreated": True,
                        "inspected": False, "compared": False, "stale": False}},
            {"attemptId": "d", "operation": "launcher:capture",
             "result": {"isError": False, "artifactCreated": True}},
            {"attemptId": "e", "operation": "ui_inspect_compare",
             "result": {"isError": False, "succeeded": True, "artifactCreated": True,
                        "inspected": True, "compared": True, "stale": True}},
        ]

        result = self.trace.classify_capture_lifecycle(attempts)

        self.assertEqual("schema-rejected", result["a"]["terminal"])
        self.assertEqual("execution-failed", result["b"]["terminal"])
        self.assertEqual("succeeded", result["c"]["terminal"])
        self.assertNotIn("inspected", result["c"]["states"])
        self.assertEqual("launcher", result["d"]["channel"])
        self.assertNotIn("succeeded", result["d"]["states"])
        self.assertIn("stale", result["e"]["states"])
        self.assertNotIn("compared-current", result["e"]["states"])

    def test_identity_graph_rejects_duplicate_cross_run_cycle_and_digest_change(self):
        events = [
            self.trace.event("batch", "run-a", "session", 1, "request", []),
            self.trace.event("batch", "run-a", "session", 2, "result", []),
        ]
        events[1]["parentEventIds"] = [events[0]["eventId"]]
        events[1] = self.trace.seal_event(events[1])
        self.trace.validate_identity_graph(events, "batch", "run-a")

        mutations = []
        mutations.append(events + [dict(events[0])])
        cross_run = [dict(item) for item in events]
        cross_run[1]["runId"] = "run-b"
        cross_run[1] = self.trace.seal_event(cross_run[1])
        mutations.append(cross_run)
        cyclic = [dict(item) for item in events]
        cyclic[0]["parentEventIds"] = [cyclic[1]["eventId"]]
        cyclic[0] = self.trace.seal_event(cyclic[0])
        mutations.append(cyclic)
        changed = [dict(item) for item in events]
        changed[0]["kind"] = "changed"
        mutations.append(changed)

        for mutation in mutations:
            with self.assertRaises(self.trace.TraceTaxonomyError):
                self.trace.validate_identity_graph(mutation, "batch", "run-a")

    def test_unproductive_loop_requires_equivalent_errors_and_no_progress(self):
        errors = [
            {"sequence": index, "operation": "ui_screenshot",
             "intentSha256": "a" * 64, "errorClass": "invalid-arguments",
             "progress": []}
            for index in range(1, 4)
        ]

        loops = self.trace.classify_unproductive_loops(errors)
        productive = [dict(item) for item in errors]
        productive[1]["progress"] = [{"kind": "artifact", "id": "artifact-1"}]

        self.assertEqual(1, len(loops))
        self.assertEqual("observed", loops[0]["evidenceClass"])
        self.assertEqual([1, 3], loops[0]["eventRange"])
        self.assertEqual([], self.trace.classify_unproductive_loops(productive))
        successful = [dict(item, errorClass=None) for item in errors]
        self.assertEqual([], self.trace.classify_unproductive_loops(successful))

    def test_taxonomy_keeps_observation_cause_and_hypothesis_closed(self):
        for family in ("capture", "semantic", "rendering", "workflow-loop"):
            attribution = self.trace.attribution(
                family, "observed", "rule/v1", ["event-1"], {"count": 1})
            self.assertEqual(family, attribution["family"])
        with self.assertRaises(self.trace.TraceTaxonomyError):
            self.trace.attribution(
                "capture", "caused", "rule/v1", ["event-1"], {})

    def test_early_exit_is_one_semantic_chain_with_downstream_effects(self):
        assertions = [
            {"id": "state.initial.values", "passed": True, "evidence": "observed"},
            *[
                {"id": f"downstream.{index}", "passed": False, "evidence": "missing"}
                for index in range(24)
            ],
        ]
        evaluation = {
            "functional": {"assertions": assertions},
            "diagnostics": [],
        }

        result = self.trace.semantic_attributions(
            evaluation, {"evaluationSha256": "a" * 64})

        self.assertEqual(1, len(result))
        self.assertEqual("source-established-cause", result[0]["evidenceClass"])
        self.assertEqual("checkpoints.initial.visibleControls",
                         result[0]["observation"]["expectedField"])
        self.assertEqual(
            [f"downstream.{index}" for index in range(24)],
            result[0]["observation"]["affectedAssertionIds"])

    def test_alias_mismatch_remains_observation_without_behavior_claim(self):
        evaluation = {
            "functional": {"assertions": [{
                "id": "controls.identities",
                "passed": False,
                "evidence": "alias mismatch: player",
            }]},
            "diagnostics": [],
        }

        result = self.trace.semantic_attributions(
            evaluation, {"evaluationSha256": "a" * 64})

        self.assertEqual(1, len(result))
        self.assertEqual("observed", result[0]["evidenceClass"])
        self.assertEqual("not-established",
                         result[0]["observation"]["behaviorFailure"])

    def test_rendering_observations_keep_metrics_and_categories_separate(self):
        evaluation = {
            "visual": [{
                "referenceId": "initial-1280x720",
                "viewportId": "compact",
                "referenceSha256": "b" * 64,
                "captureSha256": ["c" * 64] * 5,
                "metrics": {
                    "clipping": {"detected": True},
                    "edgeF1": 0.5,
                },
            }],
            "structural": [{
                "stateId": "initial",
                "viewportId": "compact",
                "signals": [{
                    "name": "legibility",
                    "status": "FAIL",
                    "observed": 0.7,
                }],
            }],
        }

        result = self.trace.rendering_attributions(
            evaluation, {"evaluationSha256": "d" * 64})

        self.assertEqual(1, len(result))
        observation = result[0]["observation"]
        self.assertEqual(
            ["frame-edge-clipping", "typography"],
            observation["categories"])
        self.assertEqual(0.5, observation["metrics"]["edgeF1"])
        self.assertNotIn("human", json.dumps(result).lower())

    def test_public_trace_removes_run_session_source_and_treatment_identity(self):
        raw = {
            "schemaVersion": self.trace.SCHEMA_VERSION,
            "availability": "available",
            "parserVersion": "parse/v1",
            "batchId": "batch",
            "runId": "run",
            "sessionId": "session",
            "inputSha256": "a" * 64,
            "sequenceBasis": "record order",
            "knownExclusions": ["hidden reasoning"],
            "events": [
                self.trace.event(
                    "batch", "run", "session", 1, "tool-request", [],
                    operation="ui_screenshot", evidenceClass="observed"),
            ],
            "captureAttempts": [{
                "attemptId": "b" * 64,
                "requestId": "c" * 64,
                "operation": "ui_screenshot",
                "payloadSha256": "d" * 64,
                "source": {
                    "kind": "referenced-ndjson",
                    "name": "private/treatment-harness.ndjson",
                    "sha256": "e" * 64,
                },
                "replayOfPayloadSha256": "d" * 64,
                "result": None,
            }],
            "captureLifecycle": {},
            "attributions": {
                "capture": [], "semantic": [], "rendering": [],
                "workflow-loop": [],
            },
            "joins": {
                "process": {"status": "unavailable", "identity": None},
                "rounds": {"status": "unavailable", "identities": []},
                "evaluation": {"status": "unavailable", "identity": None},
            },
        }

        public = self.trace.public_trace(
            raw, {"functional": {"assertions": []}}, "f" * 64)
        serialized = json.dumps(public, sort_keys=True)

        for forbidden in ("batchId", "runId", "sessionId", "inputSha256",
                          "treatment-harness", "private/"):
            self.assertNotIn(forbidden, serialized)
        self.assertEqual("referenced-ndjson",
                         public["captureAttempts"][0]["source"]["kind"])
        self.assertEqual("e" * 64,
                         public["captureAttempts"][0]["source"]["sha256"])

    def test_f_protocol_fixture_reconstructs_responses_without_causal_claim(self):
        records = []
        for index in range(14):
            request_id = f"request-{index}"
            records.append({
                "kind": "invocation",
                "requestId": request_id,
                "operation": "installed-distribution",
                "intentSha256": f"{index:064x}",
            })
            records.append({
                "kind": "response",
                "requestId": request_id,
                "status": "succeeded",
            })
        for index in range(20):
            records.append({
                "kind": "response",
                "requestId": f"request-{index % 4}",
                "status": "failed",
                "errorClass": "invalid-arguments",
            })

        result = self.trace.summarize_protocol_records(records)

        self.assertEqual(14, result["invocations"])
        self.assertEqual(14, result["successfulResponses"])
        self.assertEqual(20, result["errorResponses"]["invalid-arguments"])
        self.assertEqual("observed", result["evidenceClass"])
        self.assertEqual("not-established", result["causalInterpretation"])


if __name__ == "__main__":
    unittest.main()
