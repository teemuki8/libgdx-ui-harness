#!/usr/bin/env python3
"""Qualify every production benchmark stage with deterministic fixtures only."""

import copy
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


SCRIPT_ROOT = Path(__file__).resolve().parent
BENCHMARK_ROOT = SCRIPT_ROOT.parent
REPOSITORY_ROOT = BENCHMARK_ROOT.parent.parent
MODEL = "openai-codex/gpt-5.6-sol:medium"
PROTOCOL_AMENDMENT = "agentic-palisade/task-8-auth-broker-amendment-v1"
CASES = {
    (1, "baseline"): ("conforming", "success", "complete"),
    (1, "harness"): ("uncompilable", "success", "compile-failed"),
    (2, "baseline"): ("timeout", "timed_out", "complete"),
    (2, "harness"): ("malformed-omp-export", "telemetry_failure", "complete"),
    (3, "baseline"): ("missing-capture", "success", "runtime-failed"),
    (3, "harness"): ("leaked-child", "round_supervisor_failure", "complete"),
}
GENERATED = {".gradle", "build", "__pycache__"}
FINAL_CHANNELS = (
    "functional", "automatedVisual", "structuralUsability",
    "humanVisual", "telemetryTreatment", "traceTaxonomy")
TOKEN_METRICS = (
    "tokens.input", "tokens.output", "tokens.cacheRead",
    "tokens.cacheWrite", "tokens.reasoning")


def load_script(filename, name):
    spec = importlib.util.spec_from_file_location(name, SCRIPT_ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RUNNER = load_script("run-benchmark.py", "qualification_runner")
BLIND = load_script("build-blind-review.py", "qualification_blind")


def canonical_bytes(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"),
                       ensure_ascii=False) + "\n").encode("utf-8")


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run_command(name, command, logs, expected, cwd=BENCHMARK_ROOT, env=None):
    completed = subprocess.run(
        [str(item) for item in command], cwd=cwd, env=env, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300,
    )
    (logs / f"{name}.stdout.log").write_text(completed.stdout, encoding="utf-8")
    (logs / f"{name}.stderr.log").write_text(completed.stderr, encoding="utf-8")
    if completed.returncode != expected:
        raise ValueError(
            f"{name} exited {completed.returncode}, expected {expected}: "
            f"{completed.stderr.strip() or completed.stdout.strip()}")
    return completed

def rejected_input_retained(path, before, completed, error_fragment):
    if completed.returncode == 0 or error_fragment.lower() not in completed.stderr.lower():
        return False
    try:
        return Path(path).read_bytes() == before
    except OSError:
        return False


def validate_final_channels(report, expected_run_ids):
    expected = set(expected_run_ids)
    if len(expected) != 6:
        raise ValueError("final channel continuity requires six run IDs")
    channels = report.get("channels")
    if not isinstance(channels, dict):
        raise ValueError("final report channels are missing")
    for channel in FINAL_CHANNELS:
        value = channels.get(channel)
        raw = value.get("raw") if isinstance(value, dict) else None
        ids = [
            item.get("runId") for item in raw
            if isinstance(item, dict)
        ] if isinstance(raw, list) else []
        if len(ids) != 6 or len(set(ids)) != 6 or set(ids) != expected:
            raise ValueError(
                f"{channel} channel dropped or duplicated a run identity")
        if channel == "automatedVisual" and any(
                not isinstance(item.get("outcomes"), list) for item in raw):
            raise ValueError(
                "automatedVisual channel dropped unavailable outcomes")
        if channel == "structuralUsability" and any(
                not isinstance(item.get("outcomes"), list) for item in raw):
            raise ValueError(
                "structuralUsability channel dropped unavailable outcomes")
        if channel == "telemetryTreatment":
            for item in raw:
                metrics = item.get("metrics")
                if not isinstance(metrics, dict) or any(
                        name not in metrics for name in TOKEN_METRICS):
                    raise ValueError(
                        "telemetryTreatment channel dropped unavailable token data")
        if channel == "traceTaxonomy":
            for item in raw:
                taxonomy = item.get("taxonomy")
                if (not isinstance(taxonomy, dict)
                        or set(taxonomy.get("attributions", {})) != {
                            "capture", "semantic", "rendering", "workflow-loop"}):
                    raise ValueError(
                        "traceTaxonomy channel dropped a comparable family")
    return sorted(expected)


