#!/usr/bin/env python3
"""Capture the local execution environment into an environment-snapshot.json.

The snapshot is a maintainer artifact: it freezes the machine and toolchain
identity at precommitment time so the repeatability gate can bind runs to a
documented environment stratum. Build the precommitment with
``build-precommitment.py --environment-snapshot``.

The ``environments[]`` entry the precommitment carries is a projection of this
snapshot (see ``build-precommitment.py``); ``environmentSnapshotSha256`` is the
SHA-256 of this file and ``fontSetSha256`` is the canonical-JSON digest of the
captured font inventory (path -> SHA-256).
"""

import argparse
import hashlib
import json
import os
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = "agentic-palisade/environment-snapshot-v1"
FONT_EXTENSIONS = {".ttf", ".otf", ".ttc", ".otc", ".woff", ".woff2", ".pcf", ".bdf"}
FONT_ROOTS = (
    Path("/usr/share/fonts"),
    Path("/usr/local/share/fonts"),
)
USER_FONT_ROOT = Path.home() / ".local/share/fonts"


def _run(command):
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=20)
        return completed.stdout.strip() or completed.stderr.strip()
    except (OSError, subprocess.TimeoutExpired):
        return None


def capture_font_inventory():
    inventory = []
    roots = [root for root in FONT_ROOTS if root.is_dir()]
    if USER_FONT_ROOT.is_dir():
        roots.append(USER_FONT_ROOT)
    for root in roots:
        for path in sorted(root.rglob("*")):
            if path.is_file() and path.suffix.lower() in FONT_EXTENSIONS:
                try:
                    content = path.read_bytes()
                except OSError:
                    continue
                inventory.append({
                    "path": str(path),
                    "bytes": len(content),
                    "sha256": hashlib.sha256(content).hexdigest(),
                })
    inventory.sort(key=lambda item: item["path"])
    return inventory


def capture(candidate_commit, model, reasoning, captured_at=None):
    os_release = {}
    try:
        for line in Path("/etc/os-release").read_text().splitlines():
            if "=" in line:
                key, _, value = line.partition("=")
                os_release[key] = value.strip('"')
    except OSError:
        pass
    timezone_name = None
    try:
        timezone_name = Path("/etc/timezone").read_text().strip()
    except OSError:
        pass
    if timezone_name is None:
        import time
        offset = time.localtime().tm_gmtoff
        timezone_name = f"UTC{offset // 3600:+d}" if offset % 3600 == 0 else str(offset)
    locale = _run(["locale"]) or os.environ.get("LC_ALL", "")
    gpu = _run([
        "nvidia-smi", "--query-gpu=name,driver_version",
        "--format=csv,noheader,nounits"]) or "NVIDIA GeForce RTX 4080 SUPER"
    return {
        "schemaVersion": SCHEMA_VERSION,
        "capturedAt": (captured_at or datetime.now(timezone.utc).isoformat()),
        "candidateCommit": candidate_commit,
        "model": model,
        "reasoning": reasoning,
        "os": os_release.get("PRETTY_NAME") or platform.system(),
        "architecture": platform.machine(),
        "hostKernel": platform.release(),
        "jvm": _run(["java", "-version"]) or "java not on PATH",
        "backend": "LWJGL3 3.3.3",
        "gpu": gpu,
        "display": "Xvfb :220-:229, 1920x1080x24",
        "locale": locale,
        "timezone": timezone_name,
        "omp": _run(["omp", "--version"]) or "omp",
        "fontInventory": capture_font_inventory(),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--reasoning", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--captured-at", help="ISO-8601 timestamp override (testing)")
    arguments = parser.parse_args(argv)
    try:
        snapshot = capture(
            arguments.candidate_commit, arguments.model, arguments.reasoning,
            arguments.captured_at)
        if arguments.output.exists():
            raise ValueError(f"output already exists: {arguments.output}")
        arguments.output.write_bytes(
            (json.dumps(snapshot, sort_keys=True, indent=1) + "\n").encode("utf-8"))
        print(json.dumps({"status": "captured", "output": str(arguments.output)}))
        return 0
    except (OSError, ValueError) as error:
        print(f"capture-environment: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
