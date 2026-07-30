#!/usr/bin/env python3
"""Fail-closed treatment-input symmetry validator and mutation probes."""

import hashlib
import shutil
import tempfile
import unittest
from pathlib import Path

APPENDIX_MARKER = b"## Treatment appendix\n"
IGNORED_DIRECTORIES = {".gradle", "build", "__pycache__"}
EXPECTED_SHARED_HASHES = {
    "PROTOCOL.md": "d9113cfd7d8118a1d268f9a2655da1d7f647951f1d0ac898045f2c88161465e4",
    "template": "00fe739b45b4ad097c7c40362dc5e8cb0ddf00bb179a0e8ec7a396b4930d02fb",
    "corpus": "d9d973fc53ba753fc665d64d299647444f51d26d42f87d21f14a162ac680065b",
}
EXPECTED_COMMON_HASH = (
    "5806cc84d1e85a10dd21413a92e679ba60de24b08d9f0736b62b99895ae74da4"
)
EXPECTED_APPENDIX_HASHES = {
    "baseline": "6addeb7eda9150d3ee901062f34cab7b72e09feeb7a011e15b44451603f99681",
    "harness": "0827621ed1d37d5ca71754453c9991c845077f33513170766a988c40a7ed30bd",
}
QUALIFIED_COORDINATES = {
    b'io.github.teemuki8:harness-lwjgl3:$harnessVersion',
    b'io.github.teemuki8:harness-mcp:$harnessVersion',
}


class SymmetryError(AssertionError):
    pass


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
    for arm in ("baseline", "harness"):
        common, appendix = split_instructions(
            root / "treatments" / arm / "INSTRUCTIONS.md"
        )
        shared_by_arm[arm] = common
        if sha256(common) != EXPECTED_COMMON_HASH:
            raise SymmetryError(f"shared task wording changed in {arm}")
        if sha256(appendix) != EXPECTED_APPENDIX_HASHES[arm]:
            raise SymmetryError(f"unapproved treatment appendix change in {arm}")
    if shared_by_arm["baseline"] != shared_by_arm["harness"]:
        raise SymmetryError("shared task wording differs between treatments")

    overlay = (root / "treatments/harness/build-overlay.gradle.kts").read_bytes()
    for coordinate in QUALIFIED_COORDINATES:
        if overlay.count(coordinate) != 1:
            raise SymmetryError(
                f"generated overlay must contain {coordinate.decode()} exactly once"
            )
    for marker in (
            b"candidate-version.txt", b"candidate-maven",
            b"qualifiedHarnessCandidate"):
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
