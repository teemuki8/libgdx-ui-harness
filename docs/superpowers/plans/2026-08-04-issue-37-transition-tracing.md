# Issue #37 Transition Tracing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Query compact semantic state-transition summaries from active or completed bounded traces without downloading the trace archive.

**Architecture:** Core adds bounded semantic observations to trace retention and projects adjacent correlated observations into closed transition records. Queries filter by retained trace ID, lazy locator, allowlisted kind/property, and inclusive frame range. Protocol/MCP expose `ui_trace_query`; opaque archive behavior remains unchanged.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, Scene2D/LWJGL3, Jackson, MCP Java SDK.

## Global Constraints

- Summaries project retained evidence; incomplete correlation never becomes claimed causality.
- Full trace artifacts remain opaque and paths never cross protocol/MCP.
- Stable actor identity is semantic/hierarchy-aware, not snapshot node ID alone.
- Bound retained observations, transitions, actors, filters, frames, strings, evidence, duration, and response bytes.
- Explicitly report gaps, unknown cause, identity ambiguity, and truncation.
- Branch from merged #36 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Transition record and bounded projector

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/StateTransition.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TransitionKind.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TransitionQuery.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TransitionQueryResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TransitionProjector.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/trace/TransitionProjectorTest.java`

**Interfaces:**
```java
public enum TransitionKind {
    APPEARED, DISAPPEARED, ENABLED, DISABLED, TEXT_CHANGED,
    BOUNDS_CHANGED, FOCUS_CHANGED, MODAL_CHANGED, OBSCURATION_CHANGED,
    Z_ORDER_CHANGED, IDENTITY_AMBIGUOUS
}
public TransitionQueryResult query(
        RetainedTrace trace, TransitionQuery query, LocatorEngine locators);
```

- [ ] **Step 1: Write failing tests** for each transition kind, before/after values and paths, frame/revision, action/input correlation, layout-pass attribution only with evidence, unknown cause, gaps, ambiguity, locator/property/kind/frame filters, inclusive boundaries, stable order, and truncation.
- [ ] **Step 2: Run focused core test** and expect missing projector.
- [ ] **Step 3: Implement projection over bounded retained semantic observations/events.** Store only fields needed for comparison and opaque evidence references; do not reopen arbitrary files.
- [ ] **Step 4: Re-run focused tests twice** and require identical results.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Project compact UI state transitions"`.

### Task 2: Trace recorder integration

**Files:**
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TraceRecorder.java`
- Modify: `TraceEvent.java`, `TraceManifest.java`, `TraceReplay.java` only for versioned compatible semantic observation events.
- Modify: core trace recorder/replayer tests.
- Modify: Scene2D action/snapshot tracing call sites.

- [ ] **Step 1: Write failing compatibility tests** proving existing start/stop archives still replay, active and completed traces can query retained observations, limits create explicit gaps, and secret/backend objects are absent.
- [ ] **Step 2: Run trace tests** and expect missing semantic retention/query behavior.
- [ ] **Step 3: Add a versioned bounded semantic observation event/projection store** correlated to existing input/action/layout boundaries. Preserve old event decoding.
- [ ] **Step 4: Re-run trace tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core harness-scene2d && git commit -m "Retain queryable semantic trace evidence"`.

### Task 3: `ui_trace_query`, ADR, real fixture, and delivery

**Files:**
- Modify protocol command/response/service and tests.
- Modify MCP catalog/handler and tests.
- Create: `docs/adr/0023-compact-transition-query.md`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/TransitionTraceFixtureTest.java`

**Interfaces:** Add `trace-query` and MCP `ui_trace_query` for active/completed trace ID, optional locator, allowlisted kinds/properties, inclusive frame range, limits, and deadline.

- [ ] **Step 1: Add failing closed-schema/service tests** for every filter/reason/status, active/completed identity, bounds, unknown fields, no filesystem path, exact capability, and response without archive retrieval.
- [ ] **Step 2: Implement operation routing/schema/capability/ADR** after observing the expected failures.
- [ ] **Step 3: Add a failing real LWJGL3 fixture** where dispatched input causes appearance, enabled/text/layout/focus/overlay changes; verify correlation, filters, unknown cause, gaps, truncation, and no archive read.
- [ ] **Step 4: Wire through production trace events/query only and run fixture.**
- [ ] **Step 5: Run** `./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail` plus the real fixture.
- [ ] **Step 6: Commit, push, open ready PR `Fixes #37`, and review remote exact head.**
- [ ] **Step 7: Fix all verified findings test-first; repeat affected/full gates and review.**
- [ ] **Step 8: Merge reviewed green SHA, verify #37 closed, fetch `origin/main`.**
