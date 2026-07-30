#!/usr/bin/env python3
"""Validate and lock blinded ratings, then produce separate post-lock outcome channels."""

import argparse
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import secrets
import statistics
import sys

SCRIPT_ROOT = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("agentic_palisade_blind", SCRIPT_ROOT / "build-blind-review.py")
BLIND = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(BLIND)

LABELS = tuple("ABCDEF")
RATINGS_VERSION = "agentic-palisade/human-ratings-v1"
LOCK_VERSION = "agentic-palisade/review-lock-v1"
REPORT_VERSION = "agentic-palisade/final-report-v1"
SHA256 = re.compile(r"[0-9a-f]{64}")


def _json(path, name):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid {name}: {error}") from error


def _strict_keys(value, required, name):
    if not isinstance(value, dict) or set(value) != set(required):
        raise ValueError(f"{name} must contain exactly: {', '.join(required)}")


def _integer(value, minimum, maximum, name):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be an integer from {minimum} through {maximum}")


def _verify_public_files(review_dir, manifest):
    expected_files = {"manifest.json"}
    for field, expected_name in (
            ("reviewForm", "review-form.json"),
            ("responseSchema", "human-ratings.schema.json")):
        identity = manifest[field]
        if not isinstance(identity, dict) or set(identity) != {"file", "sha256"}:
            raise ValueError(f"invalid {field} identity")
        if identity["file"] != expected_name or not SHA256.fullmatch(identity["sha256"]):
            raise ValueError(f"invalid {field} identity")
        support_path = (review_dir / identity["file"]).resolve()
        if (not BLIND._inside(support_path, review_dir)
                or not support_path.is_file()
                or BLIND.sha256_file(support_path) != identity["sha256"]):
            raise ValueError(f"support file hash mismatch: {expected_name}")
        expected_files.add(expected_name)
    for reference in manifest["references"]:
        if not isinstance(reference, dict):
            raise ValueError("invalid public reference")
        path = (review_dir / reference.get("file", "")).resolve()
        if not BLIND._inside(path, review_dir) or not path.is_file():
            raise ValueError("public reference path is invalid")
        if BLIND.sha256_file(path) != reference.get("sha256") or path.stat().st_size != reference.get("bytes"):
            raise ValueError("public reference hash mismatch")
        if BLIND.png_dimensions(path) != (reference.get("width"), reference.get("height")):
            raise ValueError("public reference dimensions mismatch")
        expected_files.add(path.relative_to(review_dir).as_posix())
    for candidate in manifest["candidates"]:
        captures = candidate.get("captures")
        if not isinstance(captures, list) or len(captures) not in (0, 15):
            raise ValueError(
                "public candidate must contain zero or fifteen captures")
        if not captures:
            if candidate.get("automatedVisual") != []:
                raise ValueError(
                    "capture-free candidate must not claim automated visuals")
            continue
        counts = {reference_id: 0 for reference_id in BLIND.REQUIRED_REFERENCES}
        for capture in captures:
            if not isinstance(capture, dict) or capture.get("referenceId") not in counts:
                raise ValueError("invalid public capture")
            path = (review_dir / capture.get("file", "")).resolve()
            if not BLIND._inside(path, review_dir) or not path.is_file():
                raise ValueError("public capture path is invalid")
            if BLIND.sha256_file(path) != capture.get("sha256") or path.stat().st_size != capture.get("bytes"):
                raise ValueError("public capture hash mismatch")
            if BLIND.png_dimensions(path) != (capture.get("width"), capture.get("height")):
                raise ValueError("public capture dimensions mismatch")
            counts[capture["referenceId"]] += 1
            expected_files.add(path.relative_to(review_dir).as_posix())
        if set(counts.values()) != {5}:
            raise ValueError("every canonical state requires five public captures")
    actual_files = {path.relative_to(review_dir).as_posix() for path in review_dir.rglob("*") if path.is_file()}
    allowed_extra = {manifest["responseFile"]} if (review_dir / manifest["responseFile"]).is_file() else set()
    if actual_files != expected_files | allowed_extra:
        raise ValueError("review package contains missing or unexpected files")


