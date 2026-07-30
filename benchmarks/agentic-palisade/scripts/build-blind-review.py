#!/usr/bin/env python3
"""Build a hash-bound, leakage-scanned A-F review package from six frozen runs."""

import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import struct
import sys
import tempfile

LABELS = tuple("ABCDEF")
SCHEMA_VERSION = "agentic-palisade/blind-review-v1"
MAPPING_VERSION = "agentic-palisade/blind-mapping-v1"
PROTOCOL_AMENDMENT = "agentic-palisade/task-8-auth-broker-amendment-v1"
SHUFFLE_DOMAIN = b"agentic-palisade/blind-shuffle/v1\x00"
REQUIRED_REFERENCES = (
    "initial-1920x1080",
    "bottom-1920x1080",
    "initial-1280x720",
)
FORBIDDEN_TEXT = re.compile(
    r"(?:\bbaseline\b|\bharness\b|\btreatment\b|run[ _-]?id|\buuid\b|"
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b|"
    r"\btoken[A-Za-z0-9_-]*\b|\bsession[A-Za-z0-9_-]*\b|\bworkspace\b|"
    r"artifact[ _-]?root|(?:^|[\"'\s])/(?:home|Users|tmp)/|[A-Za-z]:\\)",
    re.IGNORECASE,
)
STRUCTURAL_FORBIDDEN_TEXT = re.compile(
    r"(?:\bbaseline\b|\bharness\b|\btreatment\b|run[ _-]?id|\buuid\b|"
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b|"
    r"\btoken[A-Za-z0-9_-]*\b|\bsession[A-Za-z0-9_-]*\b|\bworkspace\b|"
    r"artifact[ _-]?root|(?:^|[\"'\s])/(?:home|Users|tmp)/|[A-Za-z]:\\)",
    re.IGNORECASE,
)
FORBIDDEN_PNG_CHUNKS = {b"tEXt", b"zTXt", b"iTXt", b"eXIf", b"tIME"}


def canonical_bytes(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON input {Path(path).name}: {error}") from error


def _inside(path, root):
    try:
        Path(path).resolve().relative_to(Path(root).resolve())
        return True
    except ValueError:
        return False


def _resolve_input(root, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError("input path must be a non-empty relative path")
    resolved = (Path(root) / relative).resolve()
    if not _inside(resolved, root):
        raise ValueError("input path escapes the frozen run root")
    return resolved


def _read_hash_sidecar(path, expected_name):
    try:
        line = Path(path).read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as error:
        raise ValueError(f"cannot read hash sidecar {Path(path).name}: {error}") from error
    match = re.fullmatch(r"([0-9a-f]{64})(?:\s+\*?([^\s]+))?", line)
    if not match or (match.group(2) is not None and match.group(2) != expected_name):
        raise ValueError(f"invalid hash sidecar {Path(path).name}")
    return match.group(1)


def _verify_hashed_file(path, sidecar):
    if not Path(path).is_file() or not Path(sidecar).is_file():
        raise ValueError(f"missing hash-bound input {Path(path).name}")
    expected = _read_hash_sidecar(sidecar, Path(path).name)
    observed = sha256_file(path)
    if observed != expected:
        raise ValueError(f"hash mismatch for {Path(path).name}")
    return observed


def png_dimensions(path):
    try:
        header = Path(path).read_bytes()[:24]
    except OSError as error:
        raise ValueError(f"cannot read PNG {Path(path).name}: {error}") from error
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError(f"invalid PNG {Path(path).name}")
    width, height = struct.unpack(">II", header[16:24])
    if width < 1 or height < 1:
        raise ValueError(f"invalid PNG dimensions in {Path(path).name}")
    return width, height


def _scan_png_metadata(path):
    data = Path(path).read_bytes()
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"invalid PNG in review package: {Path(path).name}")
    offset = 8
    saw_end = False
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError(f"truncated PNG in review package: {Path(path).name}")
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        end = offset + 12 + length
        if end > len(data):
            raise ValueError(f"truncated PNG chunk in review package: {Path(path).name}")
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length:end])[0]
        import zlib
        if (zlib.crc32(kind + payload) & 0xFFFFFFFF) != expected_crc:
            raise ValueError(f"invalid PNG checksum in review package: {Path(path).name}")
        if kind in FORBIDDEN_PNG_CHUNKS:
            raise ValueError(f"PNG metadata is forbidden in review package: {Path(path).name}")
        offset = end
        if kind == b"IEND":
            saw_end = True
            break
    if not saw_end or offset != len(data):
        raise ValueError(f"invalid PNG ending in review package: {Path(path).name}")


