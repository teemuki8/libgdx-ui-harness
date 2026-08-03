#!/usr/bin/env python3
"""Fail-closed treatment-input symmetry validator and mutation probes."""

import hashlib
import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

APPENDIX_MARKER = b"## Treatment appendix\n"
IGNORED_DIRECTORIES = {".gradle", "build", "__pycache__"}
EXPECTED_SHARED_HASHES = {
    "PROTOCOL.md": "952ad23e0794165d0c4bf1a32d7c4605fa1792961c6203ae78f4d354916d910f",
    "template": "5f737b1b4ac12c6a85c10dd8fc8eebea69ecb3dce2d49924978d5d3329c414db",
    "corpus": "d9d973fc53ba753fc665d64d299647444f51d26d42f87d21f14a162ac680065b",
}
EXPECTED_COMMON_HASH = (
    "d910cc7576cf941784dfcfcf002bc9eb35e985e4ede522b0e50e3a4067417fb3"
)
EXPECTED_APPENDIX_HASHES = {
    "baseline": "a9810b5911cf89ff97cd729d9923ae8e6deb455a5bbaaba427d1976f44ccdbed",
    "harness": "57bf1fc1b11e9fc9ad79f2c29eebaf46ee338fc67323c2710a28c5df30c5358e",
}
QUALIFIED_COORDINATES = {
    b'io.github.teemuki8:harness-lwjgl3:$harnessVersion',
    b'io.github.teemuki8:harness-mcp:$harnessVersion',
}
GRADLE_WRAPPER = "../../../gradlew"
COMMON_BUILD_COMMAND = (
    "../../../gradlew -p . classes --no-daemon --console=plain --warning-mode=fail"
)
COMMON_TEST_COMMAND = (
    "../../../gradlew -p . test --no-daemon --console=plain --warning-mode=fail"
)
HARNESS_OVERLAY_ARGUMENT = (
    " --init-script ../treatments/harness/build-overlay.gradle.kts"
)


class SymmetryError(AssertionError):
    pass