def _load_public_manifest(review_dir):
    review_dir = Path(review_dir).resolve()
    BLIND.scan_package(review_dir)
    manifest_path = review_dir / "manifest.json"
    manifest = _json(manifest_path, "review manifest")
    required = {
        "schemaVersion", "hashAlgorithm", "labels", "references", "candidates",
        "matchedPairs", "reviewForm", "responseFile", "responseSchema",
    }
    if not isinstance(manifest, dict) or set(manifest) != required:
        raise ValueError("review manifest has an invalid field set")
    if manifest["schemaVersion"] != BLIND.SCHEMA_VERSION or manifest["hashAlgorithm"] != "SHA-256":
        raise ValueError("unsupported review manifest")
    if manifest["labels"] != list(LABELS):
        raise ValueError("review manifest labels are incomplete")
    candidate_labels = [candidate.get("label") for candidate in manifest["candidates"] if isinstance(candidate, dict)]
    if sorted(candidate_labels) != list(LABELS) or len(candidate_labels) != 6:
        raise ValueError("review manifest candidates are incomplete")
    expected_pair_ids = [f"pair-{pair}" for pair in (1, 2, 3)]
    if [pair.get("id") for pair in manifest["matchedPairs"] if isinstance(pair, dict)] != expected_pair_ids:
        raise ValueError("review manifest matched pairs are incomplete")
    paired_labels = []
    for pair in manifest["matchedPairs"]:
        if set(pair) != {"id", "candidates"} or not isinstance(pair["candidates"], list) or len(pair["candidates"]) != 2:
            raise ValueError("review manifest contains an invalid matched pair")
        paired_labels.extend(pair["candidates"])
    if sorted(paired_labels) != list(LABELS):
        raise ValueError("review manifest matched pairs are not a label partition")
    _verify_public_files(review_dir, manifest)
    return manifest, BLIND.sha256_file(manifest_path)


def validate_response(review_dir, ratings_path):
    """Apply strict structural and package-aware semantic validation."""
    review_dir = Path(review_dir).resolve()
    ratings_path = Path(ratings_path).resolve()
    if ratings_path != (review_dir / "human-ratings.json").resolve():
        raise ValueError("ratings must use the review package human-ratings.json path")
    manifest, manifest_hash = _load_public_manifest(review_dir)
    response = _json(ratings_path, "human ratings")
    required = {"schemaVersion", "packageManifestSha256", "fidelity", "ranking", "preferred"}
    allowed = required | {"comments"}
    if not isinstance(response, dict) or not required.issubset(response) or not set(response).issubset(allowed):
        raise ValueError("human ratings contains missing or unknown fields")
    if response["schemaVersion"] != RATINGS_VERSION:
        raise ValueError("unsupported human ratings schema")
    if response["packageManifestSha256"] != manifest_hash:
        raise ValueError("human ratings package manifest hash mismatch")
    for field, minimum, maximum in (("fidelity", 1, 7), ("ranking", 1, 6)):
        _strict_keys(response[field], LABELS, field)
        for label in LABELS:
            _integer(response[field][label], minimum, maximum, f"{field}.{label}")
    if set(response["ranking"].values()) != set(range(1, 7)):
        raise ValueError("ranking must be a bijection over 1 through 6")
    expected_pairs = {pair["id"]: set(pair["candidates"]) for pair in manifest["matchedPairs"]}
    _strict_keys(response["preferred"], tuple(expected_pairs), "preferred")
    for pair_id, selected in response["preferred"].items():
        if selected not in expected_pairs[pair_id]:
            raise ValueError(f"preferred.{pair_id} must select one candidate from that matched pair")
    comments = response.get("comments", {})
    if not isinstance(comments, dict) or not set(comments).issubset(set(LABELS) | {"overall"}):
        raise ValueError("comments contains an unknown entry")
    for key, comment in comments.items():
        if not isinstance(comment, str) or len(comment) > 2000:
            raise ValueError(f"comments.{key} exceeds the 2000 character bound")
    return response


