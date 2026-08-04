# Issue #32 Layout Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate full-stage or strict locator-subtree layout invariants from one completed-frame observation and return a deterministic bounded CI result.

**Architecture:** Core extends existing layout evidence with validator configuration, closed reason/severity findings, and one pure validation engine. Scene2D captures one immutable semantic/layout observation. Keyboard reachability consumes #35 results. Protocol/MCP add `ui_validate_layout` without duplicating `ui_layout_diagnose`.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, Scene2D, Jackson, MCP Java SDK.

## Global Constraints

- One immutable completed-frame observation per validation.
- Full-stage and subtree modes share one engine; subtree resolution stays strict and lazy.
- False-positive-prone checks are explicit opt-ins with reported thresholds.
- Keyboard reachability consumes #35 reason codes and returns unavailable when navigation evidence is absent.
- Findings/results are closed, stable-ordered, bounded, serializable, and expose truncation.
- Branch from merged #35 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Validation configuration, findings, and deterministic engine

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/layout/LayoutValidationConfig.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/layout/LayoutValidationReason.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/layout/LayoutFinding.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/layout/LayoutValidationResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/layout/LayoutValidator.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/layout/LayoutValidatorTest.java`

**Interfaces:**
```java
public enum LayoutValidationReason {
    OUTSIDE_VIEWPORT, CLIPPED_TEXT, INTERACTIVE_OVERLAP, ZERO_SIZE,
    BELOW_TARGET_SIZE, DUPLICATE_TEST_ID, MISSING_ACCESSIBLE_NAME,
    KEYBOARD_UNREACHABLE, OBSCURED, INVALID_CLIP_SCROLL,
    INCONSISTENT_ALIGNMENT, INCONSISTENT_SPACING, CHECK_UNAVAILABLE
}
public record LayoutValidationResult(
        Status status, List<LayoutFinding> findings,
        int examinedNodes, Truncation truncation) {}
```

- [ ] **Step 1: Write failing positive and negative tests for every reason code**, coordinate-space bounds, z-order, nested clip/scroll, duplicate IDs, stable ordering, severity gate, opt-in thresholds, node/result/depth/byte truncation, and navigation-unavailable behavior.
- [ ] **Step 2: Run** `./gradlew :harness-core:test --tests '*LayoutValidatorTest' --no-daemon --console=plain --warning-mode=fail`; expect missing validator types.
- [ ] **Step 3: Implement one allocation-conscious validation pass** over immutable semantic/layout inputs. Reuse `CoordinateBounds`, clip/layout observations, and #35 navigation output; do not invoke `LayoutEvaluator` once per actor.
- [ ] **Step 4: Re-run focused tests** and require PASS, including identical repeat output.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Validate whole-stage layout invariants"`.

### Task 2: Atomic Scene2D stage/subtree capture

**Files:**
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dLayoutValidator.java`
- Modify: `Scene2dSession.java`
- Modify if necessary: `Scene2dLayoutExtractor.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dLayoutValidatorTest.java`

**Interfaces:**
```java
public LayoutValidationResult validate(
        long revision, long frame, Locator subtree,
        LayoutValidationConfig config, NavigationResult navigation);
```

- [ ] **Step 1: Write failing tests** proving one render-thread capture, full-stage/subtree parity for the same nodes, strict zero/multiple subtree errors, nested clipping, z-order obscuration, and no Actor leakage.
- [ ] **Step 2: Run focused Scene2D test** and expect missing adapter.
- [ ] **Step 3: Implement atomic snapshot/layout extraction and pure-engine invocation.** Use `null`/explicit target mode for full stage rather than a synthetic wildcard locator.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-scene2d && git commit -m "Capture layout validation evidence atomically"`.

### Task 3: Public operation and ADR

**Files:**
- Modify protocol command/response/service and tests.
- Modify MCP catalog/handler and tests.
- Create: `docs/adr/0020-whole-stage-layout-validation.md`

**Interfaces:** Add `layout-validate` and MCP `ui_validate_layout`; request has target mode (`stage` or `subtree` with locator), closed enabled checks, thresholds, severity gate, max results/nodes/duration, and version. Result has CI `pass`/`fail`/`incomplete`, findings, applied config, counts, and truncation.

- [ ] **Step 1: Add failing schema tests** for stage/subtree exclusivity, all reason codes, threshold ranges, opt-in defaults, unknown fields, closed severity/status, response limits, and exact capabilities.
- [ ] **Step 2: Run protocol/MCP suites** and expect missing operation failures.
- [ ] **Step 3: Implement routing, models, schemas, mapping, capability/catalog, examples, and ADR.**
- [ ] **Step 4: Re-run protocol/MCP suites** and require PASS.
- [ ] **Step 5: Commit** with `git commit -m "Expose layout invariant validation"`.

### Task 4: Controlled-defect fixture and delivery

**Files:**
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/LayoutValidationFixtureTest.java`
- Modify: `ReferenceUiApplication.java` with one controlled defect per reason code.

- [ ] **Step 1: Add failing fixture assertions** for full-stage/subtree parity, every defect, fixed negative state, opt-ins, deterministic repeats, bounds/truncation, and a fail-then-pass CI gate.
- [ ] **Step 2: Run focused fixture** and expect failure before operation wiring.
- [ ] **Step 3: Wire only through public production validation.**
- [ ] **Step 4: Run fixture, then** `./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`.
- [ ] **Step 5: Commit, push, open ready PR `Fixes #32`.**
- [ ] **Step 6: Review remote head** for all checks, false positives, coordinate semantics, navigation reuse, comments, and exact-head CI.
- [ ] **Step 7: Fix every verified defect test-first; rerun and re-review.**
- [ ] **Step 8: Merge reviewed green SHA, verify #32 closed, fetch `origin/main`.**