def json_file(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def inventory(root, omitted=()):
    root = Path(root)
    omitted = tuple(omitted)
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if (not path.is_file()
                or any(part in GENERATED for part in relative.parts)
                or any(relative.as_posix() == item
                       or relative.as_posix().startswith(item + "/")
                       for item in omitted)):
            continue
        result[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def require_protocol_amendment(manifest):
    if manifest.get("protocolAmendment") != PROTOCOL_AMENDMENT:
        raise ValueError("unsupported benchmark protocol amendment")
    return True


def validate_treatment_symmetry(prepared):
    manifest = json_file(prepared / "benchmark-manifest.json")
    require_protocol_amendment(manifest)
    if len(manifest["runs"]) != 6 or len({run["initialCandidateHash"] for run in manifest["runs"]}) != 1:
        raise ValueError("prepared candidates are not six identical neutral templates")
    for pair in (1, 2, 3):
        arms = {run["treatment"]: run for run in manifest["runs"] if run["pair"] == pair}
        baseline = prepared / arms["baseline"]["workspace"]
        harness = prepared / arms["harness"]["workspace"]
        marker = "## Treatment appendix\n"
        baseline_common, baseline_appendix = (baseline / "INSTRUCTIONS.md").read_text().split(marker, 1)
        harness_common, harness_appendix = (harness / "INSTRUCTIONS.md").read_text().split(marker, 1)
        if baseline_common != harness_common or baseline_appendix == harness_appendix:
            raise ValueError("treatment instructions differ outside the approved appendix")
        baseline_files = inventory(baseline.parent, ("template/INSTRUCTIONS.md",))
        harness_files = inventory(
            harness.parent, ("template/INSTRUCTIONS.md", "treatments/harness"))
        if baseline_files != harness_files:
            raise ValueError("prepared arms differ outside approved treatment inputs")
    return True


def verify_sidecar(path, sidecar):
    expected = Path(sidecar).read_text(encoding="ascii").strip().split()[0]
    if expected != sha256_file(path):
        raise ValueError(f"hash sidecar mismatch: {Path(path).name}")


def process_is_live(pid):
    stat = Path("/proc") / str(pid) / "stat"
    try:
        state = stat.read_text(encoding="ascii").rsplit(")", 1)[1].split()[0]
    except FileNotFoundError:
        return False
    return state != "Z"


def public_tree_hash(root):
    digest = hashlib.sha256()
    for path in sorted(Path(root).rglob("*")):
        if path.is_file():
            relative = path.relative_to(root).as_posix().encode("utf-8")
            digest.update(len(relative).to_bytes(4, "big"))
            digest.update(relative)
            digest.update(bytes.fromhex(sha256_file(path)))
    return digest.hexdigest()


def copy_blind_inputs(source, destination, manifest):
    destination.mkdir(parents=True)
    shutil.copy2(source / "benchmark-manifest.json", destination / "benchmark-manifest.json")
    shutil.copytree(source / "corpus", destination / "corpus")
    for run in manifest["runs"]:
        target = destination / "runs" / run["runId"]
        target.mkdir(parents=True)
        shutil.copy2(source / run["runRecord"], target / "run-record.json")
        shutil.copy2(source / run["runRecordHash"], target / "run-record.sha256")
        shutil.copytree(source / "runs" / run["runId"] / "evaluation", target / "evaluation")


def qualify(output):
    output = Path(output).resolve()
    if output.exists():
        raise ValueError(f"output directory already exists: {output}")
    output.mkdir(parents=True)
    logs = output / "logs"
    logs.mkdir()
    python = Path(sys.executable)
    runner = SCRIPT_ROOT / "run-benchmark.py"
    mock_omp = BENCHMARK_ROOT / "fixtures/mock-omp.py"
    common_runner = [
        python, runner, "--model", MODEL, "--max-time", "45m", "--pairs", "3"]

    prepared = output / "prepared"
    run_command("prepare", [*common_runner, "--output", prepared, "--dry-run"], logs, 0)
    treatment_symmetry = validate_treatment_symmetry(prepared)

    run_root = output / "private-runs"
    run_result = run_command(
        "runner", [*common_runner, "--output", run_root, "--omp", mock_omp,
                   "--qualification"], logs, 1)
    runner_payload = json.loads(run_result.stdout)
    if runner_payload != {
            "status": "complete-with-failures", "runs": 6, "successful": 3,
            "output": str(run_root)}:
        raise ValueError("runner completion summary drifted")

    manifest = json_file(run_root / "benchmark-manifest.json")
    require_protocol_amendment(manifest)
    by_case = {}
    records = {}
    for listed in manifest["runs"]:
        record_path = run_root / listed["runRecord"]
        verify_sidecar(record_path, run_root / listed["runRecordHash"])
        record = json_file(record_path)
        expected_case, expected_run, _expected_evaluation = CASES[(record["pair"], record["treatment"])]
        if record["schemaVersion"] != "agentic-palisade/run-record-v1":
            raise ValueError("run record schema drifted")
        if record["exit"]["classification"] != expected_run:
            raise ValueError(f"{expected_case} run classified as {record['exit']['classification']}")
        input_manifest = run_root / listed["inputManifest"]
        if record["hashes"]["inputManifest"] != sha256_file(input_manifest):
            raise ValueError("input manifest identity discontinuity")
        candidate = run_root / record["paths"]["workspace"]
        if record["hashes"]["finalCandidate"] != RUNNER.hash_candidate(candidate):
            raise ValueError("final candidate identity discontinuity")
        records[record["runId"]] = record
        by_case[expected_case] = {"listed": listed, "record": record, "candidate": candidate}

    evaluator_project = BENCHMARK_ROOT / "evaluator"
    run_command(
        "evaluator-build",
        [REPOSITORY_ROOT / "gradlew", "-p", evaluator_project, "installDist",
         "--no-daemon", "--console=plain"], logs, 0, cwd=REPOSITORY_ROOT)
    evaluator = (evaluator_project / "build/install/agentic-palisade-evaluator/bin/"
                 "agentic-palisade-evaluator")
    evaluator_env = os.environ.copy()
    evaluator_env["PALISADE_GRADLE"] = str(REPOSITORY_ROOT / "gradlew")
    identity_continuity = True
    for case_name, item in by_case.items():
        record = item["record"]
        evaluation_dir = run_root / "runs" / record["runId"] / "evaluation"
        completed = run_command(
            f"evaluate-{case_name}", [
                evaluator, "evaluate",
                "--benchmark-manifest", run_root / "benchmark-manifest.json",
                "--candidate", item["candidate"],
                "--corpus", run_root / "corpus", "--output", evaluation_dir,
                "--candidate-id", record["runId"]], logs, 0,
            cwd=REPOSITORY_ROOT, env=evaluator_env)
        evaluation = json_file(evaluation_dir / "evaluation.json")
        verify_sidecar(
            evaluation_dir / "evaluation.json", evaluation_dir / "evaluation.sha256")
        expected_status = next(
            expected for name, _run, expected in CASES.values() if name == case_name)
        if completed.stdout.strip() != expected_status or evaluation["status"] != expected_status:
            raise ValueError(f"{case_name} evaluation status mismatch")
        identity_continuity &= (
            evaluation["schemaVersion"] == "agentic-palisade-evaluation/v1"
            and evaluation["candidate"] == {
                "id": record["runId"], "sha256": record["hashes"]["finalCandidate"]}
            and evaluation["corpus"] == {
                "schemaVersion": "agentic-palisade/v1",
                "sha256": record["hashes"]["corpus"]})
        item["evaluation"] = evaluation
    if not identity_continuity:
        raise ValueError("evaluator identity continuity failed")
    good = by_case["conforming"]["evaluation"]
    broken = by_case["malformed-omp-export"]["evaluation"]
    if good["functional"]["passed"] != good["functional"]["total"]:
        raise ValueError("conforming fixture failed functional assertions")
    good_ssim = sum(item["metrics"]["luminanceSsimScale1"] for item in good["visual"]) / 3
    broken_ssim = sum(item["metrics"]["luminanceSsimScale1"] for item in broken["visual"]) / 3
    separation = good_ssim - broken_ssim
    if separation <= 0.10:
        raise ValueError("good fixture did not materially exceed broken visual metrics")

    leaked = by_case["leaked-child"]["record"]
    leaked_pid_path = run_root / leaked["paths"]["artifactRoot"] / "leaked-child.pid"
    no_process_leaks = leaked_pid_path.is_file() and not process_is_live(
        int(leaked_pid_path.read_text(encoding="ascii")))
    if not no_process_leaks:
        raise ValueError("mock OMP child remained live after runner completion")

    seed = output / "private-seed.bin"
    seed.write_bytes(bytes(range(32)))
    seed.chmod(0o600)
    review = output / "blind-review"
    mapping = output / "private-mapping.json"
    review_second = output / "blind-review-rerun"
    mapping_second = output / "private-mapping-rerun.json"
    build_cli = SCRIPT_ROOT / "build-blind-review.py"
    run_command("blind", [python, build_cli, "--run-root", run_root,
                "--review-dir", review, "--mapping", mapping,
                "--seed-file", seed], logs, 0)
    run_command("blind-rerun", [python, build_cli, "--run-root", run_root,
                "--review-dir", review_second, "--mapping", mapping_second,
                "--seed-file", seed], logs, 0)
    deterministic = public_tree_hash(review) == public_tree_hash(review_second)
    if not deterministic:
        raise ValueError("seeded public review package is not deterministic")
    BLIND.scan_package(review)
    public_text = "\n".join(
        path.read_text(encoding="utf-8") for path in review.rglob("*.json"))
    forbidden = [str(run_root), *records, "baseline", "harness", "treatment"]
    if any(value.lower() in public_text.lower() for value in forbidden):
        raise ValueError("public review package leaked private identity")

    mutations = output / "mutations"
    mutations.mkdir()
    missing_input = mutations / "missing-capture-input"
    copy_blind_inputs(run_root, missing_input, manifest)
    conforming_id = by_case["conforming"]["record"]["runId"]
    missing_capture = next(
        (missing_input / "runs" / conforming_id / "evaluation/captures").glob("*.png"))
    missing_capture.unlink()
    missing_result = run_command(
        "mutation-missing-capture", [python, build_cli, "--run-root", missing_input,
         "--review-dir", mutations / "missing-review",
         "--mapping", mutations / "missing-mapping.json", "--seed-file", seed],
        logs, 2)
    missing_retained = (
        len(list((missing_input / "runs").glob("*/run-record.json"))) == 6
        and "capture" in missing_result.stderr.lower())

    public_manifest = json_file(review / "manifest.json")
    labels = public_manifest["labels"]
    response = {
        "schemaVersion": "agentic-palisade/human-ratings-v1",
        "packageManifestSha256": sha256_file(review / "manifest.json"),
        "fidelity": {label: 7 - index for index, label in enumerate(labels)},
        "ranking": {label: index + 1 for index, label in enumerate(labels)},
        "preferred": {pair["id"]: pair["candidates"][0]
                      for pair in public_manifest["matchedPairs"]},
        "comments": {"overall": "Deterministic qualification fixture; no human review."},
    }
    ratings = review / "human-ratings.json"
    ratings.write_bytes(canonical_bytes(response))
    ratings_before = ratings.read_bytes()
    unblind_cli = SCRIPT_ROOT / "unblind-report.py"

    tampered_mapping = mutations / "tampered-mapping.json"
    shutil.copy2(mapping, tampered_mapping)
    mapping_value = json_file(tampered_mapping)
    mapping_value["seedHex"] = "ff" * 32
    tampered_mapping.write_bytes(canonical_bytes(mapping_value))
    tampered_mapping.chmod(0o600)
    mapping_result = run_command(
        "mutation-mapping", [python, unblind_cli, "lock", "--run-root", run_root,
         "--review-dir", review, "--mapping", tampered_mapping,
         "--ratings", ratings, "--lock", mutations / "mapping.lock"], logs, 2)
    mapping_retained = (
        ratings.read_bytes() == ratings_before and tampered_mapping.is_file()
        and "mapping" in mapping_result.stderr.lower())

    response_review = mutations / "response-review"
    shutil.copytree(review, response_review)
    tampered_ratings = response_review / "human-ratings.json"
    tampered_value = json_file(tampered_ratings)
    tampered_value["packageManifestSha256"] = "0" * 64
    tampered_ratings.write_bytes(canonical_bytes(tampered_value))
    tampered_ratings_before = tampered_ratings.read_bytes()
    response_result = run_command(
        "mutation-response", [python, unblind_cli, "lock", "--run-root", run_root,
         "--review-dir", response_review, "--mapping", mapping,
         "--ratings", tampered_ratings, "--lock", mutations / "response.lock"],
        logs, 2)
    response_retained = rejected_input_retained(
        tampered_ratings, tampered_ratings_before, response_result,
        "manifest hash")

    lock = output / "review-lock.json"
    final_report = output / "final-report.json"
    run_command("lock", [python, unblind_cli, "lock", "--run-root", run_root,
                "--review-dir", review, "--mapping", mapping,
                "--ratings", ratings, "--lock", lock], logs, 0)
    run_command("unblind", [python, unblind_cli, "unblind", "--run-root", run_root,
                "--review-dir", review, "--mapping", mapping,
                "--ratings", ratings, "--lock", lock,
                "--output", final_report], logs, 0)
    report = json_file(final_report)
    if report["schemaVersion"] != "agentic-palisade/final-report-v1":
        raise ValueError("final report schema drifted")
    expected_run_ids = set(records)
    final_channel_run_ids = validate_final_channels(
        report, expected_run_ids)
    channel_drop_rejected = True
    channel_drop_retained = True
    for channel in FINAL_CHANNELS:
        mutated = copy.deepcopy(report)
        mutated["channels"][channel]["raw"].pop()
        mutation_path = mutations / f"final-report-drop-{channel}.json"
        mutation_path.write_bytes(canonical_bytes(mutated))
        mutation_before = mutation_path.read_bytes()
        try:
            validate_final_channels(mutated, expected_run_ids)
            channel_drop_rejected = False
        except ValueError:
            pass
        channel_drop_retained &= mutation_path.read_bytes() == mutation_before

    cases = {
        case_name: {
            "runId": item["record"]["runId"],
            "pair": item["record"]["pair"],
            "treatment": item["record"]["treatment"],
            "runClassification": item["record"]["exit"]["classification"],
            "evaluationStatus": item["evaluation"]["status"],
        }
        for case_name, item in sorted(by_case.items())
    }
    summary = {
        "schemaVersion": "agentic-palisade/qualification-v1",
        "status": "qualified",
        "runnerExit": run_result.returncode,
        "runCount": len(manifest["runs"]),
        "retainedRunRecords": len(records),
        "cases": cases,
        "finalChannelRunIds": final_channel_run_ids,
        "identityContinuity": identity_continuity,
        "treatmentSymmetry": treatment_symmetry,
        "noProcessLeaks": no_process_leaks,
        "visualSeparation": {
            "goodMeanSsim": good_ssim, "brokenMeanSsim": broken_ssim,
            "ssimDelta": separation},
        "deterministicRerunHashes": deterministic,
        "mutations": {
            "missing-capture": {"rejected": missing_result.returncode != 0,
                                "dataRetained": missing_retained},
            "mapping-tamper": {"rejected": mapping_result.returncode != 0,
                               "dataRetained": mapping_retained},
            "response-tamper": {"rejected": response_result.returncode != 0,
                                "dataRetained": response_retained},
            "final-channel-drop": {
                "rejected": channel_drop_rejected,
                "dataRetained": channel_drop_retained},
        },
        "finalReportSha256": sha256_file(final_report),
    }
    if any(not mutation["dataRetained"] for mutation in summary["mutations"].values()):
        raise ValueError("mutation rejection deleted or changed source data")
    (output / "qualification-summary.json").write_bytes(canonical_bytes(summary))
    print(json.dumps({"status": "qualified", "runs": 6,
                      "summary": str(output / "qualification-summary.json")},
                     sort_keys=True))
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args(argv)
    try:
        qualify(arguments.output)
        return 0
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"qualify-pipeline: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