def _atomic_private_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise ValueError(f"private lock already exists: {path.name}")
    temporary = path.parent / f".{path.name}.tmp-{secrets.token_hex(8)}"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(BLIND.canonical_bytes(value))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def _validate_private_mapping(run_root, review_dir, manifest, manifest_hash, mapping_path):
    mapping_path = Path(mapping_path)
    if not mapping_path.is_file() or not _mode_is_private(mapping_path):
        raise ValueError("private mapping must exist with restricted permissions")
    mapping = _json(mapping_path, "private mapping")
    required_mapping = {
        "schemaVersion", "seedHex", "seedSha256", "packageManifestSha256",
        "benchmarkManifestSha256", "labels", "inputHashes",
    }
    if (not isinstance(mapping, dict)
            or set(mapping) != required_mapping
            or mapping["schemaVersion"] != BLIND.MAPPING_VERSION):
        raise ValueError("invalid private mapping")
    if mapping["packageManifestSha256"] != manifest_hash:
        raise ValueError("private mapping package hash mismatch")
    try:
        seed = bytes.fromhex(mapping["seedHex"])
    except (TypeError, ValueError) as error:
        raise ValueError("private mapping seed is invalid") from error
    if len(seed) < 32 or BLIND.sha256_bytes(seed) != mapping["seedSha256"]:
        raise ValueError("private mapping seed hash mismatch")
    if set(mapping["labels"]) != set(LABELS):
        raise ValueError("private mapping labels are incomplete")

    _benchmark_manifest, _references, runs, current_hashes = BLIND._load_inputs(run_root)
    if BLIND.sha256_file(Path(run_root) / "benchmark-manifest.json") != mapping["benchmarkManifestSha256"]:
        raise ValueError("frozen benchmark manifest hash mismatch")
    if current_hashes != mapping["inputHashes"]:
        raise ValueError("frozen run or evaluation input hash mismatch")
    by_id = {run["runId"]: run for run in runs}
    expected_ids = BLIND.fisher_yates(seed, BLIND.canonical_run_ids(runs))
    expected_by_label = dict(zip(LABELS, expected_ids))
    label_runs = {}
    for label in LABELS:
        entry = mapping["labels"][label]
        if (not isinstance(entry, dict)
                or set(entry) != {"runId", "pair", "treatment", "runRecordSha256", "evaluationSha256"}):
            raise ValueError("private mapping entry is invalid")
        if entry["runId"] != expected_by_label[label]:
            raise ValueError("private mapping deterministic assignment mismatch")
        run = by_id.get(entry["runId"])
        if run is None or entry["pair"] != run["pair"] or entry["treatment"] != run["treatment"]:
            raise ValueError("private mapping no longer matches frozen runs")
        if entry["runRecordSha256"] != run["runRecordHash"] or entry["evaluationSha256"] != run["evaluationHash"]:
            raise ValueError("private mapping input hash mismatch")
        label_runs[label] = run

    expected_pairs = {
        f"pair-{pair}": sorted(label for label, run in label_runs.items() if run["pair"] == pair)
        for pair in (1, 2, 3)
    }
    if {pair["id"]: pair["candidates"] for pair in manifest["matchedPairs"]} != expected_pairs:
        raise ValueError("public matched pairs do not match private mapping")
    public_by_label = {candidate["label"]: candidate for candidate in manifest["candidates"]}
    for label in LABELS:
        public = public_by_label[label]
        run = label_runs[label]
        if (set(public) != {
                "label", "captures", "automatedVisual", "structuralUsability",
                "traceTaxonomy"}
                or public["automatedVisual"] != run["automatedVisual"]
                or public["structuralUsability"] != run["structuralUsability"]
                or public["traceTaxonomy"] != run["traceTaxonomy"]):
            raise ValueError("public label capture binding mismatch")
        if len(public["captures"]) != len(run["captures"]):
            raise ValueError("public label capture binding mismatch")
        for number, (packaged, source) in enumerate(zip(public["captures"], run["captures"]), 1):
            expected = {
                "referenceId": source["referenceId"], "stateId": source["stateId"],
                "viewportId": source["viewportId"], "width": source["width"],
                "height": source["height"], "repeat": source["repeat"],
                "file": f"candidates/{label}/capture-{number:02d}.png",
                "bytes": source["bytes"], "sha256": source["sha256"],
            }
            if packaged != expected:
                raise ValueError("public label capture binding mismatch")
    return mapping, label_runs


