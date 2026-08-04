# Issue #33 Display Matrix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run one registered scenario and assertion set across a bounded display/locale/font matrix with exact provenance and compact terminal results.

**Architecture:** Core owns matrix expansion, limits, case/result/grouping models, cancellation semantics, and compact export. LWJGL3 coordinates in-process or registered restart profiles through #39, while assertions reuse #31. Protocol/MCP expose `ui_matrix_run` and `ui_matrix_results`; large captures remain opaque artifacts.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, libGDX LWJGL3, Jackson, MCP Java SDK.

## Global Constraints

- Every case starts through #39 and proves state isolation.
- Assertions are #31 requests evaluated once per case; no second assertion engine.
- Width/height are authoritative; aspect ratio is derived or a named tolerance constraint.
- UI scale, DPR, and HiDPI mode remain distinct requested and observed fields.
- Preflight bounds the Cartesian product before any case starts.
- Started/unstarted/cancelled cases and artifact provenance remain explicit, terminal, bounded, and stable-ordered.
- Branch from merged #32 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Matrix definition, expansion, limits, and report model

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixDefinition.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixCase.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixLimits.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixCaseResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixReport.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixPlanner.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/matrix/MatrixPlannerTest.java`

**Interfaces:**
```java
public record MatrixDefinition(
        int schemaVersion, String scenarioId,
        List<WindowSize> windows, List<Double> uiScales,
        List<Double> devicePixelRatios, List<HiDpiMode> hiDpiModes,
        List<String> locales, List<String> fontSetIds,
        List<AssertionRequest> assertions) {}
public List<MatrixCase> plan(MatrixDefinition definition, MatrixLimits limits);
```

- [ ] **Step 1: Write failing tests** for deterministic Cartesian order, exact product-limit rejection before execution, duplicate dimensions, contradictory geometry rejection, derived aspect ratio, each independent scale field, immutable copies, and report grouping by case/assertion.
- [ ] **Step 2: Run focused core test** and expect missing matrix types.
- [ ] **Step 3: Implement overflow-safe product calculation and immutable models.** No backend or process data enters the planner.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Plan bounded display matrices"`.

### Task 2: LWJGL3 case execution and artifact provenance

**Files:**
- Create: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunner.java`
- Create: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/MatrixResultStore.java`
- Test: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunnerTest.java`

**Interfaces:**
```java
public CompletionStage<String> run(MatrixDefinition definition, Deadline deadline);
public MatrixReport results(String runId);
```

- [ ] **Step 1: Write failing tests** for scenario start per case, no state leakage, in-process vs registered restart profile selection, requested/observed window/viewport/framebuffer/scale/HiDPI/locale/font fields, assertion fan-out, screenshot provenance, retry bounds, cancellation terminal states, unstarted cases, and retained-result bounds.
- [ ] **Step 2: Run focused LWJGL3 test** and expect missing runner.
- [ ] **Step 3: Implement sequential bounded execution first.** Parallel case execution is out of scope; deterministic isolation is more important. Bind each artifact reference to run/case/scenario/application/session/process/frame/revision and exact parameters.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-lwjgl3 && git commit -m "Run deterministic LWJGL3 display matrices"`.

### Task 3: Protocol/MCP lifecycle and ADR

**Files:**
- Modify protocol command/response/service and JSON/service tests.
- Modify MCP catalog/handler and catalog/server tests.
- Create: `docs/adr/0021-display-matrix-lifecycle.md`

**Interfaces:** Add `matrix-run` returning `runId`, `matrix-results` returning compact `MatrixReport`, MCP `ui_matrix_run` and `ui_matrix_results`. Results never embed PNG bytes.

- [ ] **Step 1: Add failing schema tests** for all dimensions, exact scale separation, registered IDs only, product/runtime/pixel/PNG/result/report bounds, cancellation/truncation states, unknown fields/versions, and exact capabilities.
- [ ] **Step 2: Run protocol/MCP suites** and expect missing operations.
- [ ] **Step 3: Implement closed routing, schema, compact result export, capability/catalog, examples, and ADR.**
- [ ] **Step 4: Re-run protocol/MCP suites** and require PASS.
- [ ] **Step 5: Commit** with `git commit -m "Expose bounded display matrix runs"`.

### Task 4: Real 2×2×2 matrix fixture and delivery

**Files:**
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/DisplayMatrixFixtureTest.java`
- Modify: `ReferenceUiApplication.java` to register deterministic locale/display scenarios and font sets.

- [ ] **Step 1: Add a failing real fixture** with at least two resolutions, two UI-scale/DPR combinations, and two locales; prove reset, assertion fan-out, exact artifacts, grouped failure, deterministic compact export, preflight rejection, cancellation/deadline, and no leakage.
- [ ] **Step 2: Run focused fixture** and expect failure before runner wiring.
- [ ] **Step 3: Wire through #39 scenarios, #31 assertions, capture artifacts, and public operations only.**
- [ ] **Step 4: Run fixture, then** `./gradlew :harness-core:test :harness-scene2d:test :harness-lwjgl3:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`.
- [ ] **Step 5: Commit, push, open ready PR `Fixes #33`.**
- [ ] **Step 6: Review remote head** for every acceptance criterion, isolation, product arithmetic, provenance, comments, and exact-head CI.
- [ ] **Step 7: Fix verified findings test-first; repeat all affected evidence and review.**
- [ ] **Step 8: Merge reviewed green SHA, verify #33 closed, fetch `origin/main`.**