def scan_package(review_dir):
    """Fail closed if a public package contains identity, operational, or PNG metadata."""
    root = Path(review_dir)
    if not root.is_dir():
        raise ValueError("review package does not exist")
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            raise ValueError(f"leak scanner rejects symlink: {relative}")
        if FORBIDDEN_TEXT.search(relative):
            raise ValueError(f"leak detected in public file name: {relative}")
        if not path.is_file():
            continue
        if path.suffix.lower() == ".png":
            _scan_png_metadata(path)
            continue
        if path.suffix.lower() != ".json":
            raise ValueError(f"leak scanner rejects unexpected public file: {relative}")
        try:
            text = path.read_text(encoding="utf-8")
            json.loads(text)
        except (UnicodeError, json.JSONDecodeError) as error:
            raise ValueError(f"invalid public JSON {relative}: {error}") from error
        if FORBIDDEN_TEXT.search(text):
            raise ValueError(f"leak detected in public JSON: {relative}")
    return True


def fisher_yates(seed, identities):
    """Domain-separated HMAC-SHA256 Fisher-Yates with rejection sampling."""
    if not isinstance(seed, bytes) or len(seed) < 32:
        raise ValueError("private seed must contain at least 256 bits")
    shuffled = list(identities)
    counter = 0

    def randbelow(bound):
        nonlocal counter
        limit = 256 - 256 % bound
        while True:
            block = hmac.new(seed, SHUFFLE_DOMAIN + counter.to_bytes(8, "big"), hashlib.sha256).digest()
            counter += 1
            for value in block:
                if value < limit:
                    return value % bound

    for index in range(len(shuffled) - 1, 0, -1):
        other = randbelow(index + 1)
        shuffled[index], shuffled[other] = shuffled[other], shuffled[index]
    return shuffled


def _require_object(value, name):
    if not isinstance(value, dict):
        raise ValueError(f"{name} must be an object")
    return value


def _load_references(root, input_hashes):
    spec_path = Path(root) / "corpus" / "spec.json"
    spec = _json(spec_path)
    if spec.get("schemaVersion") != "agentic-palisade/v1":
        raise ValueError("unsupported corpus schema")
    entries = spec.get("references")
    if not isinstance(entries, list) or [item.get("id") for item in entries] != list(REQUIRED_REFERENCES):
        raise ValueError("corpus must contain the three canonical references in order")
    input_hashes["corpus/spec.json"] = sha256_file(spec_path)
    result = {}
    for entry in entries:
        if set(entry) < {"id", "stateId", "viewportId", "file", "width", "height", "bytes", "sha256"}:
            raise ValueError("incomplete reference identity")
        path = _resolve_input(Path(root) / "corpus", entry["file"])
        if not path.is_file() or path.stat().st_size != entry["bytes"] or sha256_file(path) != entry["sha256"]:
            raise ValueError(f"reference hash or length mismatch: {entry['id']}")
        if png_dimensions(path) != (entry["width"], entry["height"]):
            raise ValueError(f"reference dimensions mismatch: {entry['id']}")
        _scan_png_metadata(path)
        input_hashes[f"corpus/{entry['file']}"] = entry["sha256"]
        result[entry["id"]] = {"entry": entry, "path": path}
    return result