def lock_response(run_root, review_dir, mapping_path, ratings_path, lock_path):
    """Validate a complete response and atomically bind every reviewed public byte."""
    review_dir = Path(review_dir).resolve()
    lock_path = Path(lock_path).resolve()
    if BLIND._inside(lock_path, review_dir):
        raise ValueError("private lock must be outside the review package")
    validate_response(review_dir, ratings_path)
    manifest, manifest_hash = _load_public_manifest(review_dir)
    _validate_private_mapping(run_root, review_dir, manifest, manifest_hash, mapping_path)
    lock = {
        "schemaVersion": LOCK_VERSION,
        "packageManifestSha256": manifest_hash,
        "reviewFormSha256": manifest["reviewForm"]["sha256"],
        "responseSchemaSha256": manifest["responseSchema"]["sha256"],
        "responseSha256": BLIND.sha256_file(ratings_path),
    }
    _atomic_private_json(lock_path, lock)
    return lock


def _mode_is_private(path):
    return Path(path).stat().st_mode & 0o077 == 0


def _load_sealed(run_root, review_dir, mapping_path, ratings_path, lock_path):
    if not Path(lock_path).is_file() or not _mode_is_private(lock_path):
        raise ValueError("private lock must exist with restricted permissions")
    manifest, manifest_hash = _load_public_manifest(review_dir)
    response = validate_response(review_dir, ratings_path)
    lock = _json(lock_path, "review lock")
    lock_fields = (
        "schemaVersion", "packageManifestSha256", "reviewFormSha256",
        "responseSchemaSha256", "responseSha256",
    )
    _strict_keys(lock, lock_fields, "review lock")
    if lock["schemaVersion"] != LOCK_VERSION:
        raise ValueError("unsupported review lock")
    expected_lock_hashes = {
        "packageManifestSha256": manifest_hash,
        "reviewFormSha256": manifest["reviewForm"]["sha256"],
        "responseSchemaSha256": manifest["responseSchema"]["sha256"],
        "responseSha256": BLIND.sha256_file(ratings_path),
    }
    if any(lock[name] != digest for name, digest in expected_lock_hashes.items()):
        raise ValueError("review lock hash mismatch")
    mapping, label_runs = _validate_private_mapping(
        run_root, review_dir, manifest, manifest_hash, mapping_path)
    return manifest, response, mapping, label_runs, lock


def _number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _flatten_numbers(value, prefix=""):
    flattened = {}
    if isinstance(value, dict):
        for key, item in value.items():
            child = f"{prefix}.{key}" if prefix else key
            flattened.update(_flatten_numbers(item, child))
    elif _number(value):
        flattened[prefix] = value
    return flattened


def _range(values):
    values = list(values)
    return {"median": statistics.median(values), "minimum": min(values), "maximum": max(values)}


def _delta(baseline, harness):
    return {"baseline": baseline, "harness": harness, "harnessMinusBaseline": harness - baseline}


def _arms(rows, fields):
    return {
        field: {arm: _range(row[field] for row in rows if row["treatment"] == arm) for arm in ("baseline", "harness")}
        for field in fields
    }


def _channel(raw, summaries, paired):
    return {
        "raw": raw,
        "armSummaries": summaries,
        "pairedDeltas": paired,
        "sampleSizePerTreatment": 3,
        "interpretation": (
            "Directional matched-pair summary for this retained batch; "
            "no significance, population, superiority, or causal inference."),
    }


def _functional_channel(label_runs):
    raw = []
    for label, run in label_runs.items():
        functional = run["evaluation"]["functional"]
        total = functional["total"]
        raw.append({
            "label": label, "runId": run["runId"], "pair": run["pair"], "treatment": run["treatment"],
            "evaluationStatus": run["evaluation"].get("status"), "exitClassification": run["record"]["exit"]["classification"],
            "passed": functional["passed"], "total": total,
            "passRate": functional["passed"] / total if total else 0.0,
            "failurePhases": [failure.get("phase") for failure in run["record"].get("failures", [])],
        })
    fields = ("passed", "total", "passRate")
    paired = []
    for pair in (1, 2, 3):
        baseline = next(row for row in raw if row["pair"] == pair and row["treatment"] == "baseline")
        harness = next(row for row in raw if row["pair"] == pair and row["treatment"] == "harness")
        paired.append({"pair": pair, "baselineLabel": baseline["label"], "harnessLabel": harness["label"],
                       **{field: _delta(baseline[field], harness[field]) for field in fields}})
    return _channel(raw, _arms(raw, fields), paired)


def _visual_arm_range(values, label_runs, reference_id, metric, treatment):
    observed = [
        values[(label, reference_id, metric)]
        for label, run in label_runs.items()
        if run["treatment"] == treatment
        and (label, reference_id, metric) in values
    ]
    return _range(observed) if observed else None


