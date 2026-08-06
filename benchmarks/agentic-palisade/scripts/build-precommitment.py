#!/usr/bin/env python3
"""Build and seal the before-run repeatability precommitment.

Run this AFTER ``run-benchmark.py --prepare-only`` and BEFORE
``--execute-prepared``: the gate requires every recorded run ``startedAt`` to
be strictly later than the precommitment ``sealedAt``, so the precommitment
must exist and be sealed before the measured arms start. The maintainer
reviews the draft and signs it by keeping it as the artifact that the run is
bound to; ``precommitmentSha256`` is computed over canonical JSON with that
field omitted.

Precommitment hash scheme (every digest is computed over real, reviewable
content; the key set is the gate's ``PRECOMMIT_HASHES``):

- scenario:            canonical JSON {"spec": sha256(corpus/spec.json),
                       "schema": hash_tree(corpus/schema)}
- corpus:              hash_tree(corpus)  (identical to manifest hashes.corpus)
- references:          hash_tree(corpus/reference)
- evaluator:           hash_tree(scripts)  (evaluation machinery, no caches)
- candidateTreatment:  sha256(treatments/harness/INSTRUCTIONS.md)
                       (identical to the manifest harness-arm instructionsHash)
- baselineTreatment:   sha256(treatments/baseline/INSTRUCTIONS.md)
                       (identical to the manifest baseline-arm instructionsHash)
- modelProviderTools:  canonical JSON {"model", "reasoning", "imageCapable"}
- promptInstructions:  sha256(prompts/task.md)
                       (identical to manifest hashes.prompt)
- runSchedule:         canonical JSON {"profile", "pairs", "rounds",
                       "maxTimeSeconds", "releaseCandidate"}
- environmentPolicy:   sha256_file(environment-snapshot.json)
- settlingPolicy:      canonical JSON {"settlingFrames"} from the sealed profile
- thresholdPolicy:     canonical JSON {"semanticPassRate", "pngDigests",
                       "medianFidelity", "reviewers", "unusableMajority"}
- exclusionPolicy:     canonical JSON {"exclusionPolicy":
                       "rerun-both-arms-retain-original-v1"}
- statisticalPolicy:   canonical JSON {"statisticalMethod":
                       "paired-randomization-test-v1"}
- blindReviewPolicy:   canonical JSON {"reviewers", "medianFidelity"}
- actionSequence:      canonical JSON {"spec": sha256(corpus/spec.json)}
- dependencyLocks:     canonical JSON {"gradle.lockfile", "settings-gradle.lockfile"}
                       (SHA-256 of each lock file at the repository root)

The schedule is derived from the prepared manifest: one repetition per pair,
``candidate`` arm bound to the harness-treatment run and ``baseline`` arm to
the baseline-treatment run (matching the retained example), arm identities
derived deterministically as ``<kind>-<runId>``, ``frozenInputsSha256`` equal
to the manifest template hash, per-pair ``seed`` derived from the allocation
seed, and ``resourceLimits.wallSeconds`` equal to the manifest ceiling.
"""

import argparse
import hashlib
import importlib.util
import json
import secrets
import sys
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
BENCHMARK_ROOT = SCRIPT_DIR.parent
REPOSITORY_ROOT = BENCHMARK_ROOT.parent.parent


