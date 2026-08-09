#!/usr/bin/env python3
"""Checks the committed exact runtime coordinate for one compatibility profile."""

import argparse
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {"minimum": "1.0.0", "current": "2.0.0"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", choices=EXPECTED)
    args = parser.parse_args()
    lock_name = "gradle-current.lockfile" if args.profile == "current" else "gradle.lockfile"
    lock = ROOT / "harness-agent-runtime" / lock_name
    coordinate = f"io.github.teemuki8:agent-runtime-core:{EXPECTED[args.profile]}="
    if coordinate not in lock.read_text(encoding="utf-8"):
        raise SystemExit(f"{args.profile} profile does not lock {coordinate[:-1]}")
    print(f"{args.profile} ecosystem profile: {coordinate[:-1]}")


if __name__ == "__main__":
    main()
