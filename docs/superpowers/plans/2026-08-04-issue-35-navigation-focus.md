# Issue #35 Navigation and Focus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Inspect and validate repeatable keyboard/controller focus paths from registered known states using real libGDX input dispatch.

**Architecture:** Core owns bounded navigation graph, step, path, status, and validation models. Scene2D executes inputs through `Scene2dInputDispatcher`, captures focus/modal state on completed frames, and resets through #39 scenarios. Protocol/MCP expose inspect and validate as closed operations.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, libGDX Scene2D/LWJGL3, Jackson, MCP Java SDK.

## Global Constraints

- Consume #39 for known-state reset; do not add another lifecycle mechanism.
- Use real configured input dispatch; never invoke listeners directly.
- Advance only through completed rendered frames under an injected monotonic deadline; no sleeps.
- Detect cycles by stable semantic identity and context, not snapshot node ID alone.
- Bound steps, duration, actors, cycles, strings, evidence, and bytes.
- Branch from merged #31 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Core navigation graph and validation vocabulary

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/navigation/NavigationInput.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/navigation/NavigationStep.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/navigation/NavigationPath.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/navigation/NavigationReason.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/navigation/NavigationRequest.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/navigation/NavigationResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/navigation/NavigationValidator.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/navigation/NavigationValidatorTest.java`

**Interfaces:**
```java
public enum NavigationReason {
    COMPLETE, CYCLE, DEAD_END, MODAL_ESCAPE, FOCUS_LOST,
    UNREACHABLE_CONTROL, UNSUPPORTED_CONTROLLER_PATH, DEADLINE, TRUNCATED
}
public record NavigationStep(
        NavigationInput input, long beforeFrame, long beforeRevision,
        long afterFrame, long afterRevision, String beforeIdentity,
        String afterIdentity, String modalBoundaryId) {}
```

- [ ] **Step 1: Write failing pure tests** for deterministic stable ordering, cycle/dead-end distinction, default focus, unreachable nodes, modal containment, and bounded truncation.
- [ ] **Step 2: Run focused core test** and expect missing navigation types.
- [ ] **Step 3: Implement immutable models and validation over supplied steps/known focusables.** Keep execution out of core.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Add navigation validation model"`.

### Task 2: Scene2D traversal through real input

**Files:**
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dNavigationRunner.java`
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dInputDispatcher.java`
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dNavigationRunnerTest.java`

**Interfaces:**
```java
public CompletionStage<NavigationResult> inspect(NavigationRequest request);
public CompletionStage<NavigationResult> validate(NavigationRequest request);
```

- [ ] **Step 1: Write failing render-thread fixture tests** for linear Tab/Shift+Tab, directional grid, observed default focus, cycle, dead end, focus loss, modal escape, Escape/Back, unsupported controller wiring, scenario reset repeatability, and deadline.
- [ ] **Step 2: Run focused Scene2D test** and expect missing runner.
- [ ] **Step 3: Implement bounded traversal**: start #39 scenario, dispatch one real input, await a completed frame, capture fresh semantic focus/modal state, append one step, stop at terminal reason, optionally reset before validation.
- [ ] **Step 4: Re-run focused tests** and require PASS with dispatch-spy proof that no listener is called directly.
- [ ] **Step 5: Commit** `git add harness-scene2d && git commit -m "Traverse Scene2D focus through real input"`.

### Task 3: Protocol/MCP operations and ADR

**Files:**
- Modify: protocol `Command.java`, `HarnessResponse.java`, `HarnessProtocolService.java`
- Modify: protocol contract/service tests
- Modify: MCP `HarnessToolCatalog.java`, `HarnessToolHandler.java`, catalog/server tests
- Create: `docs/adr/0019-deterministic-navigation-diagnostics.md`

**Interfaces:** Add `navigation-inspect` and `navigation-validate` command/results, MCP `ui_navigation_inspect` and `ui_navigation_validate`, explicit scenario/start focus, input modes, max steps, and deadline.

- [ ] **Step 1: Add failing closed-schema and service tests** for both operations, every reason code, unknown fields/variants, bounds, unsupported controller output, and exact capability discovery.
- [ ] **Step 2: Run protocol/MCP suites** and expect operation absence failures.
- [ ] **Step 3: Implement command/result mapping, routing, schemas, examples, capabilities, and ADR.** Keep before/after frame/revision and modal boundary mandatory per step.
- [ ] **Step 4: Re-run protocol/MCP suites** and require PASS.
- [ ] **Step 5: Commit** protocol/MCP/ADR changes with `git commit -m "Expose navigation diagnostics"`.

### Task 4: Real LWJGL3 navigation fixture and delivery

**Files:**
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/NavigationFixtureTest.java`
- Modify: `ReferenceUiApplication.java` with a registered navigation scenario.

- [ ] **Step 1: Add a failing real fixture** containing linear order, directional grid, modal, cycle, dead end, unreachable control, Escape/Back, and application-wired controller movement; execute each twice and compare semantic paths.
- [ ] **Step 2: Run the focused fixture** and expect failure before production wiring.
- [ ] **Step 3: Wire the fixture through scenario registry, real dispatcher, and public operations only.**
- [ ] **Step 4: Run fixture, then** `./gradlew :harness-core:test :harness-scene2d:test :harness-lwjgl3:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`.
- [ ] **Step 5: Commit, push, open ready PR `Fixes #35`, and record exact evidence.**
- [ ] **Step 6: Review remote head** for all acceptance criteria, input authenticity, reset, reasons, bounds, comments, and CI.
- [ ] **Step 7: Fix verified findings test-first; repeat gates and review on the new SHA.**
- [ ] **Step 8: Merge exact reviewed green SHA; verify #35 closed; fetch `origin/main`.**
