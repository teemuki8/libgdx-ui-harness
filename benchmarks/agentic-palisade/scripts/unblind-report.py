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
    expected_files = {"manifest.json", "review-form.json", "human-ratings.schema.json"}
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
        if not isinstance(captures, list) or len(captures) != 15:
            raise ValueError("every public candidate requires fifteen captures")
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
    required = ("schemaVersion", "packageManifestSha256", "fidelity", "ranking", "preferred", "comments")
    _strict_keys(response, required, "human ratings")
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
    comments = response["comments"]
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


def lock_response(review_dir, ratings_path, lock_path):
    """Validate a complete response and atomically bind its bytes to the package manifest."""
    review_dir = Path(review_dir).resolve()
    lock_path = Path(lock_path).resolve()
    if BLIND._inside(lock_path, review_dir):
        raise ValueError("private lock must be outside the review package")
    validate_response(review_dir, ratings_path)
    lock = {
        "schemaVersion": LOCK_VERSION,
        "packageManifestSha256": BLIND.sha256_file(review_dir / "manifest.json"),
        "responseSha256": BLIND.sha256_file(ratings_path),
    }
    _atomic_private_json(lock_path, lock)
    return lock


def _mode_is_private(path):
    return Path(path).stat().st_mode & 0o077 == 0


def _load_sealed(run_root, review_dir, mapping_path, ratings_path, lock_path):
    for private_path in (mapping_path, lock_path):
        if not Path(private_path).is_file() or not _mode_is_private(private_path):
            raise ValueError("private mapping and lock must exist with restricted permissions")
    manifest, manifest_hash = _load_public_manifest(review_dir)
    response = validate_response(review_dir, ratings_path)
    lock = _json(lock_path, "review lock")
    _strict_keys(lock, ("schemaVersion", "packageManifestSha256", "responseSha256"), "review lock")
    if lock["schemaVersion"] != LOCK_VERSION:
        raise ValueError("unsupported review lock")
    if lock["packageManifestSha256"] != manifest_hash or lock["responseSha256"] != BLIND.sha256_file(ratings_path):
        raise ValueError("review lock hash mismatch")
    mapping = _json(mapping_path, "private mapping")
    required_mapping = {
        "schemaVersion", "seedHex", "seedSha256", "packageManifestSha256",
        "benchmarkManifestSha256", "labels", "inputHashes",
    }
    if not isinstance(mapping, dict) or set(mapping) != required_mapping or mapping["schemaVersion"] != BLIND.MAPPING_VERSION:
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
    mapped_ids = []
    for label in LABELS:
        entry = mapping["labels"][label]
        if not isinstance(entry, dict) or set(entry) != {"runId", "pair", "treatment", "runRecordSha256", "evaluationSha256"}:
            raise ValueError("private mapping entry is invalid")
        run = by_id.get(entry["runId"])
        if run is None or entry["pair"] != run["pair"] or entry["treatment"] != run["treatment"]:
            raise ValueError("private mapping no longer matches frozen runs")
        if entry["runRecordSha256"] != run["runRecordHash"] or entry["evaluationSha256"] != run["evaluationHash"]:
            raise ValueError("private mapping input hash mismatch")
        mapped_ids.append(entry["runId"])
    if len(set(mapped_ids)) != 6:
        raise ValueError("private mapping is not bijective")
    return manifest, response, mapping, {label: by_id[mapping["labels"][label]["runId"]] for label in LABELS}, lock


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
    return {"raw": raw, "armSummaries": _arms(raw, fields), "pairedDeltas": paired}


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
        raw.append({"label": label, "runId": run["runId"], "pair": run["pair"], "treatment": run["treatment"], "outcomes": outcomes})
    summaries = []
    for reference_id in BLIND.REQUIRED_REFERENCES:
        metric_names = sorted({metric for _label, reference, metric in values if reference == reference_id})
        for metric in metric_names:
            summaries.append({
                "referenceId": reference_id, "metric": metric,
                "baseline": _range(values[(label, reference_id, metric)] for label, run in label_runs.items() if run["treatment"] == "baseline"),
                "harness": _range(values[(label, reference_id, metric)] for label, run in label_runs.items() if run["treatment"] == "harness"),
            })
    paired = []
    for pair in (1, 2, 3):
        baseline_label = next(label for label, run in label_runs.items() if run["pair"] == pair and run["treatment"] == "baseline")
        harness_label = next(label for label, run in label_runs.items() if run["pair"] == pair and run["treatment"] == "harness")
        for reference_id in BLIND.REQUIRED_REFERENCES:
            names = sorted(metric for label, reference, metric in values if label == baseline_label and reference == reference_id and (harness_label, reference_id, metric) in values)
            paired.append({
                "pair": pair, "referenceId": reference_id, "baselineLabel": baseline_label, "harnessLabel": harness_label,
                "metrics": {metric: _delta(values[(baseline_label, reference_id, metric)], values[(harness_label, reference_id, metric)]) for metric in names},
            })
    return {"raw": raw, "armSummaries": summaries, "pairedDeltas": paired}


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
    return {"raw": raw, "armSummaries": _arms(raw, fields), "pairedDeltas": paired}


