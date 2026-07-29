#!/usr/bin/env python3
"""Behavioral tests for stable OMP session and round-gate telemetry."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "fixtures"


def load_module(filename, name):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TelemetryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.telemetry = load_module("parse-omp-session.py", "parse_omp_session")

    def test_extracts_provider_tokens_and_observable_operations(self):
        result = self.telemetry.parse_omp_session(FIXTURES / "session-complete.jsonl")

        self.assertEqual(
            result["tokens"],
            {
                "input": {"status": "available", "value": 150},
                "output": {"status": "available", "value": 30},
                "cacheRead": {"status": "available", "value": 35},
                "cacheWrite": {"status": "available", "value": 5},
                "reasoning": {"status": "available", "value": 10},
            },
        )
        self.assertEqual(result["toolCalls"], {"bash": 2, "edit": 1})
        self.assertEqual(result["edits"], 1)
        self.assertEqual(result["builds"], 1)
        self.assertEqual(result["launches"], 1)
        self.assertEqual(result["screenshots"], 1)
        self.assertEqual(
            result["captureEvents"],
            {
                "attempted": 1,
                "schemaRejected": 0,
                "accepted": 0,
                "inspected": 0,
                "compared": 0,
                "stale": 0,
                "completionUsed": 0,
                "launcherCaptures": 0,
            },
        )
        self.assertEqual(
            result["failedOperations"],
            [{"toolCallId": "build-1", "name": "bash"}],
        )

    def test_marks_provider_omissions_explicitly_unavailable(self):
        result = self.telemetry.parse_omp_session(FIXTURES / "session-unavailable.jsonl")

        self.assertEqual(result["tokens"]["input"], {"status": "available", "value": 7})
        self.assertEqual(result["tokens"]["output"], {"status": "available", "value": 3})
        for category in ("cacheRead", "cacheWrite", "reasoning"):
            self.assertEqual(
                result["tokens"][category],
                {"status": "unavailable", "value": None},
            )

    def test_never_interprets_hidden_reasoning_as_telemetry(self):
        result = self.telemetry.parse_omp_session(FIXTURES / "session-complete.jsonl")

        self.assertEqual(result["tokens"]["input"]["value"], 150)
        self.assertEqual(result["toolCalls"], {"bash": 2, "edit": 1})
        self.assertEqual(result["screenshots"], 1)

    def test_separates_capture_and_comparison_channels_from_launcher_pngs(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = Path(temporary) / "session.jsonl"
            events = [
                {"type": "session", "version": 3, "id": "s"},
                {
                    "type": "message",
                    "message": {
                        "role": "assistant",
                        "content": [
                            {"type": "toolCall", "id": "rejected",
                             "name": "ui_screenshot", "arguments": {}},
                            {"type": "toolCall", "id": "compared",
                             "name": "ui_inspect_compare", "arguments": {}},
                            {"type": "toolCall", "id": "launcher", "name": "bash",
                             "arguments": {"command": "./gradlew run capture"}},
                        ],
                    },
                },
                {
                    "type": "message",
                    "message": {
                        "role": "toolResult", "toolCallId": "rejected",
                        "toolName": "ui_screenshot", "isError": True,
                        "content": [{"code": "invalid-arguments",
                                     "message": "schema rejected"}],
                    },
                },
                {
                    "type": "message",
                    "message": {
                        "role": "toolResult", "toolCallId": "compared",
                        "toolName": "ui_inspect_compare", "isError": False,
                        "content": [{"kind": "inspect-compare-result",
                                     "status": "converged",
                                     "currentArtifact": {"reference": "artifact:1"},
                                     "metrics": {"differingPixels": 0}}],
                    },
                },
                {
                    "type": "message",
                    "message": {
                        "role": "toolResult", "toolCallId": "launcher",
                        "toolName": "bash", "isError": False, "content": [],
                    },
                },
            ]
            session.write_text("".join(json.dumps(event) + "\n" for event in events))

            result = self.telemetry.parse_omp_session(session)

            self.assertEqual(
                result["captureEvents"],
                {
                    "attempted": 2,
                    "schemaRejected": 1,
                    "accepted": 1,
                    "inspected": 1,
                    "compared": 1,
                    "stale": 0,
                    "completionUsed": 1,
                    "launcherCaptures": 1,
                },
            )

    def test_rejects_malformed_and_truncated_jsonl(self):
        for fixture in ("session-malformed.jsonl", "session-truncated.jsonl"):
            with self.subTest(fixture=fixture):
                with self.assertRaises(self.telemetry.TelemetryError):
                    self.telemetry.parse_omp_session(FIXTURES / fixture)

    def test_rejects_a_complete_export_with_an_unfinished_tool_call(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = Path(temporary) / "session.jsonl"
            events = [
                {"type": "session", "version": 3, "id": "s"},
                {
                    "type": "message",
                    "message": {
                        "role": "assistant",
                        "content": [{
                            "type": "toolCall",
                            "id": "unfinished",
                            "name": "bash",
                            "arguments": {"command": "./gradlew build"},
                        }],
                        "usage": {"input": 4, "output": 2},
                    },
                },
            ]
            session.write_text("".join(json.dumps(event) + "\n" for event in events))

            with self.assertRaisesRegex(self.telemetry.TelemetryError, "unfinished tool calls"):
                self.telemetry.parse_omp_session(session)

    def test_rejects_partial_token_categories_instead_of_under_counting(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = Path(temporary) / "session.jsonl"
            events = [
                {"type": "session", "version": 3, "id": "s"},
                {
                    "type": "message",
                    "message": {
                        "role": "assistant",
                        "content": [],
                        "usage": {"input": 4, "output": 2, "cacheRead": 1},
                    },
                },
                {
                    "type": "message",
                    "message": {
                        "role": "assistant",
                        "content": [],
                        "usage": {"input": 5, "output": 3},
                    },
                },
            ]
            session.write_text("".join(json.dumps(event) + "\n" for event in events))

            result = self.telemetry.parse_omp_session(session)

            self.assertEqual(
                result["tokens"]["cacheRead"],
                {"status": "unavailable", "value": None},
            )

    def test_accepts_exactly_three_ordered_round_markers(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "rounds.jsonl"
            markers = [
                {"schemaVersion": "agentic-palisade/round-v1", "round": number,
                 "accepted": True, "timestamp": f"2026-01-01T00:00:0{number}Z",
                 "candidateHash": str(number) * 64}
                for number in (1, 2, 3)
            ]
            path.write_text("".join(json.dumps(marker) + "\n" for marker in markers))

            self.assertEqual(
                [marker["round"] for marker in self.telemetry.parse_round_log(path)],
                [1, 2, 3],
            )

    def test_rejects_missing_duplicate_reordered_and_overflow_rounds(self):
        cases = {
            "missing": [1, 2],
            "duplicate": [1, 1, 2, 3],
            "reordered": [1, 3, 2],
            "overflow": [1, 2, 3, 4],
        }
        with tempfile.TemporaryDirectory() as temporary:
            for name, rounds in cases.items():
                with self.subTest(case=name):
                    path = Path(temporary) / f"{name}.jsonl"
                    markers = [
                        {"schemaVersion": "agentic-palisade/round-v1", "round": number,
                         "accepted": True, "timestamp": "2026-01-01T00:00:00Z",
                         "candidateHash": "a" * 64}
                        for number in rounds
                    ]
                    path.write_text("".join(json.dumps(marker) + "\n" for marker in markers))
                    with self.assertRaises(self.telemetry.RoundProtocolError):
                        self.telemetry.parse_round_log(path)

    def test_rejects_a_logged_failed_round_attempt(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "rounds.jsonl"
            path.write_text(json.dumps({
                "schemaVersion": "agentic-palisade/round-v1",
                "round": 2,
                "accepted": False,
                "timestamp": "2026-01-01T00:00:00Z",
                "candidateHash": "a" * 64,
                "failure": "expected round 1",
            }) + "\n")

            with self.assertRaises(self.telemetry.RoundProtocolError):
                self.telemetry.parse_round_log(path)


if __name__ == "__main__":
    unittest.main()
