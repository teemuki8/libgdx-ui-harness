#!/usr/bin/env python3
"""Fixture tests for blinded packaging, response sealing, and unblinding."""

import copy
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

SCRIPT_ROOT = Path(__file__).resolve().parent
PROTOCOL_AMENDMENT = "agentic-palisade/task-8-auth-broker-amendment-v1"
LABELS = tuple("ABCDEF")
REFERENCES = (
    ("initial-1920x1080", "initial", "desktop-1920x1080", 1920, 1080),
    ("bottom-1920x1080", "bottom", "desktop-1920x1080", 1920, 1080),
    ("initial-1280x720", "initial", "desktop-1280x720", 1280, 720),
)


def load_script(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), SCRIPT_ROOT / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BLIND = load_script("build-blind-review.py")
UNBLIND = load_script("unblind-report.py")


def canonical_bytes(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def png(width, height, color):
    signature = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    row = b"\x00" + bytes(color) * width
    return signature + png_chunk(b"IHDR", ihdr) + png_chunk(b"IDAT", zlib.compress(row * height, 9)) + png_chunk(b"IEND", b"")


def write_hashed_json(path, value, hash_path):
    data = canonical_bytes(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    hash_path.write_text(f"{sha256(data)}  {path.name}\n", encoding="ascii")


def token(value):
    return {"status": "available", "value": value}


def create_fixture(root):
    corpus = root / "corpus"
    (corpus / "reference").mkdir(parents=True)
    pngs = {
        reference_id: png(width, height, (8 + reference_index, 16 + reference_index, 24 + reference_index))
        for reference_index, (reference_id, _state, _viewport, width, height) in enumerate(REFERENCES)
    }
    references = []
    for reference_id, state_id, viewport_id, width, height in REFERENCES:
        payload = pngs[reference_id]
        relative = f"reference/{reference_id}.png"
        (corpus / relative).write_bytes(payload)
        references.append({
            "id": reference_id,
            "stateId": state_id,
            "viewportId": viewport_id,
            "file": relative,
            "width": width,
            "height": height,
            "bytes": len(payload),
            "sha256": sha256(payload),
        })
    corpus_spec = {"schemaVersion": "agentic-palisade/v1", "references": references}
    (corpus / "spec.json").write_bytes(canonical_bytes(corpus_spec))

    manifest_runs = []
    for index in range(6):
        run_id = f"00000000-0000-4000-8000-{index + 1:012d}"
        pair = index // 2 + 1
        treatment = "baseline" if index % 2 == 0 else "harness"
        run_dir = root / "runs" / run_id
        candidate_hash = sha256(f"candidate-{index}".encode())
        input_hashes = {name: sha256(f"{name}-frozen".encode()) for name in (
            "prompt", "corpus", "template", "protocol", "instructions",
            "inputManifest", "treatmentAppendix", "initialCandidate")}
        input_hashes["treatmentOverlay"] = None if treatment == "baseline" else sha256(b"overlay")
        input_hashes["finalCandidate"] = candidate_hash
        record = {
            "schemaVersion": "agentic-palisade/run-record-v1",
            "runId": run_id,
            "pair": pair,
            "treatment": treatment,
            "model": "openai-codex/gpt-5.6-sol:medium",
            "reasoning": "medium",
            "timestamps": {"startedAt": f"2026-07-2{index}T00:00:00Z", "finishedAt": f"2026-07-2{index}T00:10:00Z"},
            "wallTimeSeconds": 100 + index * 10,
            "exit": {"classification": "success" if index != 4 else "nonzero_exit", "code": 0 if index != 4 else 1, "signal": None, "timedOut": False},
            "hashes": input_hashes,
            "paths": {"workspace": f"runs/{run_id}/repository/benchmarks/agentic-palisade/template"},
            "telemetry": {
                "tokens": {name: token((index + 1) * multiplier) for name, multiplier in (("input", 100), ("output", 30), ("cacheRead", 20), ("cacheWrite", 10), ("reasoning", 15))},
                "toolCalls": {"bash": index + 1}, "edits": index + 2, "builds": index + 3,
                "launches": index + 4, "screenshots": index + 5,
                "failedOperations": [] if index != 4 else [{"name": "build"}],
            },
            "rounds": [],
            "failures": [] if index != 4 else [{"phase": "agent", "message": "fixture failure"}],
        }
        record_path = run_dir / "run-record.json"
        write_hashed_json(record_path, record, run_dir / "run-record.sha256")

        visual = []
        artifacts = []
        capture_dir = run_dir / "evaluation" / "captures"
        capture_dir.mkdir(parents=True)
        for reference_index, (reference_id, _state, viewport_id, width, height) in enumerate(REFERENCES):
            capture_hashes = []
            for repeat in range(5):
                color_index = index * 15 + reference_index * 5 + repeat
                payload = png(width, height, (32 + color_index, 64 + color_index, 96 + color_index))
                filename = f"{reference_id}-{repeat}.png"
                (capture_dir / filename).write_bytes(payload)
                digest = sha256(payload)
                capture_hashes.append(digest)
                artifacts.append({"path": f"captures/{filename}", "bytes": len(payload), "sha256": digest})
            visual.append({
                "referenceId": reference_id,
                "viewportId": viewport_id,
                "referenceSha256": next(item["sha256"] for item in references if item["id"] == reference_id),
                "captureSha256": capture_hashes,
                "metrics": {
                    "rgbMae": round(0.10 + index * 0.01, 3),
                    "luminanceSsim": {"scale1": round(0.90 - index * 0.01, 3)},
                    "edgeF1": round(0.80 - index * 0.01, 3),
                    "clipping": {"detected": index == 5},
                },
            })
        evaluation = {
            "schemaVersion": "agentic-palisade-evaluation/v1",
            "status": "complete" if index != 4 else "candidate-rejected",
            "candidate": {"id": run_id, "sha256": candidate_hash},
            "corpus": {"schemaVersion": "agentic-palisade/v1", "sha256": input_hashes["corpus"]},
            "functional": {"passed": 20 + index, "total": 30, "assertions": []},
            "visual": visual,
            "artifacts": artifacts,
            "diagnostics": ["private evaluator detail"],
        }
        evaluation_path = run_dir / "evaluation" / "evaluation.json"
        write_hashed_json(evaluation_path, evaluation, run_dir / "evaluation" / "evaluation.sha256")
        manifest_runs.append({
            "runId": run_id, "pair": pair, "treatment": treatment,
            "runRecord": f"runs/{run_id}/run-record.json",
            "runRecordHash": f"runs/{run_id}/run-record.sha256",
        })
    benchmark_manifest = {
        "schemaVersion": "agentic-palisade/benchmark-manifest-v1",
        "protocolAmendment": PROTOCOL_AMENDMENT,
        "model": "openai-codex/gpt-5.6-sol:medium",
        "pairs": 3,
        "runs": manifest_runs,
    }
    (root / "benchmark-manifest.json").write_bytes(canonical_bytes(benchmark_manifest))
    return root


def create_pre_amendment_abort_fixture(root, amendment=None):
    root.mkdir(parents=True)
    runs = []
    for index in range(6):
        run_id = f"10000000-0000-4000-8000-{index + 1:012d}"
        runs.append({
            "runId": run_id,
            "pair": index // 2 + 1,
            "treatment": "baseline" if index % 2 == 0 else "harness",
            "runRecord": f"runs/{run_id}/run-record.json",
            "runRecordHash": f"runs/{run_id}/run-record.sha256",
        })
    manifest = {
        "schemaVersion": "agentic-palisade/benchmark-manifest-v1",
        "model": "openai-codex/gpt-5.6-sol:medium",
        "pairs": 3,
        "runs": runs,
    }
    if amendment is not None:
        manifest["protocolAmendment"] = amendment
    (root / "benchmark-manifest.json").write_bytes(canonical_bytes(manifest))
    return root


def reference_shuffle(seed, identities):
    result = list(identities)
    counter = 0

    def randbelow(bound):
        nonlocal counter
        limit = 256 - (256 % bound)
        while True:
            block = hmac.new(seed, b"agentic-palisade/blind-shuffle/v1\x00" + counter.to_bytes(8, "big"), hashlib.sha256).digest()
            counter += 1
            for value in block:
                if value < limit:
                    return value % bound

    for index in range(len(result) - 1, 0, -1):
        other = randbelow(index + 1)
        result[index], result[other] = result[other], result[index]
    return result


def valid_response(review_dir, include_comments=True):
    manifest = json.loads((review_dir / "manifest.json").read_text())
    response = {
        "schemaVersion": "agentic-palisade/human-ratings-v1",
        "packageManifestSha256": sha256((review_dir / "manifest.json").read_bytes()),
        "fidelity": {label: index + 2 for index, label in enumerate(LABELS)},
        "ranking": {label: index + 1 for index, label in enumerate(LABELS)},
        "preferred": {pair["id"]: pair["candidates"][0] for pair in manifest["matchedPairs"]},
    }
    if include_comments:
        response["comments"] = {"A": "Strong hierarchy", "overall": "Fixture review"}
    path = review_dir / "human-ratings.json"
    path.write_bytes(canonical_bytes(response))
    return path, response


def has_composite(value):
    if isinstance(value, dict):
        return any("composite" in key.lower() or has_composite(item) for key, item in value.items())
    if isinstance(value, list):
        return any(has_composite(item) for item in value)
    return False


class BlindingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.input = create_fixture(self.root / "measured-private-output")
        self.review = self.root / "blind-review"
        self.mapping = self.root / "private" / "mapping.json"
        self.seed = bytes(range(32))

    def tearDown(self):
        self.temporary.cleanup()

    def build(self, review=None, mapping=None):
        return BLIND.build_package(
            self.input, review or self.review, mapping or self.mapping, seed=self.seed)
    def lock(self, ratings_path, lock_path):
        return UNBLIND.lock_response(
            self.input, self.review, self.mapping, ratings_path, lock_path)


    def test_unavailable_token_categories_remain_explicit_null_metrics(self):
        unavailable = {"status": "unavailable", "value": None}
        record = {
            "wallTimeSeconds": 1.0,
            "failures": [],
            "telemetry": {
                "tokens": {
                    "input": {"status": "available", "value": 10},
                    "output": unavailable,
                    "cacheRead": unavailable,
                    "cacheWrite": unavailable,
                    "reasoning": unavailable,
                },
                "toolCalls": {},
                "edits": 0,
                "builds": 0,
                "launches": 0,
                "screenshots": 0,
                "failedOperations": [],
            },
        }

        metrics = UNBLIND._telemetry_values(record)

        self.assertEqual(10, metrics["tokens.input"])
        for name in ("output", "cacheRead", "cacheWrite", "reasoning"):
            self.assertIsNone(metrics[f"tokens.{name}"])

    def test_seeded_fisher_yates_is_deterministic_balanced_and_private(self):
        self.build()
        second_review = self.root / "blind-review-second"
        second_mapping = self.root / "private-second" / "mapping.json"
        BLIND.build_package(self.input, second_review, second_mapping, seed=self.seed)
        first_private = json.loads(self.mapping.read_text())
        second_private = json.loads(second_mapping.read_text())
        expected = reference_shuffle(self.seed, [item["runId"] for item in json.loads((self.input / "benchmark-manifest.json").read_text())["runs"]])
        self.assertEqual(expected, [first_private["labels"][label]["runId"] for label in LABELS])
        self.assertEqual(first_private["labels"], second_private["labels"])
        self.assertEqual((self.review / "manifest.json").read_bytes(), (second_review / "manifest.json").read_bytes())
        self.assertEqual(set(first_private["labels"]), set(LABELS))
        self.assertEqual({entry["treatment"] for entry in first_private["labels"].values()}, {"baseline", "harness"})
        self.assertEqual(0o600, os.stat(self.mapping).st_mode & 0o777)
        public = json.loads((self.review / "manifest.json").read_text())
        self.assertEqual(set(LABELS), {candidate["label"] for candidate in public["candidates"]})
        self.assertEqual([2, 2, 2], [len(pair["candidates"]) for pair in public["matchedPairs"]])
        self.assertNotIn("seed", (self.review / "manifest.json").read_text().lower())

    def test_package_byte_copies_all_captures_with_dimensions_and_no_leaks(self):
        self.build()
        public = json.loads((self.review / "manifest.json").read_text())
        private = json.loads(self.mapping.read_text())
        capture_hashes = set()
        for candidate in public["candidates"]:
            self.assertEqual(15, len(candidate["captures"]))
            source_run = private["labels"][candidate["label"]]["runId"]
            for capture in candidate["captures"]:
                packaged = self.review / capture["file"]
                source_name = f'{capture["referenceId"]}-{capture["repeat"]}.png'
                source = self.input / "runs" / source_run / "evaluation" / "captures" / source_name
                self.assertEqual(source.read_bytes(), packaged.read_bytes())
                capture_hashes.add(capture["sha256"])
                self.assertEqual((capture["width"], capture["height"]), BLIND.png_dimensions(packaged))
        self.assertEqual(90, len(capture_hashes))
        self.assertEqual(3, len(public["references"]))
        BLIND.scan_package(self.review)
        package_text = "\n".join(path.read_text(errors="ignore") for path in self.review.rglob("*.json"))
        for forbidden in ("baseline", "harness", "runId", "token", str(self.input)):
            self.assertNotIn(forbidden.lower(), package_text.lower())

    def test_failed_evaluation_is_retained_as_an_empty_blinded_candidate(self):
        first = json.loads(
            (self.input / "benchmark-manifest.json").read_text())["runs"][0]
        evaluation_dir = (
            self.input / "runs" / first["runId"] / "evaluation")
        evaluation_path = evaluation_dir / "evaluation.json"
        evaluation = json.loads(evaluation_path.read_text())
        evaluation["status"] = "compile-failed"
        evaluation["functional"]["passed"] = 0
        for assertion in evaluation["functional"]["assertions"]:
            assertion["passed"] = False
        evaluation["visual"] = []
        evaluation["artifacts"] = []
        write_hashed_json(
            evaluation_path, evaluation, evaluation_dir / "evaluation.sha256")
        for capture in (evaluation_dir / "captures").glob("*.png"):
            capture.unlink()

        self.build()

        private = json.loads(self.mapping.read_text())
        label = next(
            label for label, identity in private["labels"].items()
            if identity["runId"] == first["runId"])
        public = json.loads((self.review / "manifest.json").read_text())
        candidate = next(
            item for item in public["candidates"] if item["label"] == label)
        self.assertEqual([], candidate["captures"])
        self.assertEqual([], candidate["automatedVisual"])
        BLIND.scan_package(self.review)
        ratings_path, _response = valid_response(self.review)
        lock_path = self.root / "failed-run-lock.json"
        self.lock(ratings_path, lock_path)
        self.assertTrue(lock_path.is_file())
        report_path = self.root / "failed-run-report.json"
        report = UNBLIND.unblind(
            self.input, self.review, self.mapping, ratings_path, lock_path,
            report_path)
        self.assertEqual(6, len(report["channels"]["automatedVisual"]["raw"]))
        failed_visual = next(
            item for item in report["channels"]["automatedVisual"]["raw"]
            if item["runId"] == first["runId"])
        self.assertEqual([], failed_visual["outcomes"])

    def test_leakage_scanner_rejects_mutated_names_content_and_png_metadata(self):
        self.build()
        leaked_name = self.review / "baseline-notes.txt"
        leaked_name.write_text("safe looking")
        with self.assertRaisesRegex(ValueError, "leak"):
            BLIND.scan_package(self.review)
        leaked_name.unlink()
        form = self.review / "review-form.json"
        value = json.loads(form.read_text())
        value["tokenCount"] = 42
        form.write_bytes(canonical_bytes(value))
        with self.assertRaisesRegex(ValueError, "leak"):
            BLIND.scan_package(self.review)
        value.pop("tokenCount")
        form.write_bytes(canonical_bytes(value))
        value["candidate"] = "00000000-0000-4000-8000-000000000001"
        form.write_bytes(canonical_bytes(value))
        with self.assertRaisesRegex(ValueError, "leak"):
            BLIND.scan_package(self.review)
        value.pop("candidate")
        form.write_bytes(canonical_bytes(value))
        image = next(self.review.rglob("*.png"))
        payload = image.read_bytes()
        marker = payload.rfind(struct.pack(">I", 0) + b"IEND")
        image.write_bytes(payload[:marker] + png_chunk(b"tEXt", b"treatment\x00baseline") + payload[marker:])
        with self.assertRaisesRegex(ValueError, "metadata"):
            BLIND.scan_package(self.review)

    def test_protocol_amendment_gate_precedes_evaluation_reads(self):
        manifest, _references, runs, _hashes = BLIND._load_inputs(self.input)
        self.assertEqual(manifest["protocolAmendment"], PROTOCOL_AMENDMENT)
        self.assertEqual(len(runs), 6)

        for value in (None, "agentic-palisade/wrong-amendment"):
            with self.subTest(value=value):
                source = create_pre_amendment_abort_fixture(
                    self.root / (
                        "pre-amendment-abort" if value is None
                        else "wrong-amendment-abort"
                    ),
                    value,
                )
                with self.assertRaisesRegex(ValueError, "protocol amendment"):
                    BLIND._load_inputs(source)

    def test_requires_exactly_six_hash_bound_run_and_evaluation_inputs(self):
        manifest_path = self.input / "benchmark-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["runs"].pop()
        manifest_path.write_bytes(canonical_bytes(manifest))
        with self.assertRaisesRegex(ValueError, "six"):
            self.build()
        self.input = create_fixture(self.root / "replacement-input")
        first = json.loads((self.input / "benchmark-manifest.json").read_text())["runs"][0]
        record_path = self.input / first["runRecord"]
        record_path.write_bytes(record_path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "hash"):
            self.build()

    def test_response_validation_rejects_incomplete_duplicate_out_of_range_and_bad_preference(self):
        self.build()
        ratings_path, response = valid_response(self.review)
        UNBLIND.validate_response(self.review, ratings_path)
        without_comments = copy.deepcopy(response)
        without_comments.pop("comments")
        ratings_path.write_bytes(canonical_bytes(without_comments))
        UNBLIND.validate_response(self.review, ratings_path)
        mutations = []
        missing = copy.deepcopy(response)
        missing["fidelity"].pop("F")
        mutations.append(missing)
        duplicate = copy.deepcopy(response)
        duplicate["ranking"]["F"] = 5
        mutations.append(duplicate)
        out_of_range = copy.deepcopy(response)
        out_of_range["fidelity"]["A"] = 8
        mutations.append(out_of_range)
        bad_preference = copy.deepcopy(response)
        bad_preference["preferred"]["pair-1"] = next(label for label in LABELS if label not in json.loads((self.review / "manifest.json").read_text())["matchedPairs"][0]["candidates"])
        mutations.append(bad_preference)
        long_comment = copy.deepcopy(response)
        long_comment["comments"]["A"] = "x" * 2001
        mutations.append(long_comment)
        for mutation in mutations:
            ratings_path.write_bytes(canonical_bytes(mutation))
            with self.subTest(mutation=mutation):
                with self.assertRaises(ValueError):
                    UNBLIND.validate_response(self.review, ratings_path)

    def test_lock_rejects_mapping_swap_and_public_capture_mix_up(self):
        self.build()
        ratings_path, response = valid_response(self.review)
        lock_path = self.root / "private" / "review.lock.json"
        private = json.loads(self.mapping.read_text())
        private["labels"]["A"], private["labels"]["B"] = private["labels"]["B"], private["labels"]["A"]
        self.mapping.write_bytes(canonical_bytes(private))
        with self.assertRaisesRegex(ValueError, "mapping"):
            self.lock(ratings_path, lock_path)

        private["labels"]["A"], private["labels"]["B"] = private["labels"]["B"], private["labels"]["A"]
        self.mapping.write_bytes(canonical_bytes(private))
        original_manifest = json.loads((self.review / "manifest.json").read_text())
        first_file = self.review / next(
            candidate for candidate in original_manifest["candidates"]
            if candidate["label"] == "A")["captures"][0]["file"]
        second_file = self.review / next(
            candidate for candidate in original_manifest["candidates"]
            if candidate["label"] == "B")["captures"][0]["file"]
        first_bytes, second_bytes = first_file.read_bytes(), second_file.read_bytes()
        first_file.write_bytes(second_bytes)
        second_file.write_bytes(first_bytes)
        with self.assertRaisesRegex(ValueError, "capture hash"):
            self.lock(ratings_path, lock_path)
        first_file.write_bytes(first_bytes)
        second_file.write_bytes(second_bytes)
        manifest_path = self.review / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        first = next(candidate for candidate in manifest["candidates"] if candidate["label"] == "A")
        second = next(candidate for candidate in manifest["candidates"] if candidate["label"] == "B")
        first["captures"], second["captures"] = second["captures"], first["captures"]
        first["automatedVisual"], second["automatedVisual"] = second["automatedVisual"], first["automatedVisual"]
        manifest_path.write_bytes(canonical_bytes(manifest))
        private["packageManifestSha256"] = sha256(manifest_path.read_bytes())
        self.mapping.write_bytes(canonical_bytes(private))
        response["packageManifestSha256"] = private["packageManifestSha256"]
        ratings_path.write_bytes(canonical_bytes(response))
        with self.assertRaisesRegex(ValueError, "capture binding"):
            self.lock(ratings_path, lock_path)

    def test_lock_binds_review_form_and_response_schema_bytes(self):
        self.build()
        ratings_path, _response = valid_response(self.review)
        lock_path = self.root / "private" / "review.lock.json"
        form_path = self.review / "review-form.json"
        original_form = form_path.read_bytes()
        form = json.loads(original_form)
        form["unexpected"] = True
        form_path.write_bytes(canonical_bytes(form))
        with self.assertRaisesRegex(ValueError, "support file hash"):
            self.lock(ratings_path, lock_path)
        form_path.write_bytes(original_form)
        schema_path = self.review / "human-ratings.schema.json"
        schema_path.write_bytes(schema_path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "support file hash"):
            self.lock(ratings_path, lock_path)

    def test_no_comments_response_locks_and_unblinds(self):
        self.build()
        ratings_path, _response = valid_response(self.review, include_comments=False)
        lock_path = self.root / "private" / "review.lock.json"
        output = self.root / "final-report.json"
        self.lock(ratings_path, lock_path)
        UNBLIND.unblind(self.input, self.review, self.mapping, ratings_path, lock_path, output)
        qualitative = json.loads(output.read_text())["qualitativeAssociations"]
        self.assertEqual([], qualitative["candidateComments"])
        self.assertIsNone(qualitative["overall"])

    def test_unblind_refuses_before_lock_and_detects_response_or_manifest_tampering(self):
        self.build()
        ratings_path, response = valid_response(self.review)
        lock_path = self.root / "private" / "review.lock.json"
        output = self.root / "final-report.json"
        with self.assertRaisesRegex(ValueError, "lock"):
            UNBLIND.unblind(self.input, self.review, self.mapping, ratings_path, lock_path, output)
        self.lock(ratings_path, lock_path)
        self.assertEqual(0o600, os.stat(lock_path).st_mode & 0o777)
        locked = json.loads(lock_path.read_text())
        self.assertEqual(
            {"packageManifestSha256", "reviewFormSha256", "responseSchemaSha256", "responseSha256"},
            set(locked) - {"schemaVersion"})
        mutated = copy.deepcopy(response)
        mutated["comments"]["A"] = "changed after lock"
        ratings_path.write_bytes(canonical_bytes(mutated))
        with self.assertRaisesRegex(ValueError, "hash"):
            UNBLIND.unblind(self.input, self.review, self.mapping, ratings_path, lock_path, output)
        ratings_path.write_bytes(canonical_bytes(response))
        for support_name in ("review-form.json", "human-ratings.schema.json"):
            support_path = self.review / support_name
            support_bytes = support_path.read_bytes()
            support_path.write_bytes(support_bytes + b" ")
            with self.subTest(support=support_name):
                with self.assertRaisesRegex(ValueError, "support file hash"):
                    UNBLIND.unblind(
                        self.input, self.review, self.mapping, ratings_path, lock_path, output)
            support_path.write_bytes(support_bytes)
        manifest_path = self.review / "manifest.json"
        manifest_path.write_bytes(manifest_path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "hash"):
            UNBLIND.unblind(self.input, self.review, self.mapping, ratings_path, lock_path, output)

    def test_unblind_reports_separate_correct_paired_channels_without_composite(self):
        self.build()
        ratings_path, response = valid_response(self.review)
        lock_path = self.root / "private" / "review.lock.json"
        output = self.root / "final-report.json"
        self.lock(ratings_path, lock_path)
        UNBLIND.unblind(self.input, self.review, self.mapping, ratings_path, lock_path, output)
        report = json.loads(output.read_text())
        self.assertEqual({
            "functional", "automatedVisual", "structuralUsability",
            "humanVisual", "telemetryTreatment"}, set(report["channels"]))
        self.assertEqual(
            6, len(report["channels"]["structuralUsability"]["raw"]))
        self.assertNotIn("combined", json.dumps(report).lower())
        by_pair = {item["pair"]: item for item in report["channels"]["functional"]["pairedDeltas"]}
        self.assertEqual(1, by_pair[1]["passed"]["harnessMinusBaseline"])
        human_pairs = report["channels"]["humanVisual"]["pairedDeltas"]
        private = json.loads(self.mapping.read_text())
        for item in human_pairs:
            baseline_label = next(label for label, entry in private["labels"].items() if entry["pair"] == item["pair"] and entry["treatment"] == "baseline")
            harness_label = next(label for label, entry in private["labels"].items() if entry["pair"] == item["pair"] and entry["treatment"] == "harness")
            self.assertEqual(response["fidelity"][harness_label] - response["fidelity"][baseline_label], item["fidelity"]["harnessMinusBaseline"])
        telemetry = {item["pair"]: item for item in report["channels"]["telemetryTreatment"]["pairedDeltas"]}
        self.assertEqual(10, telemetry[1]["wallTimeSeconds"]["harnessMinusBaseline"])
        self.assertIn("qualitativeAssociations", report)
        self.assertFalse(has_composite(report))
        schema = json.loads((SCRIPT_ROOT / "schemas" / "final-report.schema.json").read_text())
        self.assertNotIn("composite", json.dumps(schema).lower())


if __name__ == "__main__":
    unittest.main()
