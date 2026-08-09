#!/usr/bin/env python3
"""Fail closed when release workflow security invariants drift."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
readme = (ROOT / "README.md").read_text(encoding="utf-8")
getting_started = (ROOT / "docs/guides/getting-started.md").read_text(encoding="utf-8")
benchmark_guide = (ROOT / "benchmarks/README.md").read_text(encoding="utf-8")
release_notes_path = ROOT / "docs/releases/v1.2.1.md"
release_notes = (release_notes_path.read_text(encoding="utf-8")
                 if release_notes_path.is_file() else "")
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
for forbidden in (
        "release-gate.py verify",
        ".release-gate-exception",
        "release-evidence-$GITHUB_SHA",
        "Verify sealed repeatability decision"):
    require(forbidden not in release,
            f"empirical benchmark machinery must not gate publication: {forbidden}")
for marker in (
        "runs-on: ubuntu-latest",
        "clean check javadoc centralBundle --warning-mode=fail",
        "VALIDATED) exit 0",
        "PUBLISHED) exit 0"):
    require(marker in release, f"deterministic release gate marker missing: {marker}")


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
require("candidate_version='1.2.1-candidate.ci'" in ci,
        "CI treatment preflight must publish the 1.2.1 candidate")
require(
    "if: github.event_name == 'pull_request' && "
    "vars.DEPENDENCY_REVIEW_ENABLED == 'true'" in ci,
    "dependency review must require an explicit repository capability flag",
)

require("`1.2.1` is the current release" in readme,
        "README must identify 1.2.1 as the current release")
require("1.1.0` is the current release" not in readme,
        "README contains stale 1.1.0 current-release text")
for document_name, document in (("README", readme),
                                ("getting-started", getting_started)):
    require(document.count("io.github.teemuki8:harness-lwjgl3:1.2.1") == 1,
            f"{document_name} must show the current harness-lwjgl3 coordinate")
    require(document.count("io.github.teemuki8:harness-mcp:1.2.1") == 1,
            f"{document_name} must show the current harness-mcp coordinate")
for marker in (
        "libgdx-ui-markup:0.4.1", "agent-runtime 1.0.0", "agent-runtime 2.0.0",
        "deterministic", "empirical", "markup-only"):
    require(marker in release_notes,
            f"1.2.1 release notes missing compatibility marker: {marker}")
for marker in ("markup-only", "libgdx-ui-markup:0.4.1", "HarnessSemanticSink"):
    require(marker in benchmark_guide,
            f"benchmark guide missing markup treatment marker: {marker}")


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
