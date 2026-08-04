# Issue #36 Locator Suggestions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attach deterministic, redacted, bounded, schema-valid locator suggestions to strict zero/multiple failures without changing execution semantics.

**Architecture:** A core suggestion engine consumes only `ErrorEvidence`/candidate semantic data already retained by `StrictResolution`. Ranking prefers stable automation contracts and emits existing `Locator` objects. Protocol error mapping serializes suggestions and distinguishing properties; no new action behavior is introduced.

**Tech Stack:** Java 25, Gradle Wrapper, JUnit 5, Jackson, MCP Java SDK.

## Global Constraints

- Never scan beyond the immutable bounded failure evidence.
- Redact before ranking, message construction, trace recording, and serialization.
- Suggested locators use the existing sealed recursive locator schema.
- No retry, dispatch, or automatic fallback occurs after strict failure.
- Bound candidate scan, suggestions, distinctions, strings, depth, duration, and encoded bytes; report truncation.
- Branch from merged #34 `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Candidate evidence and suggestion engine

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/LocatorSuggestion.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/LocatorSuggestionPolicy.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/LocatorSuggestionEngine.java`
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/error/ErrorEvidence.java`
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/StrictResolution.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/locator/LocatorSuggestionEngineTest.java`
- Modify: `StrictResolutionTest.java`

**Interfaces:**
```java
public record LocatorSuggestion(
        Locator locator, Stability stability, String rationale,
        String candidateIdentity, List<DistinguishingProperty> distinctions) {}
public List<LocatorSuggestion> suggest(
        Locator failedLocator, List<CandidateEvidence> candidates,
        LocatorSuggestionPolicy policy);
```

- [ ] **Step 1: Write failing tests** for zero-match near alternatives, unique/duplicate test IDs, role+name, label, same-role multiple candidates, per-candidate uniqueness, fragile actor/type/index fallback marking, deterministic ties, limits/truncation, and resolution against the same snapshot.
- [ ] **Step 2: Run focused core tests** and expect missing suggestion types.
- [ ] **Step 3: Implement candidate-only ranking**: unique test ID; unique role+accessible name; unique label; weaker semantic fallbacks; fragile actor/type/index last. Re-query only the supplied immutable snapshot/evidence to prove advertised uniqueness.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Suggest strict locator alternatives"`.

### Task 2: Redaction before diagnostics

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/error/RedactionPolicy.java`
- Modify: `StrictResolution.java`, `ErrorEvidence.java`, and trace/error construction call sites.
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/locator/LocatorSuggestionRedactionTest.java`

**Interfaces:**
```java
public interface RedactionPolicy {
    String id();
    String redact(Field field, String value);
}
```

- [ ] **Step 1: Write failing tests** proving names, labels, text, distinctions, rationales, messages, and trace evidence are redacted before ranking/output while policy identity remains visible.
- [ ] **Step 2: Run focused redaction test** and expect failure because raw text leaks.
- [ ] **Step 3: Thread an explicit default no-redaction policy through strict resolution and apply it before candidate/suggestion construction.** Do not post-process serialized JSON.
- [ ] **Step 4: Re-run tests** and require PASS with raw secret absent from every rendered artifact.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Redact strict lookup diagnostics"`.

### Task 3: Protocol/MCP error evidence and delivery

**Files:**
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ProtocolError.java`
- Modify: `ProtocolJson.java` if explicit validation is needed.
- Modify protocol JSON/service tests.
- Modify MCP schema/server tests for error envelopes.

- [ ] **Step 1: Add failing round-trip/schema tests** for locator suggestion objects, stability, rationale, distinctions, redaction policy ID, truncation, recursive locator validation, unknown fields, and bounded evidence.
- [ ] **Step 2: Add a failing action test** proving zero/multiple failure emits zero input dispatches even when suggestions exist.
- [ ] **Step 3: Implement protocol mapping using existing locator polymorphism;** do not add a new locator grammar or operation.
- [ ] **Step 4: Run** `./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`.
- [ ] **Step 5: Commit, push, open ready PR `Fixes #36`, and review the remote exact head** for strictness, redaction, boundedness, schema, comments, and CI.
- [ ] **Step 6: Reproduce/fix every verified finding test-first; rerun, push, re-review.**
- [ ] **Step 7: Merge reviewed green SHA, verify #36 closed, fetch `origin/main`.**
