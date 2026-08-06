#!/usr/bin/env python3
"""Evaluate a sealed, all-runs repeatability release manifest."""

import argparse
import hashlib
import json
import re
import statistics
import sys
from pathlib import Path

SCHEMA = "agentic-palisade/repeatability-manifest-v1"
PRECOMMITMENT_SCHEMA = "agentic-palisade/repeatability-precommitment-v1"
DECISION_SCHEMA = "agentic-palisade/repeatability-decision-v1"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
OBSERVATIONS = ("initial-1920x1080", "bottom-1920x1080", "initial-1280x720")
COSTS = ("wallTimeMillis", "inputTokens", "edits", "builds", "launches")
IDENTITIES = ("runId", "workspaceId", "sessionId", "processId", "outputId")
ENVIRONMENT_FIELDS = (
    "id", "os", "osVersion", "jvm", "backend", "gpu", "display", "locale",
    "timezone", "fontSetSha256", "environmentSnapshotSha256",
)
PRECOMMIT_HASHES = (
    "scenario", "corpus", "references", "evaluator", "candidateTreatment",
    "baselineTreatment", "modelProviderTools", "promptInstructions",
    "runSchedule", "environmentPolicy", "settlingPolicy", "thresholdPolicy",
    "exclusionPolicy", "statisticalPolicy", "blindReviewPolicy",
    "actionSequence", "dependencyLocks",
)
CONTROLS_PATH = Path(__file__).with_name("retained-controls.json")

PROFILES = {
    "low-confidence": {
        "modelImagesRequired": True,
        "pairs": 3,
        "rounds": 2,
        "requiredRepetitions": 1,
        "semanticPassRate": 0.6,
        "pngDigests": 3,
        "settlingFrames": 3,
        "reviewers": 1,
        "medianFidelity": 3,
        "unusableMajority": True,
        "costInputTokens": 500_000,
        "costBuilds": 10,
        "costLaunches": 30,
        "wallSeconds": 40 * 60,
    },
    "high-confidence": {
        "modelImagesRequired": True,
        "pairs": 5,
        "rounds": 3,
        "requiredRepetitions": 2,
        "semanticPassRate": 1.0,
        "pngDigests": 5,
        "settlingFrames": 3,
        "reviewers": 2,
        "medianFidelity": 5,
        "unusableMajority": True,
        "costInputTokens": 1_000_000,
        "costBuilds": 100,
        "costLaunches": 100,
        "wallSeconds": None,  # per-arm sealed value, unchanged
    },
}


def canonical_bytes(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"),
                       ensure_ascii=False) + "\n").encode()


def sha256_bytes(content):
    return hashlib.sha256(content).hexdigest()


def sha256_file(path):
    return sha256_bytes(Path(path).read_bytes())


def seal(manifest):
    unsealed = {key: value for key, value in manifest.items()
                if key != "manifestSha256"}
    return sha256_bytes(canonical_bytes(unsealed))


def seal_precommitment(precommitment):
    unsealed = {key: value for key, value in precommitment.items()
                if key != "precommitmentSha256"}
    return sha256_bytes(canonical_bytes(unsealed))


def validate_retained_controls():
    controls = json.loads(CONTROLS_PATH.read_text())
    failures = []
    if controls.get("schemaVersion") != "agentic-palisade/retained-controls-v1":
        failures.append("Retained-control schema is unsupported")
        return failures
    expected = controls.get("controls", {})
    a = expected.get("A", {})
    observations = a.get("observations", {})
    if not observations.get("initial-1920x1080", {}).get("stable"):
        failures.append("Retained control A initial-1920 classification changed")
    if not observations.get("initial-1280x720", {}).get("stable"):
        failures.append("Retained control A initial-1280 classification changed")
    bottom = observations.get("bottom-1920x1080", {})
    hashes = bottom.get("hashes", [])
    if (bottom.get("stable") is not False or len(hashes) != 5
            or hashes[2:] != [hashes[2]] * 3
            or len(set(hashes)) != 3
            or bottom.get("failureMetric") != 1.491130602173354):
        failures.append("Retained control A bottom capture must remain ordered and unstable")
    for label in ("C", "D", "E", "F"):
        control = expected.get(label, {})
        captures = control.get("captures", {})
        if (control.get("humanFidelity") != 1
                or set(captures) != set(OBSERVATIONS)
                or any(len(values) != 5 or len(set(values)) != 1
                       for values in captures.values())):
            failures.append(
                f"Retained control {label} must remain capture-stable but human-unusable")
    return failures