def load_script(filename, name):
    spec = importlib.util.spec_from_file_location(name, SCRIPT_DIR / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GATE = load_script("release-gate.py", "release_gate")
RUNNER = load_script("run-benchmark.py", "benchmark_runner")

# Cost ceilings. The sealed profiles carry input-token, build, and launch
# ceilings; the edit ceiling is not profile-bound, so it is a documented
# constant here (tighter than the historical 1000 used for high-confidence).
EDITS_CEILING = 300


def sha256_file(path):
    return GATE.sha256_file(path)


def sha256_bytes(content):
    return GATE.sha256_bytes(content)


def canonical(value):
    return GATE.canonical_bytes(value)


def composite(*parts):
    """Digest of canonical JSON of the given named parts."""
    return sha256_bytes(canonical(dict(parts)))


def precommitment_hashes(manifest, environment_snapshot_path):
    profile_key = manifest["profile"]
    profile = GATE.PROFILES[profile_key]
    image_capable = RUNNER.MODEL_IMAGE_CAPABLE.get(manifest["model"])
    lock_root = REPOSITORY_ROOT
    return {
        "scenario": composite(
            ("spec", sha256_file(BENCHMARK_ROOT / "corpus/spec.json")),
            ("schema", RUNNER.hash_tree(BENCHMARK_ROOT / "corpus/schema"))),
        "corpus": manifest["hashes"]["corpus"],
        "references": RUNNER.hash_tree(BENCHMARK_ROOT / "corpus/reference"),
        "evaluator": RUNNER.hash_tree(SCRIPT_DIR),
        "candidateTreatment": sha256_file(
            BENCHMARK_ROOT / "treatments/harness/INSTRUCTIONS.md"),
        "baselineTreatment": sha256_file(
            BENCHMARK_ROOT / "treatments/baseline/INSTRUCTIONS.md"),
        "modelProviderTools": composite(
            ("model", manifest["model"]),
            ("reasoning", manifest["reasoning"]),
            ("imageCapable", image_capable)),
        "promptInstructions": sha256_file(BENCHMARK_ROOT / "prompts/task.md"),
        "runSchedule": composite(
            ("profile", profile_key),
            ("pairs", manifest["pairs"]),
            ("rounds", manifest["rounds"]),
            ("maxTimeSeconds", manifest["maxTimeSeconds"]),
            ("releaseCandidate", manifest["releaseCandidate"])),
        "environmentPolicy": sha256_file(environment_snapshot_path),
        "settlingPolicy": composite(
            ("settlingFrames", profile["settlingFrames"])),
        "thresholdPolicy": composite(
            ("semanticPassRate", profile["semanticPassRate"]),
            ("pngDigests", profile["pngDigests"]),
            ("medianFidelity", profile["medianFidelity"]),
            ("reviewers", profile["reviewers"]),
            ("unusableMajority", profile["unusableMajority"])),
        "exclusionPolicy": composite(
            ("exclusionPolicy", "rerun-both-arms-retain-original-v1")),
        "statisticalPolicy": composite(
            ("statisticalMethod", "paired-randomization-test-v1")),
        "blindReviewPolicy": composite(
            ("reviewers", profile["reviewers"]),
            ("medianFidelity", profile["medianFidelity"])),
        "actionSequence": composite(
            ("spec", sha256_file(BENCHMARK_ROOT / "corpus/spec.json"))),
        "dependencyLocks": composite(
            ("gradle.lockfile", sha256_file(lock_root / "gradle.lockfile")),
            ("settings-gradle.lockfile",
             sha256_file(lock_root / "settings-gradle.lockfile"))),
    }


def environment_entry(snapshot, model, snapshot_path):
    inventory = {item["path"]: item["sha256"]
                 for item in snapshot["fontInventory"]}
    os_name = (snapshot.get("os") or "Linux").split()[0]
    captured = datetime.fromisoformat(snapshot["capturedAt"])
    env_id = (
        f"nobsa-linux-x11-jdk25-{captured.astimezone(timezone.utc):%Y%m%d}")
    return {
        "id": env_id,
        "model": model,
        "os": os_name,
        "osVersion": snapshot["os"],
        "jvm": snapshot["jvm"],
        "backend": snapshot["backend"],
        "gpu": snapshot["gpu"],
        "display": snapshot["display"],
        "locale": snapshot["locale"],
        "timezone": snapshot["timezone"],
        "fontSetSha256": sha256_bytes(canonical(inventory)),
        "environmentSnapshotSha256": sha256_file(snapshot_path),
    }


def build_schedule(manifest, environment_id, allocation_seed):
    runs_by_pair = {}
    for run in manifest["runs"]:
        runs_by_pair.setdefault(run["pair"], {})[run["treatment"]] = run
    schedule = []
    for pair in range(1, manifest["pairs"] + 1):
        arms = runs_by_pair[pair]
        harness = arms["harness"]
        baseline = arms["baseline"]
        seed = int(hashlib.sha256(
            f"{allocation_seed}:{pair}".encode("utf-8")).hexdigest()[:8], 16)
        common = {
            "frozenInputsSha256": manifest["hashes"]["template"],
            "seed": seed,
            "resourceLimits": {"wallSeconds": manifest["maxTimeSeconds"]},
        }
        schedule.append({
            "id": f"pair-{pair}",
            "environmentId": environment_id,
            "armOrder": ["candidate", "baseline"],
            "candidate": {
                "runId": harness["runId"],
                "workspaceId": f"workspace-{harness['runId']}",
                "sessionId": f"session-{harness['runId']}",
                "processId": f"process-{harness['runId']}",
                "outputId": f"output-{harness['runId']}",
                **common,
            },
            "baseline": {
                "runId": baseline["runId"],
                "workspaceId": f"workspace-{baseline['runId']}",
                "sessionId": f"session-{baseline['runId']}",
                "processId": f"process-{baseline['runId']}",
                "outputId": f"output-{baseline['runId']}",
                **common,
            },
        })
    return schedule


def build_precommitment(
        manifest, snapshot, snapshot_path, release_version,
        candidate_source_sha256, allocation_seed, sealed_at):
    if manifest.get("schemaVersion") != "agentic-palisade/benchmark-manifest-v1":
        raise ValueError("prepared benchmark manifest schema is unsupported")
    if manifest.get("preparedOnly") is not True:
        raise ValueError("benchmark manifest was not sealed for prepared execution")
    if manifest.get("profile") not in GATE.PROFILES:
        raise ValueError(f"unknown benchmark profile: {manifest.get('profile')}")
    if len(manifest.get("runs", [])) != manifest.get("pairs", 0) * 2:
        raise ValueError("prepared benchmark schedule is incomplete")
    if len(candidate_source_sha256) != 64 or any(
            char not in "0123456789abcdef" for char in candidate_source_sha256):
        raise ValueError("candidate source SHA-256 must be 64 lowercase hex digits")
    environment = environment_entry(snapshot, manifest["model"], snapshot_path)
    precommitment = {
        "schemaVersion": "agentic-palisade/repeatability-precommitment-v1",
        "profile": manifest["profile"],
        "candidateCommit": manifest["candidateCommit"],
        "candidateSourceSha256": candidate_source_sha256,
        "releaseVersion": release_version,
        "sealedAt": sealed_at,
        "dynamicMask": None,
        "statisticalMethod": "paired-randomization-test-v1",
        "allocationSeed": allocation_seed,
        "exclusionPolicy": "rerun-both-arms-retain-original-v1",
        "scenarioAssertionGroups": 25,
        "observations": list(GATE.OBSERVATIONS),
        "precommitmentHashes": precommitment_hashes(manifest, snapshot_path),
        "costCeilings": {
            "wallTimeMillis": manifest["maxTimeSeconds"] * 1000,
            "inputTokens": GATE.PROFILES[manifest["profile"]]["costInputTokens"],
            "edits": EDITS_CEILING,
            "builds": GATE.PROFILES[manifest["profile"]]["costBuilds"],
            "launches": GATE.PROFILES[manifest["profile"]]["costLaunches"],
        },
        "environments": [environment],
        "schedule": build_schedule(
            manifest, environment["id"], allocation_seed),
    }
    precommitment["precommitmentSha256"] = GATE.seal_precommitment(precommitment)
    return precommitment


def current_commit():
    import subprocess
    return subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=REPOSITORY_ROOT,
        capture_output=True, text=True, check=True).stdout.strip()


