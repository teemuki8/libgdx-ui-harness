#!/usr/bin/env python3
"""Tests for build-precommitment.py and capture-environment.py."""

import copy
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent


def load_script(filename, name):
    spec = importlib.util.spec_from_file_location(name, SCRIPT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GATE = load_script("release-gate.py", "test_gate")
RUNNER = load_script("run-benchmark.py", "test_runner")
BUILDER = load_script("build-precommitment.py", "test_builder")
CAPTURE = load_script("capture-environment.py", "test_capture")

MODEL = "openai-codex/gpt-5.6-luna:medium"
SEALED_AT = "2026-08-06T00:00:00+00:00"
SOURCE_SHA = "a" * 64
ALLOCATION_SEED = 12345


def real_manifest_hashes():
    root = SCRIPT.parent
    return {
        "prompt": GATE.sha256_file(root / "prompts/task.md"),
        "corpus": RUNNER.hash_tree(root / "corpus"),
        "template": RUNNER.hash_tree(root / "template"),
        "protocol": GATE.sha256_file(root / "PROTOCOL.md"),
    }


def synthetic_manifest(pairs=3, profile="low-confidence"):
    """A prepared-schedule manifest with the fields the builder reads."""
    runs = []
    for pair in range(1, pairs + 1):
        for treatment in ("baseline", "harness"):
            runs.append({
                "runId": hashlib.sha256(
                    f"{pair}:{treatment}".encode()).hexdigest(),
                "pair": pair,
                "treatment": treatment,
            })
    return {
        "schemaVersion": "agentic-palisade/benchmark-manifest-v1",
        "createdAt": SEALED_AT,
        "dryRun": False,
        "preparedOnly": True,
        "releaseCandidate": True,
        "candidateCommit": "1" * 40,
        "candidateVersion": "1.1.0-candidate.test",
        "candidateMavenRepositorySha256": "b" * 64,
        "model": MODEL,
        "reasoning": "medium",
        "profile": profile,
        "maxTimeSeconds": 40 * 60,
        "rounds": RUNNER.FIXED_ROUNDS,
        "pairs": pairs,
        "hashes": real_manifest_hashes(),
        "treatmentCommonInstructionHash": "c" * 64,
        "approvedTreatmentDifferences": [],
        "runs": runs,
    }


def synthetic_snapshot():
    return {
        "schemaVersion": "agentic-palisade/environment-snapshot-v1",
        "capturedAt": "2026-08-06T00:00:00+00:00",
        "candidateCommit": "1" * 40,
        "model": MODEL,
        "reasoning": "medium",
        "os": "Nobara Linux 44",
        "architecture": "x86_64",
        "hostKernel": "7.1.4-200.nobara.fc44.x86_64",
        "jvm": "openjdk version \"25.0.3\" 2026-04-21",
        "backend": "LWJGL3 3.3.3",
        "gpu": "NVIDIA GeForce RTX 4080 SUPER",
        "display": "Xvfb :220-:229, 1920x1080x24",
        "locale": "C.UTF-8",
        "timezone": "Europe/Helsinki",
        "omp": "omp/17.1.8",
        "fontInventory": [
            {"path": "/usr/share/fonts/a.ttf", "bytes": 10,
             "sha256": hashlib.sha256(b"a").hexdigest()},
            {"path": "/usr/share/fonts/b.otf", "bytes": 10,
             "sha256": hashlib.sha256(b"b").hexdigest()},
        ],
    }


def run_builder(manifest_path, snapshot_path, output_path, *extra):
    return subprocess.run(
        [sys.executable, str(SCRIPT / "build-precommitment.py"),
         "--manifest", str(manifest_path),
         "--environment-snapshot", str(snapshot_path),
         "--output", str(output_path),
         "--release-version", "1.1.0",
         "--candidate-commit", "1" * 40,
         "--candidate-source-sha256", SOURCE_SHA,
         "--allocation-seed", str(ALLOCATION_SEED),
         "--sealed-at", SEALED_AT,
         *extra],
        capture_output=True, text=True)


class BuildPrecommitmentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.manifest_path = root / "benchmark-manifest.json"
        self.snapshot_path = root / "environment-snapshot.json"
        self.output_path = root / "precommitment.json"
        self.manifest_path.write_text(json.dumps(synthetic_manifest()))
        self.snapshot_path.write_text(json.dumps(synthetic_snapshot()))

    def test_builds_schema_valid_sealed_precommitment(self):
        completed = run_builder(
            self.manifest_path, self.snapshot_path, self.output_path)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        precommitment = json.loads(self.output_path.read_text())
        schema = json.loads(
            (SCRIPT / "schemas/repeatability-precommitment.schema.json").read_text())
        import jsonschema
        jsonschema.Draft202012Validator(schema).validate(precommitment)
        self.assertEqual(
            precommitment["precommitmentSha256"],
            GATE.seal_precommitment(precommitment))
        self.assertEqual(
            set(precommitment["precommitmentHashes"]), set(GATE.PRECOMMIT_HASHES))
        self.assertTrue(all(
            GATE._valid_hash(value)
            for value in precommitment["precommitmentHashes"].values()))
        self.assertEqual(precommitment["profile"], "low-confidence")
        self.assertEqual(precommitment["candidateCommit"], "1" * 40)
        self.assertEqual(precommitment["candidateSourceSha256"], SOURCE_SHA)
        self.assertEqual(precommitment["releaseVersion"], "1.1.0")

    def test_environment_entry_is_complete_and_bound_to_snapshot(self):
        run_builder(self.manifest_path, self.snapshot_path, self.output_path)
        precommitment = json.loads(self.output_path.read_text())
        environment = precommitment["environments"][0]
        self.assertEqual(set(environment), set(GATE.ENVIRONMENT_FIELDS) | {"model"})
        self.assertEqual(
            environment["environmentSnapshotSha256"],
            GATE.sha256_file(self.snapshot_path))
        self.assertTrue(GATE._valid_hash(environment["fontSetSha256"]))
        self.assertEqual(environment["os"], "Nobara")
        self.assertEqual(environment["osVersion"], "Nobara Linux 44")
        self.assertEqual(environment["model"], MODEL)

    def test_schedule_matches_prepared_runs(self):
        run_builder(self.manifest_path, self.snapshot_path, self.output_path)
        precommitment = json.loads(self.output_path.read_text())
        manifest = synthetic_manifest()
        schedule = precommitment["schedule"]
        self.assertEqual(len(schedule), 3)
        for index, item in enumerate(schedule):
            pair = index + 1
            harness = next(r for r in manifest["runs"]
                           if r["pair"] == pair and r["treatment"] == "harness")
            baseline = next(r for r in manifest["runs"]
                            if r["pair"] == pair and r["treatment"] == "baseline")
            self.assertEqual(item["id"], f"pair-{pair}")
            self.assertEqual(item["armOrder"], ["candidate", "baseline"])
            self.assertEqual(item["candidate"]["runId"], harness["runId"])
            self.assertEqual(item["baseline"]["runId"], baseline["runId"])
            self.assertEqual(
                item["candidate"]["workspaceId"],
                f"workspace-{harness['runId']}")
            self.assertEqual(
                item["candidate"]["frozenInputsSha256"],
                manifest["hashes"]["template"])
            self.assertEqual(
                item["candidate"]["resourceLimits"],
                {"wallSeconds": 40 * 60})
            self.assertEqual(item["candidate"]["seed"], item["baseline"]["seed"])
            self.assertEqual(
                set(item["candidate"]),
                set(GATE.IDENTITIES) | {
                    "frozenInputsSha256", "seed", "resourceLimits"})

    def test_cost_ceilings_cover_all_gate_costs(self):
        run_builder(self.manifest_path, self.snapshot_path, self.output_path)
        precommitment = json.loads(self.output_path.read_text())
        self.assertEqual(
            set(precommitment["costCeilings"]), set(GATE.COSTS))
        self.assertEqual(
            precommitment["costCeilings"]["wallTimeMillis"], 40 * 60 * 1000)
        self.assertEqual(
            precommitment["costCeilings"]["inputTokens"],
            GATE.PROFILES["low-confidence"]["costInputTokens"])

    def test_seal_changes_when_any_field_changes(self):
        run_builder(self.manifest_path, self.snapshot_path, self.output_path)
        original = json.loads(self.output_path.read_text())
        for field in ("releaseVersion", "allocationSeed", "sealedAt"):
            mutated = copy.deepcopy(original)
            mutated[field] = (mutated[field] + "x"
                              if isinstance(mutated[field], str)
                              else mutated[field] + 1)
            self.assertNotEqual(
                GATE.seal_precommitment(mutated),
                original["precommitmentSha256"], field)

    def test_harness_and_baseline_hashes_match_manifest_instructions(self):
        run_builder(self.manifest_path, self.snapshot_path, self.output_path)
        precommitment = json.loads(self.output_path.read_text())
        self.assertEqual(
            precommitment["precommitmentHashes"]["candidateTreatment"],
            GATE.sha256_file(SCRIPT.parent / "treatments/harness/INSTRUCTIONS.md"))
        self.assertEqual(
            precommitment["precommitmentHashes"]["baselineTreatment"],
            GATE.sha256_file(SCRIPT.parent / "treatments/baseline/INSTRUCTIONS.md"))
        self.assertEqual(
            precommitment["precommitmentHashes"]["promptInstructions"],
            GATE.sha256_file(SCRIPT.parent / "prompts/task.md"))
        self.assertEqual(
            precommitment["precommitmentHashes"]["corpus"],
            RUNNER.hash_tree(SCRIPT.parent / "corpus"))

    def test_refuses_existing_output(self):
        run_builder(self.manifest_path, self.snapshot_path, self.output_path)
        second = run_builder(
            self.manifest_path, self.snapshot_path, self.output_path)
        self.assertNotEqual(second.returncode, 0)
        self.assertIn("already exists", second.stderr)

    def test_rejects_unknown_profile(self):
        manifest = synthetic_manifest(profile="medium-confidence")
        self.manifest_path.write_text(json.dumps(manifest))
        completed = run_builder(
            self.manifest_path, self.snapshot_path, self.output_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("unknown benchmark profile", completed.stderr)

    def test_rejects_incomplete_schedule(self):
        manifest = synthetic_manifest()
        manifest["runs"] = manifest["runs"][:-1]
        self.manifest_path.write_text(json.dumps(manifest))
        completed = run_builder(
            self.manifest_path, self.snapshot_path, self.output_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("incomplete", completed.stderr)


class CaptureEnvironmentTest(unittest.TestCase):
    def test_captures_required_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "environment-snapshot.json"
            completed = subprocess.run(
                [sys.executable, str(SCRIPT / "capture-environment.py"),
                 "--candidate-commit", "1" * 40,
                 "--model", MODEL,
                 "--reasoning", "medium",
                 "--output", str(output),
                 "--captured-at", SEALED_AT],
                capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            snapshot = json.loads(output.read_text())
            for key in ("schemaVersion", "capturedAt", "candidateCommit",
                        "model", "reasoning", "os", "architecture",
                        "hostKernel", "jvm", "backend", "gpu", "display",
                        "locale", "timezone", "omp", "fontInventory"):
                self.assertIn(key, snapshot)
            self.assertTrue(snapshot["fontInventory"])
            for item in snapshot["fontInventory"]:
                self.assertEqual(
                    set(item), {"path", "bytes", "sha256"})
                self.assertTrue(GATE._valid_hash(item["sha256"]))


if __name__ == "__main__":
    unittest.main()
