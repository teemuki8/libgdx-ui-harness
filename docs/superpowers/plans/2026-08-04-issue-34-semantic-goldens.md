# Issue #34 Semantic Goldens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compare versioned partial semantic baselines with fresh snapshots using stable hierarchy-aware identities, explicit tolerances, and deterministic bounded diffs.

**Architecture:** Core owns baseline schema, registered catalog, stable-key matcher, and pure comparator. The comparator never calls raster/capture code. Protocol/MCP expose `ui_semantic_compare` by registered baseline ID or bounded inline baseline according to the ADR, without arbitrary filesystem access.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, Jackson, MCP Java SDK.

## Global Constraints

- Never match by snapshot-local node ID or Actor identity.
- Duplicate/insufficient identities produce ambiguity, never heuristic pass.
- Partial nodes constrain supplied properties; strict-node mode is explicit.
- Tolerances name coordinate space and units and never hide semantic mismatches.
- Exclusions are allowlisted, bounded, reported, and cannot remove identity fields.
- No screenshot, framebuffer, or visual policy dependency.
- Branch from merged #33 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Versioned baseline and catalog

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaseline.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/BaselineNode.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaselineCatalog.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/PositionalTolerance.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticComparePolicy.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticBaselineTest.java`

**Interfaces:**
```java
public record SemanticBaseline(
        int majorVersion, int minorVersion, String id,
        BaselineNode root, boolean strictNodes) {}
public interface SemanticBaselineCatalog {
    SemanticBaseline require(String id);
}
```

- [ ] **Step 1: Write failing tests** for immutable bounded trees, unknown major rejection, explicit minor behavior, identity-field exclusion rejection, tolerance units/spaces, partial vs strict nodes, and catalog IDs without filesystem paths.
- [ ] **Step 2: Run focused core test** and expect missing baseline types.
- [ ] **Step 3: Implement closed baseline/policy records and an in-memory application-registered catalog.**
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Add versioned semantic baselines"`.

### Task 2: Stable-key matching and deterministic diff

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticDifference.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticCompareResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticComparator.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticComparatorTest.java`

**Interfaces:**
```java
public SemanticCompareResult compare(
        SemanticBaseline baseline, SemanticSnapshot current,
        SemanticComparePolicy policy);
```

- [ ] **Step 1: Write failing tests** for reconstructed actors/new node IDs, unique test-ID precedence, role+accessible-name+parent matching, added/removed/changed/ambiguous classification, before/after property paths, placement definition, exact tolerance boundary, exclusions, stable order, and all truncation limits.
- [ ] **Step 2: Run focused comparator test** and expect missing comparator.
- [ ] **Step 3: Implement hierarchy-aware one-to-one matching.** Record every applied key, omission, tolerance, exclusion, and ambiguity; never choose a best candidate among duplicates.
- [ ] **Step 4: Re-run focused tests twice** and require byte-equivalent ordered results.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Compare semantic snapshots deterministically"`.

### Task 3: `ui_semantic_compare`, ADR, fixture, and delivery

**Files:**
- Modify protocol command/response/service and tests.
- Modify MCP catalog/handler and tests.
- Create: `docs/adr/0022-versioned-semantic-baselines.md`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/SemanticGoldenFixtureTest.java`

**Interfaces:** Add closed `semantic-compare` and MCP `ui_semantic_compare`, accepting registered baseline ID, policy, optional strict-node override, limits, and deadline; return baseline/current identities, status, four diff groups, applied policies, and truncation.

- [ ] **Step 1: Add failing protocol/MCP tests** for unknown versions/fields, no arbitrary path, all diff variants, policy bounds, exact capability, and operation without capture service.
- [ ] **Step 2: Run protocol/MCP suites** and expect missing operation.
- [ ] **Step 3: Implement routing/schema/handler/capability/ADR.** Snapshot once at a completed frame; no capture or visual comparator call.
- [ ] **Step 4: Add and run a fixture** proving reconstruction/node-ID independence, all classifications, tolerance, exclusions, ambiguity, deterministic repeats, truncation, and no LWJGL/framebuffer dependency.
- [ ] **Step 5: Run** `./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`.
- [ ] **Step 6: Commit, push, open ready PR `Fixes #34`, then review the remote exact head.**
- [ ] **Step 7: Fix all verified findings test-first, rerun, push, and re-review.**
- [ ] **Step 8: Merge reviewed green SHA, verify #34 closed, fetch `origin/main`.**