def _validate_run_record(record, listed):
    if record.get("schemaVersion") != "agentic-palisade/run-record-v1":
        raise ValueError("unsupported run record schema")
    for key in ("runId", "pair", "treatment"):
        if record.get(key) != listed.get(key):
            raise ValueError(f"run record {key} does not match benchmark manifest")
    if record.get("treatment") not in ("baseline", "harness") or record.get("pair") not in (1, 2, 3):
        raise ValueError("invalid matched-pair identity")
    hashes = _require_object(record.get("hashes"), "run hashes")
    required_hashes = {
        "prompt", "corpus", "template", "protocol", "instructions", "inputManifest",
        "treatmentAppendix", "treatmentOverlay", "initialCandidate", "finalCandidate",
    }
    if set(hashes) != required_hashes:
        raise ValueError("run record immutable hash set is incomplete")
    for name, digest in hashes.items():
        if digest is None and name == "treatmentOverlay" and record["treatment"] == "baseline":
            continue
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"invalid immutable hash {name}")
    if not isinstance(hashes["finalCandidate"], str):
        raise ValueError("final candidate hash is required for blinded review")
    for required in ("functional", "telemetry", "wallTimeSeconds", "exit", "failures"):
        if required == "functional":
            continue
        if required not in record:
            raise ValueError(f"run record missing {required}")