def _automated_visual_channel(label_runs):
    raw = []
    values = {}
    for label, run in label_runs.items():
        outcomes = []
        for visual in run["evaluation"]["visual"]:
            metrics = visual["metrics"]
            outcomes.append({"referenceId": visual["referenceId"], "metrics": metrics})
            for metric, value in _flatten_numbers(metrics).items():
                values[(label, visual["referenceId"], metric)] = value
        raw.append({"label": label, "runId": run["runId"], "pair": run["pair"],
                    "treatment": run["treatment"], "outcomes": outcomes})
    summaries = []
    for reference_id in BLIND.REQUIRED_REFERENCES:
        metric_names = sorted({
            metric for _label, reference, metric in values if reference == reference_id
        })
        for metric in metric_names:
            summaries.append({
                "referenceId": reference_id,
                "metric": metric,
                "baseline": _visual_arm_range(
                    values, label_runs, reference_id, metric, "baseline"),
                "harness": _visual_arm_range(
                    values, label_runs, reference_id, metric, "harness"),
            })
    paired = []
    for pair in (1, 2, 3):
        baseline_label = next(
            label for label, run in label_runs.items()
            if run["pair"] == pair and run["treatment"] == "baseline")
        harness_label = next(
            label for label, run in label_runs.items()
            if run["pair"] == pair and run["treatment"] == "harness")
        for reference_id in BLIND.REQUIRED_REFERENCES:
            names = sorted(
                metric for label, reference, metric in values
                if label == baseline_label
                and reference == reference_id
                and (harness_label, reference_id, metric) in values)
            paired.append({
                "pair": pair,
                "referenceId": reference_id,
                "baselineLabel": baseline_label,
                "harnessLabel": harness_label,
                "metrics": {
                    metric: _delta(
                        values[(baseline_label, reference_id, metric)],
                        values[(harness_label, reference_id, metric)])
                    for metric in names
                },
            })
    return _channel(raw, summaries, paired)


def _structural_channel(label_runs):
    raw = []
    for label, run in label_runs.items():
        outcomes = run["evaluation"].get("structural", [])
        raw.append({
            "label": label,
            "runId": run["runId"],
            "pair": run["pair"],
            "treatment": run["treatment"],
            "outcomes": outcomes,
        })
    paired = []
    for pair in (1, 2, 3):
        pair_rows = [row for row in raw if row["pair"] == pair]
        paired.append({
            "pair": pair,
            "baselineLabel": next(
                row["label"] for row in pair_rows if row["treatment"] == "baseline"),
            "harnessLabel": next(
                row["label"] for row in pair_rows if row["treatment"] == "harness"),
            "comparison": "independent pass/fail outcomes retained without weighting",
        })
    return _channel(
        raw,
        {
            arm: {
                status: sum(
                    1 for row in raw if row["treatment"] == arm
                    for outcome in row["outcomes"]
                    if outcome.get("status") == status)
                for status in ("PASS", "FAIL", "INCOMPLETE", "STALE", "UNSTABLE")
            }
            for arm in ("baseline", "harness")
        },
        paired)


def _human_channel(label_runs, response):
    raw = [{"label": label, "runId": run["runId"], "pair": run["pair"], "treatment": run["treatment"],
            "fidelity": response["fidelity"][label], "ranking": response["ranking"][label]}
           for label, run in label_runs.items()]
    fields = ("fidelity", "ranking")
    paired = []
    for pair in (1, 2, 3):
        baseline = next(row for row in raw if row["pair"] == pair and row["treatment"] == "baseline")
        harness = next(row for row in raw if row["pair"] == pair and row["treatment"] == "harness")
        selected = response["preferred"][f"pair-{pair}"]
        paired.append({
            "pair": pair, "baselineLabel": baseline["label"], "harnessLabel": harness["label"],
            "preferredLabel": selected, "preferredTreatment": label_runs[selected]["treatment"],
            **{field: _delta(baseline[field], harness[field]) for field in fields},
        })
    return _channel(raw, _arms(raw, fields), paired)