def _failure(failures, message):
    failures.append(message)


def _valid_hash(value):
    return isinstance(value, str) and SHA256.fullmatch(value) is not None


def _median(values):
    return statistics.median(values)


def _range(values):
    return [min(values), max(values)]


def verify_artifacts(manifest, evidence_root):
    root = Path(evidence_root).resolve()
    catalog = manifest.get("artifacts", {})
    expected_paths = set(catalog) | {
        "precommitment.json", "manifest.json", "decision.json",
    }
    actual_paths = {
        str(path.relative_to(root))
        for path in root.rglob("*")
        if path.is_file() or path.is_symlink()
    }
    if actual_paths != expected_paths:
        missing = sorted(expected_paths - actual_paths)
        extra = sorted(actual_paths - expected_paths)
        raise ValueError(f"retained artifact set mismatch; missing={missing}, extra={extra}")
    for relative, expected in catalog.items():
        artifact = (root / relative).resolve()
        if (root not in artifact.parents or not artifact.is_file()
                or sha256_file(artifact) != expected):
            raise ValueError(f"retained artifact mismatch: {relative}")


def evaluate(
        manifest, precommitment, candidate_commit, release_version,
        candidate_source_sha256=None):
    failures = []
    if (not isinstance(precommitment, dict)
            or precommitment.get("schemaVersion") != PRECOMMITMENT_SCHEMA):
        _failure(failures, "Precommitment schema is unsupported")
        precommitment = precommitment if isinstance(precommitment, dict) else {}
    profile_key = precommitment.get("profile", "high-confidence")
    profile = PROFILES.get(profile_key)
    if profile is None:
        _failure(failures, f"Unknown profile: {profile_key}")
        profile = PROFILES["high-confidence"]
    if precommitment.get("precommitmentSha256") != seal_precommitment(precommitment):
        _failure(failures, "Precommitment seal does not match its canonical content")
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != SCHEMA:
        _failure(failures, "Manifest schema is unsupported")
        manifest = manifest if isinstance(manifest, dict) else {}
    if manifest.get("manifestSha256") != seal(manifest):
        _failure(failures, "Manifest seal does not match its canonical content")
    if manifest.get("precommitmentSha256") != precommitment.get(
            "precommitmentSha256"):
        _failure(failures, "Result manifest is not bound to the precommitment")
    committed_fields = (
        "candidateCommit", "candidateSourceSha256", "releaseVersion",
        "dynamicMask", "statisticalMethod", "allocationSeed", "exclusionPolicy",
        "scenarioAssertionGroups", "observations", "precommitmentHashes",
        "costCeilings", "environments",
    )
    for field in committed_fields:
        if manifest.get(field) != precommitment.get(field):
            _failure(failures, f"Post-start precommitment change: {field}")
    if not COMMIT.fullmatch(candidate_commit or ""):
        _failure(failures, "Expected candidate commit is malformed")
    if manifest.get("candidateCommit") != candidate_commit:
        _failure(failures, "Candidate commit identity does not match the release tag")
    if manifest.get("releaseVersion") != release_version:
        _failure(failures, "Release version does not match the release tag")
    if not _valid_hash(manifest.get("candidateSourceSha256")):
        _failure(failures, "Candidate source digest is missing")
    if (candidate_source_sha256 is not None
            and manifest.get("candidateSourceSha256") != candidate_source_sha256):
        _failure(failures, "Candidate source digest does not match the release archive")
    if manifest.get("dynamicMask") is not None:
        _failure(failures, "Dynamic masks are forbidden by the default release policy")
    if manifest.get("statisticalMethod") != "paired-randomization-test-v1":
        _failure(failures, "Predeclared paired statistical method is missing")
    if manifest.get("scenarioAssertionGroups") != 25:
        _failure(failures, "Scenario must precommit all 25 assertion groups")
    if manifest.get("observations") != list(OBSERVATIONS):
        _failure(failures, "Canonical observation schedule is incomplete or reordered")
    precommit = manifest.get("precommitmentHashes")
    if (not isinstance(precommit, dict) or set(precommit) != set(PRECOMMIT_HASHES)
            or any(not _valid_hash(value) for value in precommit.values())):
        _failure(failures, "Precommitment identity hashes are incomplete")
    if not isinstance(manifest.get("allocationSeed"), int):
        _failure(failures, "Sealed arm allocation seed is missing")
    if manifest.get("exclusionPolicy") != "rerun-both-arms-retain-original-v1":
        _failure(failures, "Sealed exclusion policy is unsupported")
    if manifest.get("retainedControlsSha256") != sha256_file(CONTROLS_PATH):
        _failure(failures, "Retained-control catalog digest does not match")
    failures.extend(validate_retained_controls())
    artifacts = manifest.get("artifacts")
    if (not isinstance(artifacts, dict) or not artifacts
            or any(not isinstance(path, str) or not path
                   or path.startswith("/") or ".." in Path(path).parts
                   or not _valid_hash(digest)
                   for path, digest in artifacts.items())):
        _failure(failures, "Retained artifact path/digest catalog is incomplete")

    ceilings = manifest.get("costCeilings")
    if not isinstance(ceilings, dict) or set(ceilings) != set(COSTS):
        _failure(failures, "All five precommitted cost ceilings are required")
        ceilings = {}
    elif any(not isinstance(ceilings[key], (int, float)) or ceilings[key] < 0
             for key in COSTS):
        _failure(failures, "Cost ceilings must be finite non-negative numbers")

    environments = manifest.get("environments")
    environment_by_id = {}
    if not isinstance(environments, list) or not environments:
        _failure(failures, "At least one complete environment stratum is required")
    else:
        for environment in environments:
            if not isinstance(environment, dict) or any(
                    not environment.get(field) for field in ENVIRONMENT_FIELDS):
                _failure(failures, "Environment identity is incomplete")
                continue
            if not _valid_hash(environment["fontSetSha256"]):
                _failure(failures, "Environment font-set digest is malformed")
            if not _valid_hash(environment["environmentSnapshotSha256"]):
                _failure(failures, "Complete environment snapshot digest is malformed")
            identifier = environment["id"]
            if identifier in environment_by_id:
                _failure(failures, f"Environment stratum is duplicated: {identifier}")
            environment_by_id[identifier] = environment

    repetitions = manifest.get("repetitions")
    if not isinstance(repetitions, list):
        _failure(failures, "Repetitions must be an array")
        repetitions = []

    identity_values = {field: set() for field in IDENTITIES}
    strata = {identifier: [] for identifier in environment_by_id}
    canonical_states = {}
    transition_hashes = {}
    capture_sets = {}
    raw_costs = {key: [] for key in COSTS}
    paired_deltas = {key: [] for key in COSTS}
    run_results = []

    for index, repetition in enumerate(repetitions):
        failure_start = len(failures)
        prefix = f"Repetition {index + 1}"
        if not isinstance(repetition, dict):
            _failure(failures, f"{prefix} is malformed")
            continue
        if repetition.get("excluded") is True:
            _failure(failures, f"{prefix} is excluded; release policy requires every listed run")
        environment_id = repetition.get("environmentId")
        if environment_id not in environment_by_id:
            _failure(failures, f"{prefix} references an undeclared environment stratum")
        else:
            strata[environment_id].append(repetition)
        candidate = repetition.get("candidate", {})
        baseline = repetition.get("baseline", {})
        if (not repetition.get("startedAt")
                or precommitment.get("sealedAt", "") >= repetition["startedAt"]):
            _failure(failures, f"{prefix} did not start after manifest sealing")
        if repetition.get("armOrder") not in (
                ["candidate", "baseline"], ["baseline", "candidate"]):
            _failure(failures, f"{prefix} arm order is not predeclared")
        schedule = precommitment.get("schedule", [])
        scheduled = next(
            (item for item in schedule if isinstance(item, dict)
             and item.get("id") == repetition.get("id")),
            None,
        )
        if repetition.get("status") == "cancelled":
            fail_fast = any(isinstance(item, dict)
                            and item.get("failFast") is True
                            for item in schedule)
            if not fail_fast:
                _failure(failures,
                         f"{prefix} cancelled without a precommitted fail-fast rule")
                continue
            reason = repetition.get("cancelReason")
            if not isinstance(reason, dict) or not reason.get("runId"):
                _failure(failures, f"{prefix} cancellation lacks a runId reason")
                continue
            triggering = next(
                (item for item in manifest.get("repetitions", [])
                 if isinstance(item, dict)
                 and item.get("status") != "cancelled"
                 and item.get("id") == reason.get("runId")),
                None,
            )
            failed = bool(triggering) and any(
                arm.get("classification") != "success"
                for arm_name in ("candidate", "baseline")
                for arm in [triggering.get(arm_name, {})])
            if not failed:
                _failure(failures,
                         f"{prefix} cancellation reason does not name a failed run")
                continue
            continue  # justified cancellation: skip the strict schedule comparison
        if scheduled is None:
            _failure(failures, f"{prefix} is absent from the sealed schedule")
        else:
            planned = {
                "id": repetition.get("id"),
                "environmentId": repetition.get("environmentId"),
                "armOrder": repetition.get("armOrder"),
                "candidate": {
                    key: candidate.get(key)
                    for key in (*IDENTITIES, "frozenInputsSha256", "seed",
                                "resourceLimits")
                },
                "baseline": {
                    key: baseline.get(key)
                    for key in (*IDENTITIES, "frozenInputsSha256", "seed",
                                "resourceLimits")
                },
            }
            expected = {key: value for key, value in scheduled.items()
                        if key != "failFast"}
            if expected != planned:
                _failure(failures, f"{prefix} differs from the sealed schedule")
        for arm_name, arm in (("candidate", candidate), ("baseline", baseline)):
            if not isinstance(arm, dict):
                _failure(failures, f"{prefix} {arm_name} arm is malformed")
                continue
            for field in IDENTITIES:
                value = arm.get(field)
                if not isinstance(value, str) or not value:
                    _failure(failures, f"{prefix} {arm_name} {field} is missing")
                elif value in identity_values[field]:
                    _failure(failures, f"{prefix} reuses {field}: {value}")
                else:
                    identity_values[field].add(value)
        for field in ("frozenInputsSha256", "seed", "resourceLimits"):
            if candidate.get(field) != baseline.get(field):
                _failure(failures, f"{prefix} matched arms differ in {field}")
        if not _valid_hash(candidate.get("frozenInputsSha256")):
            _failure(failures, f"{prefix} frozen input digest is malformed")

        semantic = candidate.get("semantic", {})
        if (not isinstance(semantic.get("passed"), (int, float))
                or semantic.get("passed") < profile["semanticPassRate"] * 25
                or semantic.get("total") != 25):
            _failure(failures, f"{prefix} semantic channel is not "
                     f"{profile['semanticPassRate'] * 25:g}/25")
        checkpoints = semantic.get("checkpoints", [])
        if (not isinstance(checkpoints, list) or len(checkpoints) != 25
                or any(not item.get("actionId")
                       or item.get("assertionPassed") is not True
                       or not all(_valid_hash(item.get(key)) for key in
                                  ("rawSha256", "canonicalSha256",
                                   "transitionOutcomeSha256"))
                       for item in checkpoints if isinstance(item, dict))
                or any(not isinstance(item, dict) for item in checkpoints)):
            _failure(failures, f"{prefix} semantic checkpoints are incomplete")
        for label, key in (("canonical state", "canonicalStateSha256"),
                           ("transition", "transitionSha256")):
            value = semantic.get(key)
            if not _valid_hash(value):
                _failure(failures, f"{prefix} {label} hash is malformed")
            target = canonical_states if key == "canonicalStateSha256" else transition_hashes
            target.setdefault(environment_id, set()).add(value)

        settling = candidate.get("settling", {})
        frames = settling.get("completedFrameHashes", [])
        if not _valid_hash(settling.get("policySha256")):
            _failure(failures, f"{prefix} settling policy digest is malformed")
        if (not isinstance(settling.get("settleTimeoutMillis"), int)
                or settling["settleTimeoutMillis"] <= 0):
            _failure(failures, f"{prefix} settling timeout is not finite")
        if (not isinstance(frames, list) or len(frames) < 3
                or any(not isinstance(item, dict)
                       or set(item) != {"semanticRevision", "canonicalSha256",
                                        "layoutSha256", "scrollSha256",
                                        "framebufferSha256", "renderedFrame"}
                       or not isinstance(item["semanticRevision"], int)
                       or not isinstance(item["renderedFrame"], int)
                       or any(not _valid_hash(item[key]) for key in
                              ("canonicalSha256", "layoutSha256", "scrollSha256",
                               "framebufferSha256"))
                       for item in frames)
                or len({canonical_bytes(item) for item in frames[-3:]}) != 3
                or len({canonical_bytes({key: value for key, value in item.items()
                                        if key != "renderedFrame"})
                        for item in frames[-3:]}) != 1
                or not all(left["renderedFrame"] < right["renderedFrame"]
                           for left, right in zip(frames[-3:], frames[-2:]))):
            _failure(failures, f"{prefix} settling lacks three stable completed frames")

        captures = candidate.get("captures", {})
        for observation in OBSERVATIONS:
            capture = captures.get(observation, {})
            hashes = capture.get("pngSha256", [])
            if (not isinstance(hashes, list)
                    or len(hashes) != profile["pngDigests"]
                    or any(not _valid_hash(item) for item in hashes)
                    or len(set(hashes)) != 1
                    or capture.get("sessionId") != candidate.get("sessionId")
                    or not frames
                    or capture.get("semanticRevision") !=
                    frames[-1].get("semanticRevision")
                    or not isinstance(capture.get("completedFrame"), int)
                    or capture["completedFrame"] <= frames[-1].get("renderedFrame", -1)):
                _failure(failures, f"{prefix} capture channel is unstable for {observation}")
            else:
                capture_sets.setdefault((environment_id, observation), set()).add(hashes[0])

        channels = candidate.get("channels", {})
        if channels.get("automatedVisual") is not True:
            _failure(failures, f"{prefix} automated visual channel failed")
        if channels.get("structuralUsability") is not True:
            _failure(failures, f"{prefix} structural usability channel failed")

        review = repetition.get("humanReview", {})
        reviewers = review.get("reviewerIds", [])
        ratings = review.get("fidelityRatings", [])
        if (not isinstance(reviewers, list)
                or len(reviewers) < profile["reviewers"]
                or len(set(reviewers)) != len(reviewers)):
            _failure(failures, f"{prefix} human channel lacks "
                     f"{profile['reviewers']} blind reviewer(s)")
        if (not isinstance(ratings, list) or len(ratings) != len(reviewers)
                or not ratings
                or _median(ratings) < profile["medianFidelity"]):
            _failure(failures, f"{prefix} human fidelity channel is below "
                     f"{profile['medianFidelity']}")
        if (profile["unusableMajority"] and isinstance(reviewers, list)
                and review.get("unusableVotes", 0) > len(reviewers) / 2):
            _failure(failures, f"{prefix} human channel has a majority unusable verdict")
        if not all(_valid_hash(review.get(key)) for key in
                   ("responseSha256", "mappingSha256")):
            _failure(failures, f"{prefix} human review digests are malformed")
        if not (review.get("ratingsSealedAt") and review.get("unblindedAt")
                and review["ratingsSealedAt"] < review["unblindedAt"]):
            _failure(failures, f"{prefix} human ratings were not sealed before unblinding")
        if not _valid_hash(repetition.get("evidenceSha256")):
            _failure(failures, f"{prefix} retained evidence digest is malformed")

        candidate_costs = candidate.get("costs", {})
        baseline_costs = baseline.get("costs", {})
        for cost in COSTS:
            current = candidate_costs.get(cost)
            reference = baseline_costs.get(cost)
            if not isinstance(current, (int, float)) or not isinstance(reference, (int, float)):
                _failure(failures, f"{prefix} cost channel {cost} is unavailable")
                continue
            if cost in ceilings:
                ceiling = ceilings[cost]
                if cost == "inputTokens":
                    ceiling = min(ceiling, profile["costInputTokens"])
                elif cost == "builds":
                    ceiling = min(ceiling, profile["costBuilds"])
                elif cost == "launches":
                    ceiling = min(ceiling, profile["costLaunches"])
                elif (cost == "wallTimeMillis"
                        and profile.get("wallSeconds") is not None):
                    ceiling = min(ceiling, profile["wallSeconds"] * 1000)
                if current > ceiling:
                    _failure(failures, f"{prefix} cost channel {cost} exceeds its ceiling")
            raw_costs[cost].append({"pair": repetition.get("id"),
                                    "candidate": current, "baseline": reference})
            paired_deltas[cost].append(current - reference)
        run_results.append({
            "id": repetition.get("id"),
            "environmentId": environment_id,
            "passed": len(failures) == failure_start,
            "failures": failures[failure_start:],
            "channels": {
                "semantic": semantic,
                "settling": settling,
                "captures": captures,
                "automatedVisual": channels.get("automatedVisual"),
                "structuralUsability": channels.get("structuralUsability"),
                "humanReview": review,
                "costs": candidate_costs,
            },
            "evidenceSha256": repetition.get("evidenceSha256"),
        })

    declared_models = {
        environment.get("model")
        for environment in (precommitment.get("environments") or [])
        if isinstance(environment, dict)
    } - {None}
    used_models = {
        environment_by_id[repetition["environmentId"]].get("model")
        for repetition in repetitions
        if isinstance(repetition, dict)
        and repetition.get("status") != "cancelled"
        and repetition.get("environmentId") in environment_by_id
    } - {None}
    if len(used_models) != 1 or used_models != declared_models:
        _failure(failures, "model consistency check failed: "
                 f"declared={sorted(declared_models)} used={sorted(used_models)}")
    qualified_model = next(iter(used_models), None) or next(
        iter(declared_models), "unqualified")

    non_cancelled_count = sum(
        1 for repetition in repetitions
        if isinstance(repetition, dict)
        and repetition.get("status") != "cancelled")
    cross_schedule_required = (
        non_cancelled_count >= profile["requiredRepetitions"])

    stratum_report = []
    for identifier, items in sorted(strata.items()):
        if len(items) < profile["pairs"]:
            _failure(failures, f"Environment stratum {identifier} has fewer than "
                     f"{profile['pairs']} matched pairs")
        if cross_schedule_required:
            for collection, label in ((canonical_states, "canonical state"),
                                      (transition_hashes, "transition")):
                if len(collection.get(identifier, set())) != 1:
                    _failure(failures, f"Environment stratum {identifier} has divergent {label} hashes")
            for observation in OBSERVATIONS:
                if len(capture_sets.get((identifier, observation), set())) != 1:
                    _failure(failures,
                             f"Environment stratum {identifier} has cross-repetition capture drift "
                             f"for {observation}")
        stratum_report.append({"environmentId": identifier, "matchedPairs": len(items)})
    scheduled_ids = [
        item.get("id") for item in precommitment.get("schedule", [])
        if isinstance(item, dict)
    ]
    result_ids = [
        item.get("id") for item in repetitions if isinstance(item, dict)
    ]
    if (len(scheduled_ids) < profile["pairs"]
            or len(set(scheduled_ids)) != len(scheduled_ids)
            or scheduled_ids != result_ids):
        _failure(failures, "Result set is not the complete sealed schedule")

    summaries = {}
    for cost in COSTS:
        values = paired_deltas[cost]
        summaries[cost] = {
            "rawPairs": raw_costs[cost],
            "pairedDeltas": values,
            "medianDelta": _median(values) if values else None,
            "deltaRange": _range(values) if values else None,
        }
    evidence_material = {
        "manifestSha256": manifest.get("manifestSha256"),
        "precommitmentSha256": precommitment.get("precommitmentSha256"),
        "retainedControlsSha256": manifest.get("retainedControlsSha256"),
        "evidenceSha256": [item.get("evidenceSha256") for item in repetitions
                           if isinstance(item, dict)],
        "artifacts": artifacts,
    }
    decision = {
        "schemaVersion": DECISION_SCHEMA,
        "candidateCommit": manifest.get("candidateCommit"),
        "candidateSourceSha256": manifest.get("candidateSourceSha256"),
        "releaseVersion": manifest.get("releaseVersion"),
        "manifestSha256": manifest.get("manifestSha256"),
        "precommitmentSha256": precommitment.get("precommitmentSha256"),
        "evidenceDigest": sha256_bytes(canonical_bytes(evidence_material)),
        "passed": not failures,
        "failures": failures,
        "strata": stratum_report,
        "runs": run_results,
        "statistics": {
            "method": manifest.get("statisticalMethod"),
            "scope": (f"observed matched pairs only, model {qualified_model}; "
                      "no population or universal determinism claim"),
            "costs": summaries,
        },
    }
    decision["decisionSha256"] = sha256_bytes(canonical_bytes(decision))
    return decision


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--precommitment", required=True, type=Path)
    verify.add_argument("--manifest", required=True, type=Path)
    verify.add_argument("--decision", required=True, type=Path)
    verify.add_argument("--candidate-commit", required=True)
    verify.add_argument("--candidate-source-sha256", required=True)
    verify.add_argument("--release-version", required=True)
    verify.add_argument("--evidence-root", required=True, type=Path)
    create = subparsers.add_parser("create-decision")
    create.add_argument("--precommitment", required=True, type=Path)
    create.add_argument("--manifest", required=True, type=Path)
    create.add_argument("--output", required=True, type=Path)
    create.add_argument("--evidence-root", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        manifest = json.loads(arguments.manifest.read_text())
        precommitment = json.loads(arguments.precommitment.read_text())
        if arguments.command == "create-decision":
            decision = evaluate(
                manifest, precommitment, manifest.get("candidateCommit", ""),
                manifest.get("releaseVersion", ""))
            if arguments.output.exists():
                raise ValueError("decision output already exists")
            arguments.output.write_bytes(canonical_bytes(decision))
            verify_artifacts(manifest, arguments.evidence_root)
            return 0 if decision["passed"] else 1
        verify_artifacts(manifest, arguments.evidence_root)
        decision = evaluate(manifest, precommitment, arguments.candidate_commit,
                            arguments.release_version,
                            arguments.candidate_source_sha256)
        if arguments.decision.read_bytes() != canonical_bytes(decision):
            raise ValueError("precommitted decision differs from regenerated decision")
        if not decision["passed"]:
            raise ValueError("repeatability decision failed: " + "; ".join(decision["failures"]))
        print(json.dumps({"status": "passed", "decisionSha256": decision["decisionSha256"]}))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"release-gate: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