def _load_inputs(root):
    root = Path(root).resolve()
    manifest_path = root / "benchmark-manifest.json"
    manifest = _json(manifest_path)
    if manifest.get("schemaVersion") != "agentic-palisade/benchmark-manifest-v1":
        raise ValueError("unsupported benchmark manifest schema")
    if manifest.get("protocolAmendment") != PROTOCOL_AMENDMENT:
        raise ValueError("unsupported benchmark protocol amendment")
    listed_runs = manifest.get("runs")
    if not isinstance(listed_runs, list) or len(listed_runs) != 6:
        raise ValueError("blind review requires exactly six immutable runs")
    if manifest.get("pairs") != 3:
        raise ValueError("blind review requires exactly three matched pairs")
    run_ids = [item.get("runId") for item in listed_runs if isinstance(item, dict)]
    if len(set(run_ids)) != 6 or any(not isinstance(item, str) for item in run_ids):
        raise ValueError("six unique run identities are required")

    input_hashes = {"benchmark-manifest.json": sha256_file(manifest_path)}
    references = _load_references(root, input_hashes)
    runs = []
    pair_arms = set()
    common_hashes = None
    for listed in listed_runs:
        run_path = _resolve_input(root, listed.get("runRecord"))
        hash_path = _resolve_input(root, listed.get("runRecordHash"))
        run_hash = _verify_hashed_file(run_path, hash_path)
        record = _json(run_path)
        _validate_run_record(record, listed)
        pair_arm = (record["pair"], record["treatment"])
        if pair_arm in pair_arms:
            raise ValueError("duplicate matched-pair arm")
        pair_arms.add(pair_arm)
        frozen_common = {name: record["hashes"][name] for name in ("prompt", "corpus", "template", "protocol")}
        if common_hashes is None:
            common_hashes = frozen_common
        elif frozen_common != common_hashes:
            raise ValueError("shared immutable benchmark inputs differ across runs")

        run_dir = root / "runs" / record["runId"]
        evaluation_path = run_dir / "evaluation" / "evaluation.json"
        evaluation_hash_path = run_dir / "evaluation" / "evaluation.sha256"
        evaluation_hash = _verify_hashed_file(evaluation_path, evaluation_hash_path)
        evaluation = _json(evaluation_path)
        if evaluation.get("schemaVersion") != "agentic-palisade-evaluation/v1":
            raise ValueError("unsupported evaluation schema")
        candidate = _require_object(evaluation.get("candidate"), "evaluation candidate")
        if candidate.get("id") != record["runId"] or candidate.get("sha256") != record["hashes"]["finalCandidate"]:
            raise ValueError("evaluation candidate identity or hash mismatch")
        corpus = _require_object(evaluation.get("corpus"), "evaluation corpus")
        if corpus.get("schemaVersion") != "agentic-palisade/v1" or corpus.get("sha256") != record["hashes"]["corpus"]:
            raise ValueError("evaluation corpus identity mismatch")
        functional = _require_object(evaluation.get("functional"), "functional evaluation")
        if not all(isinstance(functional.get(name), int) for name in ("passed", "total")) or not 0 <= functional["passed"] <= functional["total"]:
            raise ValueError("invalid functional evaluation")
        status = evaluation.get("status")
        if status not in {
                "complete", "compile-failed", "runtime-failed",
                "invalid-candidate", "candidate-rejected"}:
            raise ValueError("unsupported evaluation status")
        visuals = evaluation.get("visual")
        artifacts = evaluation.get("artifacts")
        if not isinstance(visuals, list) or not isinstance(artifacts, list):
            raise ValueError("evaluation evidence lists are missing")
        if visuals:
            if {item.get("referenceId") for item in visuals} != set(REQUIRED_REFERENCES):
                raise ValueError(
                    "evaluation must contain every canonical visual outcome")
        elif status == "complete":
            raise ValueError(
                "complete evaluation must contain every canonical visual outcome")
        elif artifacts:
            raise ValueError(
                "failed evaluation must not claim artifacts without visual outcomes")
        artifact_by_path = {item.get("path"): item for item in artifacts if isinstance(item, dict)}
        captures = []
        public_visual = []
        structural = evaluation.get("structural", [])
        if not isinstance(structural, list):
            raise ValueError("structural usability outcomes must be an array")
        if STRUCTURAL_FORBIDDEN_TEXT.search(json.dumps(structural, sort_keys=True)):
            raise ValueError("structural usability outcomes contain leakage")
        if structural and len(structural) != len(REQUIRED_REFERENCES):
            raise ValueError("structural outcomes must cover every reference or be unavailable")
        for visual in sorted(visuals, key=lambda item: REQUIRED_REFERENCES.index(item["referenceId"])):
            reference = references[visual["referenceId"]]["entry"]
            if visual.get("viewportId") != reference["viewportId"] or visual.get("referenceSha256") != reference["sha256"]:
                raise ValueError("visual reference identity mismatch")
            capture_hashes = visual.get("captureSha256")
            if not isinstance(capture_hashes, list) or len(capture_hashes) != 5:
                raise ValueError("every canonical visual requires five captures")
            metrics = _require_object(visual.get("metrics"), "automated visual metrics")
            if FORBIDDEN_TEXT.search(json.dumps(metrics, sort_keys=True)):
                raise ValueError("automated visual metrics contain leakage")
            public_visual.append({"referenceId": visual["referenceId"], "metrics": metrics})
            for repeat, expected_hash in enumerate(capture_hashes):
                logical = f"captures/{visual['referenceId']}-{repeat}.png"
                artifact = artifact_by_path.get(logical)
                capture_path = evaluation_path.parent / logical
                if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
                    raise ValueError("invalid capture hash")
                if not isinstance(artifact, dict) or artifact.get("sha256") != expected_hash:
                    raise ValueError(f"capture artifact identity mismatch: {logical}")
                if not capture_path.is_file() or capture_path.stat().st_size != artifact.get("bytes") or sha256_file(capture_path) != expected_hash:
                    raise ValueError(f"capture hash or length mismatch: {logical}")
                if png_dimensions(capture_path) != (reference["width"], reference["height"]):
                    raise ValueError(f"capture dimensions mismatch: {logical}")
                _scan_png_metadata(capture_path)
                captures.append({
                    "source": capture_path,
                    "referenceId": visual["referenceId"],
                    "stateId": reference["stateId"],
                    "viewportId": reference["viewportId"],
                    "width": reference["width"],
                    "height": reference["height"],
                    "repeat": repeat,
                    "sha256": expected_hash,
                    "bytes": artifact["bytes"],
                })
                input_hashes[capture_path.relative_to(root).as_posix()] = expected_hash
        for path, digest in ((run_path, run_hash), (hash_path, sha256_file(hash_path)),
                             (evaluation_path, evaluation_hash), (evaluation_hash_path, sha256_file(evaluation_hash_path))):
            input_hashes[path.relative_to(root).as_posix()] = digest
        runs.append({
            "runId": record["runId"], "pair": record["pair"], "treatment": record["treatment"],
            "record": record, "evaluation": evaluation, "runRecordHash": run_hash,
            "evaluationHash": evaluation_hash, "captures": captures,
            "automatedVisual": public_visual, "structuralUsability": structural,
        })
    if pair_arms != {(pair, arm) for pair in (1, 2, 3) for arm in ("baseline", "harness")}:
        raise ValueError("every matched pair must contain one run from each arm")
    return manifest, references, runs, input_hashes


