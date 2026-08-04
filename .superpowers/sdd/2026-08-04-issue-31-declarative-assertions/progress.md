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
13. Task 2 deadline evidence RED: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEngineTest --no-daemon --console=plain --warning-mode=fail` — BUILD FAILED; `preservesInitialFailedEvidenceWhenEvaluationCrossesTheExactDeadline` and `replacesPriorResolutionFailureWithResolvedEvidenceWhenEvaluationCrossesDeadline` failed (13 tests completed, 2 failed).
14. Task 2 deadline evidence GREEN: `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEngineTest --no-daemon --console=plain --warning-mode=fail` — BUILD SUCCESSFUL (3 actionable tasks).
15. Task 3 RED (protocol): `./gradlew :harness-protocol:test --tests dev.gdx.uiharness.protocol.ProtocolJsonContractTest --no-daemon --console=plain --warning-mode=fail` — BUILD FAILED in `compileTestJava` because `Command.Assert` and `HarnessResponse.Result.Assertion` did not exist.
16. Task 3 RED (MCP): `./gradlew :harness-mcp:test --tests dev.gdx.uiharness.mcp.HarnessToolCatalogTest --no-daemon --console=plain --warning-mode=fail` — BUILD FAILED because `ui_assert` was absent from the exact catalog and schema.
17. Task 3 GREEN: `./gradlew :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail` — BUILD SUCCESSFUL (12 actionable tasks).
18. Task 3 concern: condition mismatches return the closed assertion result with empty candidates; strict zero-match and multiple-match failures intentionally remain distinct typed protocol error envelopes, which already retain bounded candidates and trace evidence.
19. Task 3 concern: production routing reuses `WaitEngine`'s immutable snapshot supplier, locator engine, frame signal, and monotonic clock; deadline completion therefore retains the Task 2 event-driven behavior rather than introducing a scheduler or sleeps.
20. Task 3 review defects RED (schema): `./gradlew :harness-mcp:test --tests dev.gdx.uiharness.mcp.HarnessToolCatalogTest.assertionOutputAcceptsEmptySetLevelNodeIdForCountEvidence` — BUILD FAILED because `nodeId=""` violated `minLength: 1`.
21. Task 3 review defects RED (deadline/cleanup): `./gradlew :harness-core:test --tests dev.gdx.uiharness.core.assertion.AssertionEngineTest` — BUILD FAILED in `compileTestJava` because the injected deadline wake-up API did not exist.
22. Task 3 review defects GREEN: `./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.assertion.*' --tests 'dev.gdx.uiharness.core.wait.*' :harness-protocol:test :harness-mcp:test` — BUILD SUCCESSFUL (14 actionable tasks).
23. Task 3 review defects concern: legacy `WaitEngine` construction remains source-compatible for wait-only consumers but rejects `assertThat`; every production session advertising `ui_assert` injects its owned scheduled executor.