def load_preflight():
    path = Path(__file__).with_name("treatment-preflight.py")
    specification = importlib.util.spec_from_file_location(
        "agentic_palisade_treatment_preflight", path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def tree_hash(path):
    """Hashes path names and file digests, excluding only local generated output."""
    if path.is_symlink():
        raise SymmetryError(f"symbolic link is not allowed in shared input: {path}")
    digest = hashlib.sha256()
    if path.is_file():
        digest.update(path.name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        return digest.hexdigest()
    if not path.is_dir():
        raise SymmetryError(f"shared input is missing: {path}")
    for entry in sorted(path.rglob("*")):
        relative = entry.relative_to(path)
        if any(part in IGNORED_DIRECTORIES for part in relative.parts):
            continue
        if entry.is_symlink():
            raise SymmetryError(f"symbolic link is not allowed in shared input: {entry}")
        if entry.is_file():
            digest.update(relative.as_posix().encode("utf-8"))
            digest.update(b"\0")
            digest.update(hashlib.sha256(entry.read_bytes()).digest())
    return digest.hexdigest()


def split_instructions(path):
    content = path.read_bytes()
    if content.count(APPENDIX_MARKER) != 1:
        raise SymmetryError(f"instruction appendix marker must occur exactly once: {path}")
    return content.split(APPENDIX_MARKER, 1)


def validate_treatment_paths(root):
    treatment_root = root / "treatments"
    if not treatment_root.is_dir():
        raise SymmetryError("treatments directory is missing")
    for entry in sorted(treatment_root.rglob("*")):
        if entry.is_symlink():
            raise SymmetryError(f"treatment symbolic link is not allowed: {entry}")
        if not entry.is_file():
            continue
        relative = entry.relative_to(treatment_root)
        allowed = relative == Path("baseline/INSTRUCTIONS.md") or (
            relative == Path("harness/INSTRUCTIONS.md")
            or relative == Path("harness/build-overlay.gradle.kts")
            or (
                len(relative.parts) >= 6
                and relative.parts[:4] in {
                    ("harness", "src", "main", "java"),
                    ("harness", "src", "test", "java"),
                }
                and relative.suffix == ".java"
            )
        )
        if not allowed:
            raise SymmetryError(f"unexpected treatment-only file: {relative.as_posix()}")


def validate_treatment_symmetry(root):
    root = Path(root).resolve()
    observed = {}
    for relative, expected in EXPECTED_SHARED_HASHES.items():
        observed[relative] = tree_hash(root / relative)
        if observed[relative] != expected:
            raise SymmetryError(f"shared input changed: {relative}")

    validate_treatment_paths(root)
    shared_by_arm = {}
    appendices_by_arm = {}
    for arm in ("baseline", "harness"):
        common, appendix = split_instructions(
            root / "treatments" / arm / "INSTRUCTIONS.md"
        )
        shared_by_arm[arm] = common
        appendices_by_arm[arm] = appendix.decode("utf-8")
        if sha256(common) != EXPECTED_COMMON_HASH:
            raise SymmetryError(f"shared task wording changed in {arm}")
        if sha256(appendix) != EXPECTED_APPENDIX_HASHES[arm]:
            raise SymmetryError(f"unapproved treatment appendix change in {arm}")
    if shared_by_arm["baseline"] != shared_by_arm["harness"]:
        raise SymmetryError("shared task wording differs between treatments")
    common_text = shared_by_arm["baseline"].decode("utf-8")
    if common_text.count(GRADLE_WRAPPER) != 1 or "authorized exception" not in common_text:
        raise SymmetryError("shared instructions must authorize the fixed Gradle Wrapper")
    baseline_appendix = appendices_by_arm["baseline"]
    harness_appendix = appendices_by_arm["harness"]
    if baseline_appendix.count(GRADLE_WRAPPER) != 3:
        raise SymmetryError("baseline must receive compile, test, and launch wrapper commands")
    if harness_appendix.count(GRADLE_WRAPPER) != 3:
        raise SymmetryError("harness must receive compile, test, and launch wrapper commands")
    normalized_harness = harness_appendix.replace(HARNESS_OVERLAY_ARGUMENT, "")
    for command in (COMMON_BUILD_COMMAND, COMMON_TEST_COMMAND):
        if command not in baseline_appendix or command not in normalized_harness:
            raise SymmetryError("treatments must receive equivalent build and test commands")
    if "build-overlay.gradle.kts" in baseline_appendix:
        raise SymmetryError("baseline must not receive the harness overlay")
    if harness_appendix.count("build-overlay.gradle.kts") != 3:
        raise SymmetryError("harness commands must use only the approved overlay")

    overlay = (root / "treatments/harness/build-overlay.gradle.kts").read_bytes()
    for coordinate in QUALIFIED_COORDINATES:
        if overlay.count(coordinate) != 1:
            raise SymmetryError(
                f"generated overlay must contain {coordinate.decode()} exactly once"
            )
    for marker in (
            b"candidate-version.txt", b"candidate-maven",
            b"qualifiedHarnessCandidate", b"standardInput = System.`in`"):
        if overlay.count(marker) != 1:
            raise SymmetryError(
                f"generated overlay must bind {marker.decode()} exactly once")
    return observed


def copy_benchmark(source, destination):
    shutil.copytree(
        source,
        destination,
        ignore=shutil.ignore_patterns(".gradle", "build", "__pycache__"),
    )


class TreatmentSymmetryMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[1]

    def test_approved_inputs_are_symmetric(self):
        observed = validate_treatment_symmetry(self.root)
        self.assertEqual(EXPECTED_SHARED_HASHES, observed)

    def test_both_arms_receive_the_same_explicit_wrapper_build_and_test_access(self):
        common_by_arm = {}
        appendix_by_arm = {}
        for arm in ("baseline", "harness"):
            common, appendix = split_instructions(
                self.root / "treatments" / arm / "INSTRUCTIONS.md"
            )
            common_by_arm[arm] = common.decode("utf-8")
            appendix_by_arm[arm] = appendix.decode("utf-8")

        self.assertEqual(common_by_arm["baseline"], common_by_arm["harness"])
        self.assertIn(GRADLE_WRAPPER, common_by_arm["baseline"])
        self.assertIn("authorized exception", common_by_arm["baseline"])
        self.assertIn(COMMON_BUILD_COMMAND, appendix_by_arm["baseline"])
        self.assertIn(COMMON_TEST_COMMAND, appendix_by_arm["baseline"])
        normalized_harness = appendix_by_arm["harness"].replace(
            HARNESS_OVERLAY_ARGUMENT, ""
        )
        self.assertIn(COMMON_BUILD_COMMAND, normalized_harness)
        self.assertIn(COMMON_TEST_COMMAND, normalized_harness)
        for arm in ("baseline", "harness"):
            self.assertEqual(3, appendix_by_arm[arm].count(GRADLE_WRAPPER))
        self.assertNotIn("build-overlay.gradle.kts", appendix_by_arm["baseline"])
        self.assertIn("build-overlay.gradle.kts", appendix_by_arm["harness"])

    def test_preflight_commands_are_offline_and_differ_only_by_the_overlay(self):
        preflight = load_preflight()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            workspace = root / "benchmarks/agentic-palisade/template"
            run_dir = Path(directory) / "run"
            runtime = {"workspace": workspace, "runDir": run_dir}
            baseline = preflight.gradle_commands({
                "treatment": "baseline", "_runtime": runtime})
            harness = preflight.gradle_commands({
                "treatment": "harness", "_runtime": runtime})

        for phase in ("compile", "test", "launch"):
            self.assertIn("--offline", baseline[phase])
            self.assertIn("--offline", harness[phase])
            self.assertEqual(baseline[phase][0], harness[phase][0])
        for treatment in (baseline, harness):
            self.assertEqual(1, len(treatment["seed"]))
            self.assertTrue(all("--offline" not in command
                                for command in treatment["seed"]))
        self.assertNotIn("--init-script", baseline["compile"])
        self.assertIn("--init-script", harness["compile"])
        baseline_compile = baseline["compile"]
        harness_compile = list(harness["compile"])
        overlay = harness_compile.index("--init-script")
        del harness_compile[overlay:overlay + 2]
        self.assertEqual(baseline_compile, harness_compile)

    def test_rejects_design_hint_in_only_one_treatment(self):
        with tempfile.TemporaryDirectory() as directory:
            mutated = Path(directory) / "agentic-palisade"
            copy_benchmark(self.root, mutated)
            instructions = mutated / "treatments/harness/INSTRUCTIONS.md"
            instructions.write_bytes(
                instructions.read_bytes()
                + b"\nUse a Table with a hard-coded two-column control grid.\n"
            )
            with self.assertRaisesRegex(
                SymmetryError, "unapproved treatment appendix change in harness"
            ):
                validate_treatment_symmetry(mutated)

    def test_rejects_a_different_wrapper_path_in_one_treatment(self):
        with tempfile.TemporaryDirectory() as directory:
            mutated = Path(directory) / "agentic-palisade"
            copy_benchmark(self.root, mutated)
            instructions = mutated / "treatments/baseline/INSTRUCTIONS.md"
            instructions.write_text(
                instructions.read_text(encoding="utf-8").replace(
                    COMMON_BUILD_COMMAND,
                    COMMON_BUILD_COMMAND.replace("../../../gradlew", "../../gradlew")),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                    SymmetryError, "unapproved treatment appendix change in baseline"):
                validate_treatment_symmetry(mutated)

    def test_rejects_acceptance_assertion_in_only_one_treatment(self):
        with tempfile.TemporaryDirectory() as directory:
            mutated = Path(directory) / "agentic-palisade"
            copy_benchmark(self.root, mutated)
            instructions = mutated / "treatments/baseline/INSTRUCTIONS.md"
            content = instructions.read_bytes()
            content = content.replace(
                APPENDIX_MARKER,
                b"The hidden evaluator asserts an exact actor count.\n\n" + APPENDIX_MARKER,
            )
            instructions.write_bytes(content)
            with self.assertRaisesRegex(
                SymmetryError, "shared task wording changed in baseline"
            ):
                validate_treatment_symmetry(mutated)

    def test_rejects_an_extra_one_arm_instruction_file(self):
        with tempfile.TemporaryDirectory() as directory:
            mutated = Path(directory) / "agentic-palisade"
            copy_benchmark(self.root, mutated)
            (mutated / "treatments/harness/HINTS.txt").write_text(
                "Prefer a particular widget tree.", encoding="utf-8"
            )
            with self.assertRaisesRegex(SymmetryError, "unexpected treatment-only file"):
                validate_treatment_symmetry(mutated)


if __name__ == "__main__":
    unittest.main()