def _atomic_private_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise ValueError(f"private output already exists: {path.name}")
    temporary = path.parent / f".{path.name}.tmp-{secrets.token_hex(8)}"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(canonical_bytes(value))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def canonical_run_ids(runs):
    """Return the precommitted pair/arm order used as shuffle input."""
    ordered = sorted(
        runs,
        key=lambda run: (run["pair"], 0 if run["treatment"] == "baseline" else 1),
    )
    return [run["runId"] for run in ordered]


def build_package(run_root, review_dir, mapping_path, seed=None):
    """Validate six frozen inputs and publish a deterministic blind package."""
    run_root = Path(run_root).resolve()
    review_dir = Path(review_dir).resolve()
    mapping_path = Path(mapping_path).resolve()
    if review_dir.exists() or mapping_path.exists():
        raise ValueError("review package and private mapping must be new paths")
    if _inside(mapping_path, review_dir):
        raise ValueError("private mapping must be outside the review package")
    if _inside(review_dir, run_root) or _inside(run_root, review_dir):
        raise ValueError("review package and frozen input root must be separate")
    private_seed = secrets.token_bytes(32) if seed is None else seed
    manifest, references, runs, input_hashes = _load_inputs(run_root)
    shuffled_ids = fisher_yates(private_seed, canonical_run_ids(runs))
    by_id = {run["runId"]: run for run in runs}
    label_runs = {label: by_id[run_id] for label, run_id in zip(LABELS, shuffled_ids)}

    review_dir.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{review_dir.name}.tmp-", dir=review_dir.parent))
    try:
        public_references = []
        for number, reference_id in enumerate(REQUIRED_REFERENCES, 1):
            source = references[reference_id]
            relative = f"references/reference-{number:02d}.png"
            destination = temporary / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source["path"].read_bytes())
            entry = source["entry"]
            public_references.append({
                "id": reference_id, "stateId": entry["stateId"], "viewportId": entry["viewportId"],
                "width": entry["width"], "height": entry["height"], "file": relative,
                "bytes": entry["bytes"], "sha256": entry["sha256"],
            })

        public_candidates = []
        for label in LABELS:
            run = label_runs[label]
            public_captures = []
            for number, capture in enumerate(run["captures"], 1):
                relative = f"candidates/{label}/capture-{number:02d}.png"
                destination = temporary / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(capture["source"].read_bytes())
                public_captures.append({
                    "referenceId": capture["referenceId"], "stateId": capture["stateId"],
                    "viewportId": capture["viewportId"], "width": capture["width"],
                    "height": capture["height"], "repeat": capture["repeat"], "file": relative,
                    "bytes": capture["bytes"], "sha256": capture["sha256"],
                })
            public_candidates.append({
                "label": label,
                "captures": public_captures,
                "automatedVisual": run["automatedVisual"],
                "structuralUsability": run["structuralUsability"],
            })

        public_pairs = []
        for pair in (1, 2, 3):
            labels = sorted(label for label, run in label_runs.items() if run["pair"] == pair)
            public_pairs.append({"id": f"pair-{pair}", "candidates": labels})
        form = {
            "schemaVersion": "agentic-palisade/review-form-v1",
            "manifestFile": "manifest.json",
            "responseFile": "human-ratings.json",
            "instructions": [
                "Review every reference and all five repeated captures for each canonical state.",
                "Record one fidelity value from 1 through 7 for every candidate.",
                "Assign every ranking value from 1 through 6 exactly once; 1 is best.",
                "Select one preferred candidate within each displayed matched pair.",
                "Comments are optional and limited to 2000 characters per entry.",
            ],
            "labels": list(LABELS),
            "matchedPairs": public_pairs,
            "fields": {
                "fidelity": {"labels": list(LABELS), "minimum": 1, "maximum": 7},
                "ranking": {"labels": list(LABELS), "minimum": 1, "maximum": 6, "unique": True},
                "preferred": {pair["id"]: pair["candidates"] for pair in public_pairs},
                "comments": {"optional": True, "maximumLength": 2000},
            },
        }
        form_path = temporary / "review-form.json"
        form_path.write_bytes(canonical_bytes(form))
        schema_source = Path(__file__).resolve().parent / "schemas" / "human-ratings.schema.json"
        if not schema_source.is_file():
            raise ValueError("human ratings schema is missing")
        schema_path = temporary / "human-ratings.schema.json"
        schema_path.write_bytes(schema_source.read_bytes())
        public_manifest = {
            "schemaVersion": SCHEMA_VERSION,
            "hashAlgorithm": "SHA-256",
            "labels": list(LABELS),
            "references": public_references,
            "candidates": public_candidates,
            "matchedPairs": public_pairs,
            "reviewForm": {"file": "review-form.json", "sha256": sha256_file(form_path)},
            "responseFile": "human-ratings.json",
            "responseSchema": {"file": "human-ratings.schema.json", "sha256": sha256_file(schema_path)},
        }
        manifest_bytes = canonical_bytes(public_manifest)
        (temporary / "manifest.json").write_bytes(manifest_bytes)
        scan_package(temporary)
        os.replace(temporary, review_dir)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)

    private_labels = {
        label: {
            "runId": run["runId"], "pair": run["pair"], "treatment": run["treatment"],
            "runRecordSha256": run["runRecordHash"], "evaluationSha256": run["evaluationHash"],
        }
        for label, run in label_runs.items()
    }
    private_mapping = {
        "schemaVersion": MAPPING_VERSION,
        "seedHex": private_seed.hex(),
        "seedSha256": sha256_bytes(private_seed),
        "packageManifestSha256": sha256_file(review_dir / "manifest.json"),
        "benchmarkManifestSha256": sha256_file(run_root / "benchmark-manifest.json"),
        "labels": private_labels,
        "inputHashes": dict(sorted(input_hashes.items())),
    }
    try:
        _atomic_private_json(mapping_path, private_mapping)
    except Exception:
        shutil.rmtree(review_dir)
        raise
    return public_manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", required=True, type=Path)
    parser.add_argument("--review-dir", required=True, type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--seed-file", type=Path, help="private deterministic seed; omit to generate 256 random bits")
    arguments = parser.parse_args(argv)
    try:
        seed = arguments.seed_file.read_bytes() if arguments.seed_file else None
        build_package(arguments.run_root, arguments.review_dir, arguments.mapping, seed=seed)
        print(json.dumps({"status": "built", "candidates": 6, "reviewDirectory": str(arguments.review_dir)}))
        return 0
    except (OSError, ValueError) as error:
        print(f"build-blind-review: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
