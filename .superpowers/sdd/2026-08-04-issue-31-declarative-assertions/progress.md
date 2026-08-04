# SDD ledger — plan: /home/tjaaskel/git/libgdx-ui-harness/.worktrees/remaining-issues-design/docs/superpowers/plans/2026-08-04-issue-31-declarative-assertions.md

1. Baseline: origin/main c9938fb; `./gradlew test --no-daemon --console=plain --warning-mode=fail` BUILD SUCCESSFUL (32 tasks).

2. Task 1 RED: `./gradlew :harness-core:test --tests '*AssertionEvaluatorTest' --no-daemon --console=plain --warning-mode=fail` — BUILD FAILED in `compileTestJava` because the assertion types/evaluator did not exist.
3. Task 1 GREEN: `./gradlew :harness-core:test --tests '*AssertionEvaluatorTest' --no-daemon --console=plain --warning-mode=fail` — BUILD SUCCESSFUL (3 actionable tasks).
4. Task 1 concern: `StableForFrames` is modeled and bounded here but intentionally rejected by the pure single-snapshot evaluator; completed-frame behavior belongs to Task 2.

5. Task 1 review fix RED: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEvaluatorTest` — BUILD FAILED; both zero-area target and zero-area other cases failed (10 tests completed, 2 failed).
6. Task 1 review fix GREEN: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEvaluatorTest` — BUILD SUCCESSFUL (3 actionable tasks).

7. Task 2 RED: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEngineTest` — BUILD FAILED in `compileTestJava` because `AssertionEngine` did not exist.
8. Task 2 GREEN: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEngineTest` — BUILD SUCCESSFUL (3 actionable tasks).
9. Task 2 concern: expiry is observed at registration or a completed-frame callback because the asynchronous API has no scheduler; frame registration rejection and signal closure complete the returned stage exceptionally.

10. Task 2 review fix RED: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEngineTest` — BUILD FAILED; `rejectsAPassWhenSnapshotEvaluationCrossesTheExactDeadline`, `stableForFramesIgnoresDistinctSignalsForTheSameCompletedSnapshot`, and `closureWaitsForAlreadyAcceptedFramesToDrain` failed (11 tests completed, 3 failed).
11. Task 2 review fix GREEN: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEngineTest` — BUILD SUCCESSFUL (3 actionable tasks).
12. Task 2 review fix concern: deadline expiry remains event-driven (registration/evaluation/frame/closure) because the API has no scheduler; stable snapshot identity is bounded to one revision/frame pair.