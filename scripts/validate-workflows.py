#!/usr/bin/env python3
"""Fail closed when release workflow security invariants drift."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
errors: list[str] = []


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


require("\n    env:\n" not in release, "release job must not have job-scoped secrets")
for marker in (
    "TRUSTED_RELEASE_PUBLIC_KEY: ${{ secrets.RELEASE_SIGNING_PUBLIC_KEY }}",
    "TRUSTED_RELEASE_FINGERPRINT: ${{ vars.RELEASE_SIGNING_FINGERPRINT }}",
    'export GNUPGHOME="$(mktemp -d)"',
    'trap \'rm -rf "$GNUPGHOME"\' EXIT',
    'if [[ "$actual" != "$expected" ]]',
    'git fetch --force --no-tags origin "refs/tags/$tag:refs/tags/$tag"',
    'git tag --verify "$tag"',
):
    require(marker in release, f"trusted tag verification marker missing: {marker}")
require(
    "if: ${{ ! hashFiles('.release-gate-exception') || "
    "github.ref_name != 'v1.2.0' }}" in release,
    "release gate exception must be mechanically limited to v1.2.0",
)


require('--header "Authorization:' not in release,
        "Central authorization header must never be a curl argv value")
require(release.count('trap \'rm -f "$curl_config"\' EXIT') == 3,
        "every Central step must delete its curl config")
require(release.count('chmod 600 "$curl_config"') == 3,
        "every Central curl config must be mode 0600")
require(release.count('curl --config "$curl_config"') == 4,
        "every Central request must use the protected curl config")
require(release.count("MAVEN_SIGNING_KEY: ${{ secrets.MAVEN_SIGNING_KEY }}") == 1,
        "private signing key must be scoped to exactly one run step")

lock_gate = "git diff --exit-code -- settings-gradle.lockfile gradle.lockfile " \
    "'**/gradle.lockfile' gradle/verification-metadata.xml"
require(lock_gate in ci, "lock drift gate must include settings, root, subprojects, metadata")
native_test_exclusions = "-x :harness-lwjgl3:test -x :harness-fixtures:test"
mac_check = f"./gradlew clean check {native_test_exclusions} --warning-mode=fail"
mac_compile = "./gradlew :harness-lwjgl3:testClasses " \
    ":harness-fixtures:testClasses --warning-mode=fail"
windows_check = f".\\gradlew.bat clean check {native_test_exclusions} --warning-mode=fail"
windows_compile = ".\\gradlew.bat :harness-lwjgl3:testClasses " \
    ":harness-fixtures:testClasses --warning-mode=fail"
require(mac_check in ci,
        "macOS check must exclude native tests that require first-thread execution")
require(mac_compile in ci,
        "macOS must compile the native adapter and reference fixture")
require(windows_check in ci,
        "Windows must run backend-neutral checks without unavailable hosted OpenGL")
require(windows_compile in ci,
        "Windows must compile the native adapter and reference fixture")
if mac_check in ci and mac_compile in ci:
    require(ci.index(mac_check) < ci.index(mac_compile),
            "macOS backend-neutral check must precede native fixture compilation")

qualification_command = (
    "xvfb-run -a python3 benchmarks/agentic-palisade/scripts/"
    "qualify-pipeline.py --output \"$RUNNER_TEMP/agentic-palisade-qualification\""
)
symmetry_preflight = (
    "xvfb-run -a python3 \\\n"
    "            benchmarks/agentic-palisade/scripts/treatment-preflight.py"
)
require(qualification_command in ci,
        "CI must execute the deterministic Agentic Palisade qualification under Xvfb")
require(ci.count(qualification_command) == 1,
        "CI must execute the paid-agent-free qualification exactly once")
require(symmetry_preflight in ci,
        "CI must execute the offline treatment build and launch preflight")
require(ci.count(symmetry_preflight) == 1,
        "CI must execute the treatment symmetry preflight exactly once")
template_native_test = (
    "xvfb-run -a ./gradlew -p benchmarks/agentic-palisade/template test"
)
require(template_native_test in ci,
        "CI must execute the LWJGL3 candidate-template tests under Xvfb")
for marker in (
        "python3 benchmarks/agentic-palisade/scripts/test-corpus.py",
        "python3 benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py",
        "python3 benchmarks/agentic-palisade/scripts/test-telemetry.py",
        "python3 benchmarks/agentic-palisade/scripts/test-runner.py",
        "python3 benchmarks/agentic-palisade/scripts/test-blinding.py",
        "./gradlew -p benchmarks/agentic-palisade/evaluator test",
        "./gradlew -p benchmarks/agentic-palisade/template test"):
    require(marker in ci, f"benchmark qualification prerequisite missing: {marker}")
require("omp --model" not in ci and "run-benchmark.py --output" not in ci,
        "CI must never invoke measured OMP agents")
require(
    "if: github.event_name == 'pull_request' && "
    "vars.DEPENDENCY_REVIEW_ENABLED == 'true'" in ci,
    "dependency review must require an explicit repository capability flag",
)


for path, text in (("ci.yml", ci), ("release.yml", release)):
    for action in re.findall(r"uses:\s+[^@\s]+@([^\s#]+)", text):
        require(bool(re.fullmatch(r"[0-9a-f]{40}", action)),
                f"{path} action is not pinned to a full commit SHA: {action}")

if errors:
    print("workflow validation failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("workflow security invariants: PASS")
