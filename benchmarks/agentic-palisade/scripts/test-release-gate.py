#!/usr/bin/env python3
"""Behavioral tests for the repeatability release gate."""

import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("release-gate.py")
SPEC = importlib.util.spec_from_file_location("release_gate", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)

SHA = "a" * 64


def sealed_manifest():
    repetitions = []
    for index in range(5):
        observations = {
            name: {
                "semanticRevision": 25,
                "sessionId": f"candidate-session-{index}",
                "completedFrame": 43,
                "pngSha256": [hashlib.sha256(name.encode()).hexdigest()] * 5,
            }
            for name in GATE.OBSERVATIONS
        }
        checkpoints = [{
            "actionId": f"assertion-{item + 1}",
            "assertionPassed": True,
            "rawSha256": hashlib.sha256(f"raw-{item}".encode()).hexdigest(),
            "canonicalSha256": hashlib.sha256(f"canonical-{item}".encode()).hexdigest(),
            "transitionOutcomeSha256": hashlib.sha256(
                f"transition-{item}".encode()).hexdigest(),
        } for item in range(25)]
        stable_frame = {
            "semanticRevision": 25,
            "canonicalSha256": "d" * 64,
            "layoutSha256": "6" * 64,
            "scrollSha256": "7" * 64,
            "framebufferSha256": "8" * 64,
        }
        common = {
            "frozenInputsSha256": SHA,
            "seed": 1729,
            "resourceLimits": {"wallSeconds": 2700, "memoryMiB": 4096, "cpus": 2},
        }
        candidate = {
            **common,
            "runId": f"candidate-{index}",
            "workspaceId": f"candidate-workspace-{index}",
            "sessionId": f"candidate-session-{index}",
            "processId": f"candidate-process-{index}",
            "outputId": f"candidate-output-{index}",
            "semantic": {
                "passed": 25,
                "total": 25,
                "canonicalStateSha256": SHA,
                "transitionSha256": "b" * 64,
                "checkpoints": checkpoints,
            },
            "settling": {
                "policySha256": "c" * 64,
                "settleTimeoutMillis": 5000,
                "completedFrameHashes": [
                    {**stable_frame, "renderedFrame": frame} for frame in (40, 41, 42)
                ],
            },
            "captures": copy.deepcopy(observations),
            "channels": {"automatedVisual": True, "structuralUsability": True},
            "costs": {
                "wallTimeMillis": 100,
                "inputTokens": 1000,
                "edits": 20,
                "builds": 1,
                "launches": 1,
            },
        }
        baseline = {
            **common,
            "runId": f"baseline-{index}",
            "workspaceId": f"baseline-workspace-{index}",
            "sessionId": f"baseline-session-{index}",
            "processId": f"baseline-process-{index}",
            "outputId": f"baseline-output-{index}",
            "costs": {
                "wallTimeMillis": 120,
                "inputTokens": 1200,
                "edits": 24,
                "builds": 2,
                "launches": 2,
            },
        }
        repetitions.append({
            "id": f"pair-{index + 1}",
            "environmentId": "linux-nvidia",
            "startedAt": f"2026-07-30T00:0{index + 3}:00Z",
            "armOrder": ["candidate", "baseline"] if index % 2 == 0
            else ["baseline", "candidate"],
            "candidate": candidate,
            "baseline": baseline,
            "humanReview": {
                "reviewerIds": [f"reviewer-a-{index}", f"reviewer-b-{index}"],
                "fidelityRatings": [6, 5],
                "unusableVotes": 0,
                "responseSha256": "e" * 64,
                "mappingSha256": "f" * 64,
                "ratingsSealedAt": "2026-07-30T00:00:00Z",
                "unblindedAt": "2026-07-30T00:01:00Z",
            },
            "evidenceSha256": hashlib.sha256(f"evidence-{index}".encode()).hexdigest(),
        })
    manifest = {
        "schemaVersion": "agentic-palisade/repeatability-manifest-v1",
        "candidateCommit": "1" * 40,
        "candidateSourceSha256": "2" * 64,
        "releaseVersion": "1.1.0",
        "sealedAt": "2026-07-30T00:10:00Z",
        "dynamicMask": None,
        "statisticalMethod": "paired-randomization-test-v1",
        "allocationSeed": 90210,
        "exclusionPolicy": "rerun-both-arms-retain-original-v1",
        "scenarioAssertionGroups": 25,
        "observations": list(GATE.OBSERVATIONS),
        "precommitmentHashes": {key: hashlib.sha256(key.encode()).hexdigest()
                                for key in GATE.PRECOMMIT_HASHES},
        "costCeilings": {
            "wallTimeMillis": 200,
            "inputTokens": 2000,
            "edits": 40,
            "builds": 3,
            "launches": 3,
        },
        "environments": [{
            "id": "linux-nvidia",
            "os": "Linux",
            "osVersion": "42",
            "jvm": "Temurin-25.0.1",
            "backend": "LWJGL3",
            "gpu": "NVIDIA-570.1",
            "display": "X11-1920x1080@1.0",
            "locale": "en_US.UTF-8",
            "timezone": "UTC",
            "fontSetSha256": "3" * 64,
            "environmentSnapshotSha256": "4" * 64,
        }],
        "repetitions": repetitions,
        "retainedControlsSha256": GATE.sha256_file(GATE.CONTROLS_PATH),
        "artifacts": {
            f"runs/pair-{index + 1}.json":
                hashlib.sha256(f"evidence-{index}".encode()).hexdigest()
            for index in range(5)
        },
    }
    manifest["manifestSha256"] = GATE.seal(manifest)
    return manifest


