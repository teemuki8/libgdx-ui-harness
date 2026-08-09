#!/usr/bin/env python3
"""Repository contract tests for exact runtime and markup compatibility lanes."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class EcosystemProfileContractTest(unittest.TestCase):
    def test_published_floor_and_current_fixture_are_exact(self):
        catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
        self.assertIn('agent-runtime = "1.0.0"', catalog)
        self.assertIn('markup = "0.4.1"', catalog)
        for forbidden in ("latest.release", "latest.integration", "+\""):
            self.assertNotIn(forbidden, catalog)

    def test_current_profile_and_tasks_are_declared(self):
        build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('"minimum" to "1.0.0"', build)
        self.assertIn('"current" to "2.0.0"', build)
        self.assertIn('tasks.register<GradleBuild>("minimumEcosystemTest")', build)
        self.assertIn('tasks.register<GradleBuild>("currentEcosystemTest")', build)

    def test_profile_lock_and_published_pom_floor_are_separate(self):
        current_lock = ROOT / "harness-agent-runtime/gradle-current.lockfile"
        self.assertTrue(current_lock.is_file())
        self.assertIn(
            "io.github.teemuki8:agent-runtime-core:2.0.0=",
            current_lock.read_text(encoding="utf-8"),
        )
        publication = (ROOT / "harness-agent-runtime/build.gradle.kts").read_text(
            encoding="utf-8")
        self.assertIn("api(libs.agent.runtime.core)", publication)

    def test_ci_and_release_run_both_lanes(self):
        for path in (".github/workflows/ci.yml", ".github/workflows/release.yml"):
            workflow = (ROOT / path).read_text(encoding="utf-8")
            self.assertIn("minimumEcosystemTest", workflow)
            self.assertIn("currentEcosystemTest", workflow)


if __name__ == "__main__":
    unittest.main()
