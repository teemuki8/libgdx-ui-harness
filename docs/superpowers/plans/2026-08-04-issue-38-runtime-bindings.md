# Issue #38 Runtime Bindings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit typed UI-to-runtime entity/property bindings, locator filters, and optional bounded same-frame displayed/runtime comparison.

**Architecture:** Core extends semantic nodes with typed binding records and entity/property locator filters. Scene2D stores bindings in the existing weak session-owned `Semantics` metadata and captures them atomically. An optional core observation SPI supplies bounded typed runtime values; protocol/MCP expose query filters and `ui_runtime_compare` without a mandatory runtime library.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, Scene2D, Jackson, MCP Java SDK.

## Global Constraints

- Bindings are explicit; never infer from labels, names, reflection, object identity, or parsing.
- No Actor, entity object, unrestricted reflection/invocation, or arbitrary runtime expression crosses the boundary.
- Existing applications work without a runtime provider or `libgdx-agent-runtime` dependency.
- Same-frame equality is claimed only when atomic correlation is proven; otherwise status is uncorrelated/stale.
- Bound IDs, paths, values, bindings per node, results, duration, evidence, and bytes; redact before output.
- Branch from merged #37 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Typed semantic bindings and locator filters

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/model/RuntimeBinding.java`
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/model/SemanticNode.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/EntityLocator.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/EntityPropertyLocator.java`
- Modify: `Locator.java`, `LocatorEngine` implementation, and locator tests.
- Modify: semantic snapshot tests.

**Interfaces:**
```java
public record RuntimeBinding(
        String entityId, String propertyId, String valueFormatId,
        String comparatorId, String correlationId) {}
public static Locator entity(String entityId);
public static Locator entityProperty(String entityId, String propertyId);
```

- [ ] **Step 1: Write failing tests** for entity-only/property bindings, strict zero/multiple matches, replacement fields, immutable bounds, typed snapshot serialization shape, and applications with no bindings.
- [ ] **Step 2: Run focused core model/locator tests** and expect missing binding/filter types.
- [ ] **Step 3: Implement additive typed node bindings and closed locator variants.** Keep existing constructors source-compatible through an overload/factory only if compilation proves necessary; do not leave deprecated aliases.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Add typed runtime bindings and locators"`.

### Task 2: Scene2D weak metadata lifecycle

**Files:**
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/ActorMetadata.java`
- Modify: `Semantics.java`, `SemanticNodeBuilder.java`, `Scene2dSnapshotter.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/RuntimeBindingMetadataTest.java`

**Interfaces:**
```java
public void bindEntity(Actor actor, String entityId);
public void bindProperty(Actor actor, String entityId, String propertyId,
        String valueFormatId, String comparatorId, String correlationId);
public void clearBindings(Actor actor);
```

- [ ] **Step 1: Write failing tests** for bind/replace/clear, entity-only/property output, per-node limits, session close, weak Actor collection, and render-thread snapshot capture.
- [ ] **Step 2: Run focused Scene2D test** and expect missing API.
- [ ] **Step 3: Extend existing `ActorMetadata` and `Semantics` weak map;** do not add a second ownership map or static facade.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-scene2d && git commit -m "Capture explicit Scene2D runtime bindings"`.

### Task 3: Optional runtime observation and comparison

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeObservationProvider.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeObservation.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeValue.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeCompareRequest.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeCompareResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeComparator.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/runtime/RuntimeComparatorTest.java`

**Interfaces:**
```java
public interface RuntimeObservationProvider {
    RuntimeObservation observe(String entityId, String propertyId);
}
public RuntimeCompareResult compare(
        SemanticSnapshot ui, SemanticNode node,
        RuntimeObservation observation, RuntimeCompareRequest request);
```

- [ ] **Step 1: Write failing tests** for typed match/mismatch, explicit formatter/normalizer/comparator IDs, no provider, missing entity/property, stale, frame mismatch, uncorrelated, unsupported type, ambiguity, exact same-frame, bounds, and redaction.
- [ ] **Step 2: Run focused runtime tests** and expect missing SPI/comparator.
- [ ] **Step 3: Implement optional read-only SPI and closed typed values.** No dependency on `libgdx-agent-runtime`; no arbitrary query string or reflection.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Compare bounded runtime observations"`.

### Task 4: Protocol/MCP surfaces, ADR, fixture, and delivery

**Files:**
- Modify protocol locator polymorphism, command/response/service, JSON/service tests.
- Modify MCP locator schema, catalog/handler, and tests.
- Create: `docs/adr/0024-explicit-runtime-bindings.md`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/RuntimeBindingFixtureTest.java`

**Interfaces:** `ui_query` accepts entity/entity-property locator variants. Add closed `runtime-compare` and MCP `ui_runtime_compare` accepting a strict locator/binding selector, registered comparator/normalizer identity, limits, and deadline; never arbitrary runtime expressions.

- [ ] **Step 1: Add failing schema/round-trip tests** for new locators and every comparison diagnostic, unknown fields/types, bounds/redaction, no provider, and exact capability/catalog.
- [ ] **Step 2: Add fixture tests** for bind/replace/clear/collection, no-runtime query, optional provider, typed match/mismatch, missing/stale/ambiguous, and exact vs mismatched frame correlation.
- [ ] **Step 3: Implement protocol/MCP routing/schema/handler/capability and ADR** after expected failures. Capture UI/runtime at one declared render-thread boundary when the provider supports it.
- [ ] **Step 4: Run** `./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail` and the fixture.
- [ ] **Step 5: Commit, push, open ready PR `Fixes #38`, and review remote exact head** for independence, security, correlation truthfulness, comments, and CI.
- [ ] **Step 6: Fix all verified findings test-first; rerun, push, and re-review.**
- [ ] **Step 7: Merge reviewed green SHA, verify #38 closed, fetch `origin/main`.**