def sealed_precommitment(manifest):
    precommitment = {
        "schemaVersion": "agentic-palisade/repeatability-precommitment-v1",
        "candidateCommit": manifest["candidateCommit"],
        "candidateSourceSha256": manifest["candidateSourceSha256"],
        "releaseVersion": manifest["releaseVersion"],
        "sealedAt": "2026-07-30T00:02:00Z",
        "dynamicMask": manifest["dynamicMask"],
        "statisticalMethod": manifest["statisticalMethod"],
        "allocationSeed": manifest["allocationSeed"],
        "exclusionPolicy": manifest["exclusionPolicy"],
        "scenarioAssertionGroups": manifest["scenarioAssertionGroups"],
        "observations": manifest["observations"],
        "precommitmentHashes": manifest["precommitmentHashes"],
        "costCeilings": manifest["costCeilings"],
        "environments": manifest["environments"],
        "schedule": [{
            "id": repetition["id"],
            "environmentId": repetition["environmentId"],
            "armOrder": repetition["armOrder"],
            "candidate": {
                key: repetition["candidate"][key]
                for key in (*GATE.IDENTITIES, "frozenInputsSha256", "seed",
                            "resourceLimits")
            },
            "baseline": {
                key: repetition["baseline"][key]
                for key in (*GATE.IDENTITIES, "frozenInputsSha256", "seed",
                            "resourceLimits")
            },
        } for repetition in manifest["repetitions"]],
    }
    precommitment["precommitmentSha256"] = GATE.seal_precommitment(precommitment)
    manifest["precommitmentSha256"] = precommitment["precommitmentSha256"]
    manifest["manifestSha256"] = GATE.seal(manifest)
    return precommitment