def _telemetry_values(record):
    telemetry = record["telemetry"]
    result = {
        "wallTimeSeconds": record["wallTimeSeconds"], "edits": telemetry["edits"], "builds": telemetry["builds"],
        "launches": telemetry["launches"], "screenshots": telemetry["screenshots"],
        "failedOperations": len(telemetry["failedOperations"]), "failureCount": len(record.get("failures", [])),
    }
    for name, category in telemetry["tokens"].items():
        if category.get("status") == "available" and _number(category.get("value")):
            result[f"tokens.{name}"] = category["value"]
    for name, count in telemetry.get("toolCalls", {}).items():
        if _number(count):
            result[f"toolCalls.{name}"] = count
    return result


def _telemetry_channel(label_runs):
    values = {label: _telemetry_values(run["record"]) for label, run in label_runs.items()}
    raw = [{"label": label, "runId": run["runId"], "pair": run["pair"], "treatment": run["treatment"], "metrics": values[label]}
           for label, run in label_runs.items()]
    names = sorted(set.intersection(*(set(item) for item in values.values())))
    summaries = {name: {arm: _range(values[label][name] for label, run in label_runs.items() if run["treatment"] == arm)
                        for arm in ("baseline", "harness")} for name in names}
    paired = []
    for pair in (1, 2, 3):
        baseline_label = next(label for label, run in label_runs.items() if run["pair"] == pair and run["treatment"] == "baseline")
        harness_label = next(label for label, run in label_runs.items() if run["pair"] == pair and run["treatment"] == "harness")
        shared = sorted(set(values[baseline_label]) & set(values[harness_label]))
        item = {"pair": pair, "baselineLabel": baseline_label, "harnessLabel": harness_label}
        item.update({name: _delta(values[baseline_label][name], values[harness_label][name]) for name in shared})
        paired.append(item)
    return {"raw": raw, "armSummaries": summaries, "pairedDeltas": paired}


def _qualitative(label_runs, response):
    associated = []
    for label in LABELS:
        if label in response["comments"]:
            run = label_runs[label]
            associated.append({"label": label, "runId": run["runId"], "pair": run["pair"],
                               "treatment": run["treatment"], "comment": response["comments"][label]})
    return {"candidateComments": associated, "overall": response["comments"].get("overall")}


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
            "humanVisual": _human_channel(label_runs, response),
            "telemetryTreatment": _telemetry_channel(label_runs),
        },
        "qualitativeAssociations": _qualitative(label_runs, response),
        "interpretation": {
            "sampleSizePerTreatment": 3,
            "pairedDeltaConvention": "harness minus baseline",
            "conclusions": "Directional only; retain separate outcome channels.",
        },
    }
    _atomic_public_json(output_path, report)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    lock_parser = subparsers.add_parser("lock", help="validate and atomically seal a complete response")
    lock_parser.add_argument("--review-dir", required=True, type=Path)
    lock_parser.add_argument("--ratings", required=True, type=Path)
    lock_parser.add_argument("--lock", required=True, type=Path)
    report_parser = subparsers.add_parser("unblind", help="verify the seal and create the final report")
    report_parser.add_argument("--run-root", required=True, type=Path)
    report_parser.add_argument("--review-dir", required=True, type=Path)
    report_parser.add_argument("--mapping", required=True, type=Path)
    report_parser.add_argument("--ratings", required=True, type=Path)
    report_parser.add_argument("--lock", required=True, type=Path)
    report_parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "lock":
            lock_response(arguments.review_dir, arguments.ratings, arguments.lock)
            print(json.dumps({"status": "locked", "lock": str(arguments.lock)}))
        else:
            unblind(arguments.run_root, arguments.review_dir, arguments.mapping,
                    arguments.ratings, arguments.lock, arguments.output)
            print(json.dumps({"status": "unblinded", "report": str(arguments.output)}))
        return 0
    except (OSError, ValueError) as error:
        print(f"unblind-report: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
