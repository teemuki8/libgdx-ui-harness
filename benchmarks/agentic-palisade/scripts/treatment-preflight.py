#!/usr/bin/env python3
"""Compile, test, and launch one candidate-bound treatment pair offline."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys


SCRIPT_ROOT = Path(__file__).resolve().parent
BENCHMARK_ROOT = SCRIPT_ROOT.parent
REPOSITORY_ROOT = BENCHMARK_ROOT.parent.parent
COMMAND_TIMEOUT_SECONDS = 180


def load_runner():
    path = SCRIPT_ROOT / "run-benchmark.py"
    specification = importlib.util.spec_from_file_location(
        "agentic_palisade_run_benchmark", path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def prepare_pair(output, candidate_repository, candidate_version):
    runner = load_runner()
    repository, _ = runner._candidate_repository(
        candidate_repository, candidate_version)
    treatment_inputs = runner._treatment_inputs()
    hashes = {
        "prompt": runner.sha256_bytes(
            (BENCHMARK_ROOT / "prompts/task.md").read_bytes()),
        "corpus": runner.hash_tree(BENCHMARK_ROOT / "corpus"),
        "template": runner.hash_tree(BENCHMARK_ROOT / "template"),
        "protocol": runner.sha256_bytes(
            (BENCHMARK_ROOT / "PROTOCOL.md").read_bytes()),
    }
    output.mkdir(parents=True, exist_ok=False)
    return {
        treatment: runner._prepare_run(
            output, 1, treatment, index, hashes, treatment_inputs,
            model="treatment-preflight",
            reasoning=runner.FIXED_REASONING,
            profile="low-confidence",
            max_seconds=runner.MIN_SECONDS,
            candidate_repository=candidate_repository,
            candidate_version=candidate_version)
        for index, treatment in enumerate(("baseline", "harness"))
    }


def gradle_commands(item):
    runtime = item["_runtime"]
    workspace = runtime["workspace"]
    wrapper = workspace.parents[2] / "gradlew"
    common = [str(wrapper), "-p", str(workspace)]
    offline = [str(wrapper), "--offline", "-p", str(workspace)]
    options = ["--no-daemon", "--console=plain", "--warning-mode=fail"]
    if item["treatment"] == "baseline":
        seed_prefix = common
        prefix = offline
        launch = [
            *prefix, "run",
            "--args=--commands " + str(runtime["runDir"] / "preflight-commands.ndjson")
            + " --evidence " + str(runtime["runDir"] / "preflight-evidence"),
            *options,
        ]
    else:
        overlay = workspace.parent / "treatments/harness/build-overlay.gradle.kts"
        seed_prefix = [*common, "--init-script", str(overlay)]
        prefix = [*offline, "--init-script", str(overlay)]
        launch = [*prefix, "run", *options]
    return {
        "seed": [
            [*seed_prefix, "test", *options],
        ],
        "compile": [*prefix, "classes", *options],
        "test": [*prefix, "test", *options],
        "launch": launch,
    }


def markup_identity(workspace):
    try:
        return load_runner().markup_identity(workspace)
    except ValueError as error:
        raise RuntimeError(str(error)) from error


def shared_markup_identity(items):
    identities = {
        treatment: markup_identity(item["_runtime"]["workspace"])
        for treatment, item in items.items()
    }
    if identities["baseline"] != identities["harness"]:
        raise RuntimeError("prepared treatments do not share identical markup inputs")
    return identities["baseline"]


def run_command(command, environment, standard_input=None):
    completed = subprocess.run(
        command,
        cwd=REPOSITORY_ROOT,
        env=environment,
        input=standard_input,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=COMMAND_TIMEOUT_SECONDS,
        check=False,
    )
    if completed.returncode != 0:
        tail = completed.stdout[-8_192:]
        raise RuntimeError(
            f"preflight command failed ({completed.returncode}): "
            f"{' '.join(command)}\n{tail}")


def preflight(arguments):
    output = arguments.output.resolve()
    items = prepare_pair(
        output, arguments.candidate_maven_repository.resolve(),
        arguments.candidate_version)
    markup = shared_markup_identity(items)
    environment = dict(os.environ)
    environment["GRADLE_USER_HOME"] = str(arguments.gradle_user_home.resolve())
    results = []
    for treatment in ("baseline", "harness"):
        item = items[treatment]
        runtime = item["_runtime"]
        commands = gradle_commands(item)
        if arguments.seed_dependencies:
            for command in commands["seed"]:
                run_command(command, environment)
        if treatment == "baseline":
            command_file = runtime["runDir"] / "preflight-commands.ndjson"
            command_file.write_text('{"command":"close"}\n', encoding="utf-8")
            standard_input = None
        else:
            standard_input = ""
        for phase in ("compile", "test", "launch"):
            run_command(
                commands[phase], environment,
                standard_input if phase == "launch" else None)
            results.append({"treatment": treatment, "phase": phase, "status": "pass"})
        if treatment == "baseline":
            result = runtime["runDir"] / "preflight-evidence/results.ndjson"
            if not result.is_file():
                raise RuntimeError("baseline launch produced no results.ndjson")
    print(json.dumps({
        "schemaVersion": "agentic-palisade/treatment-preflight-v1",
        "offline": True,
        "dependenciesSeeded": arguments.seed_dependencies,
        "candidateVersion": arguments.candidate_version,
        "markup": markup,
        "results": results,
    }, sort_keys=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--candidate-maven-repository", type=Path, required=True)
    parser.add_argument("--candidate-version", required=True)
    parser.add_argument("--gradle-user-home", type=Path, required=True)
    parser.add_argument("--seed-dependencies", action="store_true")
    arguments = parser.parse_args()
    try:
        preflight(arguments)
        return 0
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"treatment-preflight: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
