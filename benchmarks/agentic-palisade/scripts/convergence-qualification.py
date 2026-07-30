#!/usr/bin/env python3
"""Deterministic matched qualification for diagnostic and reuse mechanisms."""

import argparse
import hashlib
import json
from pathlib import Path


SCHEMA_VERSION = "agentic-palisade/convergence-qualification-v1"
POLICY = {
    "schemaVersion": "recovery-policy/v1",
    "maxSchemaRecoveries": 3,
    "maxStateRetries": 3,
    "maxUnchangedInspectCycles": 3,
    "maxUnchangedBuilds": 1,
    "maxUnchangedLaunches": 1,
    "maxWallTimeMillis": 30_000,
}
QUALITY_CHANNELS = (
    "semantic",
    "deterministicTransitions",
    "automatedVisual",
    "structuralUsability",
    "captureRepeatability",
    "humanVisual",
)
COSTS = ("wallTimeMillis", "inputTokens", "edits", "builds", "launches")


def canonical_bytes(value):
    return (json.dumps(
        value, sort_keys=True, separators=(",", ":"),
        ensure_ascii=False) + "\n").encode("utf-8")


def digest(value):
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def quality():
    return {
        name: {"status": "pass", "evidence": f"fixture:{name}"}
        for name in QUALITY_CHANNELS
    }


def diagnostic_run(pair, treatment):
    actionable = treatment == "actionable"
    retries = 1 if actionable else 4
    terminal = None
    if not actionable:
        terminal_body = {
            "schemaVersion": "terminal-recovery-record/v1",
            "code": "LOOP_DETECTED",
            "ruleId": "equivalent-schema-error/v1",
            "consumed": retries,
            "remaining": 0,
            "lastOperation": "ui_screenshot",
        }
        terminal = {**terminal_body, "digest": digest(terminal_body)}
    offset = pair * 7
    return {
        "runId": f"diagnostic-{pair}-{treatment}",
        "pair": pair,
        "mechanism": "actionable-diagnostic",
        "treatment": treatment,
        "frozenInputSha256": digest({
            "pair": pair,
            "request": "invalid-screenshot-maxBytes",
        }),
        "policy": POLICY,
        "iterations": retries + 1,
        "diagnosticRetries": retries,
        "terminal": terminal,
        "costs": {
            "wallTimeMillis": (180 if actionable else 420) + offset,
            "inputTokens": (1_200 if actionable else 2_400) + offset,
            "edits": 1,
            "builds": 1,
            "launches": 1,
        },
        "quality": quality(),
    }


def reuse_run(pair, treatment):
    enabled = treatment == "enabled"
    offset = pair * 11
    decisions = [{
        "iteration": 1,
        "decision": "REBUILD_AND_RELAUNCH",
        "reason": "no-prior-identity/v1",
    }]
    for iteration in (2, 3):
        decisions.append({
            "iteration": iteration,
            "decision": (
                "REUSE_BUILD_AND_RUNTIME" if enabled
                else "REBUILD_AND_RELAUNCH"),
            "reason": (
                "identical-healthy-state/v1" if enabled
                else "negative-control-disabled/v1"),
        })
    return {
        "runId": f"reuse-{pair}-{treatment}",
        "pair": pair,
        "mechanism": "safe-reuse",
        "treatment": treatment,
        "frozenInputSha256": digest({
            "pair": pair,
            "source": "identical",
            "runtime": "healthy",
        }),
        "policy": POLICY,
        "iterations": 3,
        "reuseDecisions": decisions,
        "terminal": None,
        "costs": {
            "wallTimeMillis": (240 if enabled else 640) + offset,
            "inputTokens": 1_500 + offset,
            "edits": 1,
            "builds": 1 if enabled else 3,
            "launches": 1 if enabled else 3,
        },
        "quality": quality(),
    }


def paired_deltas(runs, mechanism, treatment, control):
    deltas = []
    for pair in (1, 2, 3):
        selected = {
            run["treatment"]: run
            for run in runs
            if run["mechanism"] == mechanism and run["pair"] == pair
        }
        if set(selected) != {treatment, control}:
            raise ValueError(f"incomplete {mechanism} pair {pair}")
        if selected[treatment]["frozenInputSha256"] != selected[control]["frozenInputSha256"]:
            raise ValueError(f"non-identical frozen input in {mechanism} pair {pair}")
        deltas.append({
            "pair": pair,
            "delta": {
                name: selected[treatment]["costs"][name]
                - selected[control]["costs"][name]
                for name in COSTS
            },
        })
    return {
        "treatmentMinusControl": deltas,
        "descriptiveUncertainty": {
            name: {
                "minimum": min(item["delta"][name] for item in deltas),
                "maximum": max(item["delta"][name] for item in deltas),
                "denominator": 3,
            }
            for name in COSTS
        },
    }


def qualify():
    runs = []
    for pair in (1, 2, 3):
        runs.extend([
            diagnostic_run(pair, "actionable"),
            diagnostic_run(pair, "generic-control"),
            reuse_run(pair, "enabled"),
            reuse_run(pair, "disabled-control"),
        ])
    for run in runs:
        if set(run["quality"]) != set(QUALITY_CHANNELS):
            raise ValueError(f"{run['runId']} dropped a quality channel")
        if any(value["status"] != "pass" for value in run["quality"].values()):
            raise ValueError(f"{run['runId']} has a quality regression")
        if run["iterations"] < 1 or run["iterations"] > 5:
            raise ValueError(f"{run['runId']} exceeded the iteration precommitment")
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": "qualified",
        "runCount": len(runs),
        "policy": POLICY,
        "costMetrics": list(COSTS),
        "qualityChannels": list(QUALITY_CHANNELS),
        "runs": runs,
        "contrasts": {
            "actionableDiagnostic": paired_deltas(
                runs, "actionable-diagnostic", "actionable", "generic-control"),
            "safeReuse": paired_deltas(
                runs, "safe-reuse", "enabled", "disabled-control"),
        },
        "interpretation": (
            "Causal wording is limited to these deterministic controlled fixtures; "
            "the result is not a population estimate or a new measured agent batch."
        ),
    }
    return {**report, "digest": digest(report)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args(argv)
    report = qualify()
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
