# Issue #31 Declarative Assertions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add closed declarative assertions across Java, protocol, and `ui_assert`, including completed-frame stability and bounded diagnostic evidence.

**Architecture:** A sealed core assertion union and evaluator reuse `LocatorEngine`, `StrictResolution`, `WaitEngine` timing primitives, immutable snapshots, and completed-frame signals. Protocol and MCP add one exact operation; Scene2D/LWJGL3 fixtures prove fresh re-resolution and frame behavior.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, Scene2D/LWJGL3, Jackson, MCP Java SDK.

## Global Constraints

- Actor assertions use lazy locators and re-resolve on every attempt; zero and multiple matches stay distinct.
- `hidden` never means missing; count is the only non-strict cardinality assertion.
- Stable state uses completed rendered frames and an injected monotonic deadline, never sleeps.
- Evidence and schemas are immutable, closed, versioned, deterministic, serializable, and bounded.
- Branch from merged #39 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Assertion union and pure evaluations

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/assertion/UiAssertion.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/assertion/AssertionRequest.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/assertion/AssertionResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/assertion/AssertionEvidence.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/assertion/AssertionEvaluator.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/assertion/AssertionEvaluatorTest.java`

**Interfaces:**
```java
public sealed interface UiAssertion permits Visible, Hidden, Enabled, Disabled,
        Focused, Checked, TextEquals, TextContains, CountEquals,
        BoundsInsideViewport, DoesNotOverlap, StableForFrames,
        AccessibleNameExists {}

public record AssertionRequest(
        int schemaVersion, Locator locator, UiAssertion assertion, Deadline deadline) {}

public record AssertionResult(
        Status status, AssertionEvidence evidence, long elapsedNanos) {}
```

- [ ] **Step 1: Write failing tests for every non-temporal variant**: pass/fail, missing vs hidden, multiple matches, count cardinality, viewport boundary, overlap boundary, null checked state, and accessible-name blankness.
- [ ] **Step 2: Run** `./gradlew :harness-core:test --tests '*AssertionEvaluatorTest' --no-daemon --console=plain --warning-mode=fail`; expect missing assertion types.
- [ ] **Step 3: Implement the sealed union and minimal evaluator** over one supplied `SemanticSnapshot`. Reuse strict resolution except `CountEquals`; do not copy locator logic.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Add declarative assertion evaluator"`.

### Task 2: Deadline retry and completed-frame stability

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/assertion/AssertionEngine.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/assertion/AssertionEngineTest.java`
- Modify only if reusable signal behavior is missing: `harness-core/src/main/java/dev/gdx/uiharness/core/wait/FrameSignal.java`

**Interfaces:**
```java
public CompletionStage<AssertionResult> assertThat(
        Supplier<SemanticSnapshot> snapshots,
        AssertionRequest request,
        FrameSignal frames,
        MonotonicClock clock);
```

- [ ] **Step 1: Write failing tests** proving fresh snapshot re-resolution per attempt, explicit elapsed time, exact deadline boundary, `StableForFrames` property-set comparison, reset on change, N completed frames, max-frame rejection, and no allocation of unbounded history.
- [ ] **Step 2: Run** the focused `AssertionEngineTest`; expect FAIL for missing engine.
- [ ] **Step 3: Implement retry/stability as a bounded state machine** retaining only the last compared evidence and count. Propagate strict zero/multiple failures with bounded candidates.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Evaluate assertions across rendered frames"`.

### Task 3: Protocol, MCP, capability, and ADR

**Files:**
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/Command.java`
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java`
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessProtocolService.java`
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java`
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/HarnessProtocolServiceTest.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`
- Create: `docs/adr/0018-declarative-ui-assertions.md`

**Interfaces:** Add `Command.Assert`, `HarnessResponse.Result.Assertion`, capability `ui_assert`, and a closed MCP `ui_assert` schema with an explicit assertion version/discriminator and deadline.

- [ ] **Step 1: Add failing golden/schema tests for all 13 variants**, exact required fields, unknown variant/field/version rejection, recursive locator closure, evidence limits/truncation, and exact capability/catalog advertisement.
- [ ] **Step 2: Run protocol/MCP suites** and expect FAIL because `ui_assert` is absent.
- [ ] **Step 3: Implement service routing and exact schema/handler/result mapping.** Evidence includes locator, assertion, expected, last observed/actionability, revision, frame, elapsed, candidates, truncation, and trace ID when present.
- [ ] **Step 4: Re-run protocol/MCP suites** and require PASS.
- [ ] **Step 5: Commit** `git add harness-protocol harness-mcp docs/adr/0018-declarative-ui-assertions.md && git commit -m "Expose declarative UI assertions"`.

### Task 4: Real rendered-frame fixture and delivery

**Files:**
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/DeclarativeAssertionFixtureTest.java`
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiApplication.java`

- [ ] **Step 1: Add a failing real LWJGL3 fixture** where an actor changes and is reconstructed across frames; assert stable-for-N, deadline failure, fresh lazy resolution, and bounded failure evidence through `ui_assert`.
- [ ] **Step 2: Run** `./gradlew :harness-fixtures:test --tests '*DeclarativeAssertionFixtureTest' --no-daemon --console=plain --warning-mode=fail`; expect FAIL before wiring.
- [ ] **Step 3: Wire production assertion engine access through the session/protocol path.** Do not add direct fixture shortcuts.
- [ ] **Step 4: Run focused tests, then** `./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail` and the real fixture.
- [ ] **Step 5: Commit, push, and open a ready PR with `Fixes #31`** and exact results.
- [ ] **Step 6: Review the remote PR head** against all variants, strictness, timing, evidence, schema, comments, and exact-head CI.
- [ ] **Step 7: Reproduce and fix every verified finding test-first; rerun, push, and re-review the new SHA.**
- [ ] **Step 8: Merge the reviewed green SHA, verify issue #31 closed, and fetch `origin/main`.**