def _telemetry_values(record):
    telemetry = record["telemetry"]
    result = {
        "wallTimeSeconds": record["wallTimeSeconds"], "edits": telemetry["edits"], "builds": telemetry["builds"],
        "launches": telemetry["launches"], "screenshots": telemetry["screenshots"],
        "failedOperations": len(telemetry["failedOperations"]), "failureCount": len(record.get("failures", [])),
    }
    for name, category in telemetry["tokens"].items():
        value = category.get("value")
        result[f"tokens.{name}"] = (
            value
            if category.get("status") == "available" and _number(value)
            else None)
    for name, count in telemetry.get("toolCalls", {}).items():
        if _number(count):
            result[f"toolCalls.{name}"] = count
    return result


def _telemetry_arm_range(values, label_runs, name, treatment):
    observed = [
        values[label][name]
        for label, run in label_runs.items()
        if run["treatment"] == treatment
        and name in values[label]
        and _number(values[label][name])
    ]
    return _range(observed) if observed else None


def _telemetry_channel(label_runs):
    values = {label: _telemetry_values(run["record"]) for label, run in label_runs.items()}
    raw = [{
        "label": label, "runId": run["runId"], "pair": run["pair"],
        "treatment": run["treatment"], "metrics": values[label],
    } for label, run in label_runs.items()]
    names = sorted(set().union(*(set(item) for item in values.values())))
    summaries = {
        name: {
            arm: _telemetry_arm_range(
                values, label_runs, name, arm)
            for arm in ("baseline", "harness")
        }
        for name in names
    }
    paired = []
    for pair in (1, 2, 3):
        baseline_label = next(
            label for label, run in label_runs.items()
            if run["pair"] == pair and run["treatment"] == "baseline")
        harness_label = next(
            label for label, run in label_runs.items()
            if run["pair"] == pair and run["treatment"] == "harness")
        shared = sorted(
            name for name in set(values[baseline_label]) & set(values[harness_label])
            if _number(values[baseline_label][name])
            and _number(values[harness_label][name]))
        item = {"pair": pair, "baselineLabel": baseline_label, "harnessLabel": harness_label}
        item.update({
            name: _delta(values[baseline_label][name], values[harness_label][name])
            for name in shared
        })
        paired.append(item)
    return _channel(raw, summaries, paired)


def _trace_channel(label_runs):
    raw = []
    for label, run in label_runs.items():
        taxonomy = json.loads(json.dumps(
            run["record"]["telemetry"]["traceTaxonomy"]))
        taxonomy["attributions"]["semantic"] = (
            run["traceTaxonomy"]["attributions"]["semantic"])
        taxonomy["attributions"]["rendering"] = (
            run["traceTaxonomy"]["attributions"]["rendering"])
        taxonomy["joins"]["evaluation"] = {
            "status": "available",
            "identity": {
                "sha256": run["evaluationHash"],
                "schemaVersion": run["evaluation"]["schemaVersion"],
                "candidateSha256": run["evaluation"]["candidate"]["sha256"],
                "corpusSha256": run["evaluation"]["corpus"]["sha256"],
            },
        }
        raw.append({
            "label": label,
            "runId": run["runId"],
            "pair": run["pair"],
            "treatment": run["treatment"],
            "taxonomy": taxonomy,
        })
    family_counts = {
        arm: {
            family: sum(
                len(row["taxonomy"]["attributions"].get(family, []))
                for row in raw if row["treatment"] == arm)
            for family in ("capture", "semantic", "rendering", "workflow-loop")
        }
        for arm in ("baseline", "harness")
    }
    paired = []
    for pair in (1, 2, 3):
        baseline = next(
            row for row in raw
            if row["pair"] == pair and row["treatment"] == "baseline")
        harness = next(
            row for row in raw
            if row["pair"] == pair and row["treatment"] == "harness")
        paired.append({
            "pair": pair,
            "baselineLabel": baseline["label"],
            "harnessLabel": harness["label"],
            "pairedFields": [
                "capture", "semantic", "rendering", "workflow-loop"],
            "method": "directional matched-pair association; no causal inference",
        })
    return _channel(raw, family_counts, paired)