class ReleaseGateTest(unittest.TestCase):
    def test_public_schemas_are_strict_and_parseable(self):
        schema_root = SCRIPT.parent / "schemas"
        precommitment_schema = json.loads(
            (schema_root / "repeatability-precommitment.schema.json").read_text())
        manifest_schema = json.loads(
            (schema_root / "repeatability-manifest.schema.json").read_text())
        decision_schema = json.loads(
            (schema_root / "repeatability-decision.schema.json").read_text())
        self.assertFalse(manifest_schema["additionalProperties"])
        self.assertFalse(precommitment_schema["additionalProperties"])
        self.assertFalse(decision_schema["additionalProperties"])
        self.assertEqual(GATE.SCHEMA,
                         manifest_schema["properties"]["schemaVersion"]["const"])
        self.assertEqual(
            GATE.PRECOMMITMENT_SCHEMA,
            precommitment_schema["properties"]["schemaVersion"]["const"])
        self.assertEqual(GATE.DECISION_SCHEMA,
                         decision_schema["properties"]["schemaVersion"]["const"])

    def test_complete_conjunction_passes_and_is_deterministic(self):
        manifest = sealed_manifest()
        precommitment = sealed_precommitment(manifest)
        first = GATE.evaluate(manifest, precommitment, "1" * 40, "1.1.0")
        second = GATE.evaluate(manifest, precommitment, "1" * 40, "1.1.0")
        self.assertTrue(first["passed"])
        self.assertEqual(first, second)
        self.assertEqual(5, first["strata"][0]["matchedPairs"])
        self.assertEqual("paired-randomization-test-v1", first["statistics"]["method"])

    def test_each_independent_channel_fails_closed(self):
        mutations = [
            ("semantic", lambda m: m["repetitions"][0]["candidate"]["semantic"].update(passed=24)),
            ("settling", lambda m: m["repetitions"][0]["candidate"]["settling"].update(
                completedFrameHashes=[
                    {**m["repetitions"][0]["candidate"]["settling"]
                     ["completedFrameHashes"][0], "renderedFrame": frame,
                     "framebufferSha256": "9" * 64 if frame == 41 else "8" * 64}
                    for frame in (40, 41, 42)])),
            ("capture", lambda m: m["repetitions"][0]["candidate"]["captures"]
             ["bottom-1920x1080"]["pngSha256"].__setitem__(4, "5" * 64)),
            ("capture", lambda m: m["repetitions"][0]["candidate"]["captures"]
             ["initial-1920x1080"].update(sessionId="stale-session")),
            ("automated", lambda m: m["repetitions"][0]["candidate"]["channels"].update(
                automatedVisual=False)),
            ("structural", lambda m: m["repetitions"][0]["candidate"]["channels"].update(
                structuralUsability=False)),
            ("human", lambda m: m["repetitions"][0]["humanReview"].update(
                fidelityRatings=[4, 4])),
            ("cost", lambda m: m["repetitions"][0]["candidate"]["costs"].update(
                inputTokens=2001)),
        ]
        for expected, mutation in mutations:
            with self.subTest(expected=expected):
                manifest = sealed_manifest()
                precommitment = sealed_precommitment(manifest)
                mutation(manifest)
                manifest["manifestSha256"] = GATE.seal(manifest)
                decision = GATE.evaluate(
                    manifest, precommitment, "1" * 40, "1.1.0")
                self.assertFalse(decision["passed"])
                self.assertTrue(any(expected in item.lower() for item in decision["failures"]))

    def test_identity_seal_environment_and_all_runs_are_required(self):
        mutations = [
            lambda m: m.update(manifestSha256="0" * 64),
            lambda m: m["repetitions"].pop(),
            lambda m: m["repetitions"][1]["candidate"].update(
                workspaceId=m["repetitions"][0]["candidate"]["workspaceId"]),
            lambda m: m["repetitions"][0]["baseline"].update(seed=1730),
            lambda m: m["environments"][0].pop("fontSetSha256"),
            lambda m: m.update(dynamicMask={"rectangles": []}),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                manifest = sealed_manifest()
                precommitment = sealed_precommitment(manifest)
                mutation(manifest)
                if manifest.get("manifestSha256") != "0" * 64:
                    manifest["manifestSha256"] = GATE.seal(manifest)
                self.assertFalse(GATE.evaluate(
                    manifest, precommitment, "1" * 40, "1.1.0")["passed"])

    def test_cross_repetition_capture_drift_fails(self):
        manifest = sealed_manifest()
        precommitment = sealed_precommitment(manifest)
        manifest["repetitions"][4]["candidate"]["captures"]["initial-1920x1080"][
            "pngSha256"] = ["9" * 64] * 5
        manifest["manifestSha256"] = GATE.seal(manifest)
        decision = GATE.evaluate(manifest, precommitment, "1" * 40, "1.1.0")
        self.assertFalse(decision["passed"])
        self.assertTrue(any("cross-repetition capture" in item for item in decision["failures"]))

    def test_cli_verifies_precommitted_decision_byte_for_byte(self):
        manifest = sealed_manifest()
        precommitment = sealed_precommitment(manifest)
        decision = GATE.evaluate(manifest, precommitment, "1" * 40, "1.1.0")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "manifest.json"
            precommitment_path = root / "precommitment.json"
            decision_path = root / "decision.json"
            manifest_path.write_bytes(GATE.canonical_bytes(manifest))
            precommitment_path.write_bytes(GATE.canonical_bytes(precommitment))
            decision_path.write_bytes(GATE.canonical_bytes(decision))
            (root / "runs").mkdir()
            for index in range(5):
                (root / "runs" / f"pair-{index + 1}.json").write_bytes(
                    f"evidence-{index}".encode())
            self.assertEqual(0, GATE.main([
                "verify", "--manifest", str(manifest_path),
                "--precommitment", str(precommitment_path),
                "--decision", str(decision_path),
                "--candidate-commit", "1" * 40,
                "--candidate-source-sha256", "2" * 64,
                "--release-version", "1.1.0",
                "--evidence-root", str(root),
            ]))
            (root / "unsealed-extra.json").write_text("{}")
            self.assertEqual(1, GATE.main([
                "verify", "--manifest", str(manifest_path),
                "--precommitment", str(precommitment_path),
                "--decision", str(decision_path),
                "--candidate-commit", "1" * 40,
                "--candidate-source-sha256", "2" * 64,
                "--release-version", "1.1.0",
                "--evidence-root", str(root),
            ]))


if __name__ == "__main__":
    unittest.main()