def validate_schema(precommitment):
    import jsonschema
    schema = json.loads(
        (SCRIPT_DIR / "schemas/repeatability-precommitment.schema.json").read_text())
    jsonschema.Draft202012Validator(schema).validate(precommitment)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--environment-snapshot", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--release-version", default="1.1.0")
    parser.add_argument("--candidate-commit", default=None,
                        help="commit whose Maven artifacts the schedule consumes; "
                             "defaults to current HEAD")
    parser.add_argument("--candidate-source-sha256", default=None,
                        help="git archive CANDIDATE_COMMIT | sha256sum; "
                             "defaults to computing it")
    parser.add_argument("--allocation-seed", type=int, default=None,
                        help="random 63-bit seed when omitted")
    parser.add_argument("--sealed-at", default=None,
                        help="ISO-8601 timestamp; defaults to now")
    arguments = parser.parse_args(argv)
    try:
        manifest = json.loads(arguments.manifest.read_text(encoding="utf-8"))
        snapshot = json.loads(
            arguments.environment_snapshot.read_text(encoding="utf-8"))
        snapshot_path = arguments.environment_snapshot.resolve()
        manifest["candidateCommit"] = (
            arguments.candidate_commit or current_commit())
        candidate_source = arguments.candidate_source_sha256
        if candidate_source is None:
            import subprocess
            archive = subprocess.run(
                ["git", "archive", manifest["candidateCommit"]],
                cwd=REPOSITORY_ROOT, capture_output=True, check=True).stdout
            candidate_source = hashlib.sha256(archive).hexdigest()
        allocation_seed = arguments.allocation_seed
        if allocation_seed is None:
            allocation_seed = secrets.randbits(63)
        sealed_at = arguments.sealed_at or datetime.now(timezone.utc).isoformat()
        precommitment = build_precommitment(
            manifest, snapshot, snapshot_path, arguments.release_version,
            candidate_source, allocation_seed, sealed_at)
        validate_schema(precommitment)
        if arguments.output.exists():
            raise ValueError(f"output already exists: {arguments.output}")
        arguments.output.write_bytes(
            (json.dumps(precommitment, sort_keys=True) + "\n").encode("utf-8"))
        print(json.dumps({
            "status": "sealed",
            "output": str(arguments.output),
            "sealedAt": sealed_at,
            "allocationSeed": allocation_seed,
            "precommitmentSha256": precommitment["precommitmentSha256"],
            "schedulePairs": len(precommitment["schedule"]),
            "environmentId": precommitment["environments"][0]["id"],
        }))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"build-precommitment: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