def _human_evidence_boundary(label_runs, response):
    fidelity = response["fidelity"]
    lowest = min(fidelity.values())
    tie_break_only = sorted(
        label for label, value in fidelity.items()
        if value == lowest
    )
    first = min(response["ranking"], key=response["ranking"].get)
    preferred_to = None
    preferred_label = None
    for pair, selected in response["preferred"].items():
        if selected == first:
            preferred_to = pair
            pair_number = int(pair.removeprefix("pair-"))
            preferred_label = next(
                label for label, run in label_runs.items()
                if run["pair"] == pair_number and label != first)
            break
    return {
        "firstLabel": first,
        "firstFidelity": fidelity[first],
        "preferredPair": preferred_to,
        "preferredToLabel": preferred_label,
        "lowestFidelity": lowest,
        "tieBreakOnlyLabels": tie_break_only if len(tie_break_only) > 1 else [],
        "ordinalCorrelationExclusions": (
            tie_break_only if len(tie_break_only) > 1 else []),
        "rule": (
            "Equal low-fidelity unusable candidates are not ordinal evidence; "
            "unique ranks within that group are deterministic tie-breaks."),
    }


def _qualitative(label_runs, response):
    comments = response.get("comments", {})
    associated = []
    for label in LABELS:
        if label in comments:
            run = label_runs[label]
            associated.append({
                "label": label, "runId": run["runId"], "pair": run["pair"],
                "treatment": run["treatment"], "comment": comments[label],
            })
    return {"candidateComments": associated, "overall": comments.get("overall")}


def _atomic_public_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise ValueError(f"final report already exists: {path.name}")
    temporary = path.parent / f".{path.name}.tmp-{secrets.token_hex(8)}"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(BLIND.canonical_bytes(value))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def unblind(run_root, review_dir, mapping_path, ratings_path, lock_path, output_path):
    """Refuse pre-lock/mutated inputs and write distinct post-lock analytical channels."""
    _manifest, response, mapping, label_runs, lock = _load_sealed(
        Path(run_root).resolve(), Path(review_dir).resolve(), Path(mapping_path).resolve(),
        Path(ratings_path).resolve(), Path(lock_path).resolve())
    report = {
        "schemaVersion": REPORT_VERSION,
        "packageManifestSha256": mapping["packageManifestSha256"],
        "responseSha256": lock["responseSha256"],
        "channels": {
            "functional": _functional_channel(label_runs),
            "automatedVisual": _automated_visual_channel(label_runs),
            "structuralUsability": _structural_channel(label_runs),
            "humanVisual": _human_channel(label_runs, response),
            "telemetryTreatment": _telemetry_channel(label_runs),
            "traceTaxonomy": _trace_channel(label_runs),
        },
        "qualitativeAssociations": _qualitative(label_runs, response),
        "interpretation": {
            "sampleSizePerTreatment": 3,
            "pairedDeltaConvention": "harness minus baseline",
            "humanEvidenceBoundary": _human_evidence_boundary(
                label_runs, response),
            "conclusions": (
                "Directional only for n=3 matched pairs in this retained batch; "
                "functional, raster similarity, structural usability, human "
                "fidelity, cost, and trace taxonomy measure different properties "
                "and remain separate. No significance or causal inference."
            ),
        },
    }
    _atomic_public_json(output_path, report)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    lock_parser = subparsers.add_parser(
        "lock", help="validate and atomically seal a complete response")
    lock_parser.add_argument("--run-root", required=True, type=Path)
    lock_parser.add_argument("--review-dir", required=True, type=Path)
    lock_parser.add_argument("--mapping", required=True, type=Path)
    lock_parser.add_argument("--ratings", required=True, type=Path)
    lock_parser.add_argument("--lock", required=True, type=Path)
    report_parser = subparsers.add_parser(
        "unblind", help="verify the seal and create the final report")
    report_parser.add_argument("--run-root", required=True, type=Path)
    report_parser.add_argument("--review-dir", required=True, type=Path)
    report_parser.add_argument("--mapping", required=True, type=Path)
    report_parser.add_argument("--ratings", required=True, type=Path)
    report_parser.add_argument("--lock", required=True, type=Path)
    report_parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "lock":
            lock_response(
                arguments.run_root, arguments.review_dir, arguments.mapping,
                arguments.ratings, arguments.lock)
            print(json.dumps({"status": "locked", "lock": str(arguments.lock)}))
        else:
            unblind(
                arguments.run_root, arguments.review_dir, arguments.mapping,
                arguments.ratings, arguments.lock, arguments.output)
            print(json.dumps({"status": "unblinded", "report": str(arguments.output)}))
        return 0
    except (OSError, ValueError) as error:
        print(f"unblind-report: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
