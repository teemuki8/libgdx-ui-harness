# Issue #39 Scenario Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded application-registered scenario lifecycle that reaches and proves deterministic known state without granting MCP launch authority.

**Architecture:** `harness-core` defines immutable scenario identities, definitions, results, bounds, and host interfaces. `harness-scene2d` owns render-thread hook execution and completed-frame readiness. `harness-lwjgl3` supplies an optional allowlisted restart-profile coordinator. Protocol and MCP expose list/start by registered IDs only.

**Tech Stack:** Java 25 without preview APIs, Gradle Wrapper, JUnit 5, libGDX Scene2D/LWJGL3, Jackson, MCP Java SDK.

## Global Constraints

- No Actor, Stage, libGDX collection, or backend type crosses the adapter boundary.
- All Stage/Actor reads and mutations run on the render thread.
- Waiting uses an injected monotonic clock and completed frames; no sleeps.
- Public data is immutable, versioned, serializable, stable-ordered, and bounded.
- MCP accepts registered IDs and bounded configuration values only; never code, commands, paths, environment, classes, or launch arguments.
- Existing `Scene2dSession` use remains source-compatible and requires no scenario registry or coordinator.
- Branch from current `origin/main`; exclude local commit `1e91cbf`.

---

### Task 1: Core scenario contract and registry

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/scenario/ScenarioDefinition.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/scenario/ScenarioRegistry.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/scenario/ScenarioRequest.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/scenario/ScenarioResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/scenario/ScenarioFailure.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/scenario/ScenarioLifecycle.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/scenario/ScenarioRegistryTest.java`

**Interfaces:**
- Produces:
```java
public record ScenarioDefinition(
        int schemaVersion, String id, String definitionVersion,
        String applicationId, List<String> supportedProfileIds,
        int maxSetupAttempts, Duration maxDuration) {}

public interface ScenarioLifecycle {
    void setup(ScenarioRequest request);
    void reset(ScenarioRequest request);
    boolean ready(ScenarioRequest request);
    String startStateIdentity(ScenarioRequest request, SemanticSnapshot snapshot);
    void cleanup(ScenarioRequest request);
}

public record ScenarioRequest(
        String scenarioId, long seed, Map<String, String> configuration,
        String profileId, Deadline deadline) {}

public final class ScenarioRegistry {
    public void register(ScenarioDefinition definition, ScenarioLifecycle lifecycle);
    public List<ScenarioDefinition> definitions();
    public RegisteredScenario require(String id);
}
```

- [ ] **Step 1: Write failing bounded-registry tests** covering stable sorted listing, duplicate IDs, identifier/configuration/string/count limits, unknown IDs, and immutability.
- [ ] **Step 2: Run** `./gradlew :harness-core:test --tests '*ScenarioRegistryTest' --no-daemon --console=plain --warning-mode=fail`; expect compilation failure because scenario types do not exist.
- [ ] **Step 3: Implement the minimal records, closed failure enum, registry defensive copies, and explicit limits.** Do not add discovery, reflection, or launcher data.
- [ ] **Step 4: Re-run the focused test** and require PASS.
- [ ] **Step 5: Commit** `git add harness-core && git commit -m "Add bounded scenario registry contract"`.

### Task 2: Scene2D render-thread lifecycle runner

**Files:**
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java`
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunnerTest.java`
- Test fixture: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dTestSupport.java`

**Interfaces:**
- Consumes: `ScenarioRegistry`, `ScenarioLifecycle`, `RenderThreadScheduler`, `MonotonicClock`, and completed `SemanticSnapshot` frames.
- Produces:
```java
public CompletionStage<ScenarioResult> start(
        ScenarioRequest request, String applicationId, String processId,
        String sessionId);
```

- [ ] **Step 1: Write failing tests** proving setup/reset/cleanup run on the render thread, readiness needs a completed frame, monotonic deadline expiry is distinct from setup rejection, cancellation performs cleanup, and repeated inputs either return the same identity or `NONDETERMINISTIC_INITIAL_STATE`.
- [ ] **Step 2: Run** `./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest' --no-daemon --console=plain --warning-mode=fail`; expect FAIL for missing runner.
- [ ] **Step 3: Implement the runner** as an explicit state machine with one terminal result. Record definition/version, configuration digest, seed, application/process/session IDs, start/ready frames and revisions, elapsed duration, attempts, profile, and cleanup status.
- [ ] **Step 4: Re-run the focused test** and require PASS, including no `Thread.sleep` path.
- [ ] **Step 5: Commit** `git add harness-scene2d && git commit -m "Run scenarios on completed Scene2D frames"`.

### Task 3: Optional registered LWJGL3 restart profiles

**Files:**
- Create: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/LaunchProfile.java`
- Create: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/RegisteredLaunchCoordinator.java`
- Test: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/RegisteredLaunchCoordinatorTest.java`

**Interfaces:**
```java
public interface RegisteredLaunchCoordinator {
    CompletionStage<LaunchResult> restart(String registeredProfileId, Deadline deadline);
}
```

- [ ] **Step 1: Write failing tests** for known/unknown profile IDs, application compatibility, replacement process/session identities, deadline/cancellation, and rejection of caller-supplied command/path/environment fields at the public boundary.
- [ ] **Step 2: Run** `./gradlew :harness-lwjgl3:test --tests '*RegisteredLaunchCoordinatorTest' --no-daemon --console=plain --warning-mode=fail`; expect missing types.
- [ ] **Step 3: Implement an allowlisted coordinator interface and immutable profile/result data.** Host code owns commands internally; none appear in protocol-facing records.
- [ ] **Step 4: Re-run focused tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-lwjgl3 && git commit -m "Add registered LWJGL3 launch profiles"`.

### Task 4: Protocol, MCP, capability, and ADR surface

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
- Create: `docs/adr/0017-bounded-scenario-lifecycle.md`

**Interfaces:** Add closed `scenario-list` and `scenario-start` command/result variants and MCP tools `ui_scenarios` and `ui_scenario_start`. Requests contain scenario ID, seed, bounded configuration, registered profile ID, and explicit deadline only.

- [ ] **Step 1: Add failing JSON/schema/service tests** for exact round trips, unknown fields/variants, over-limit config, unknown/incompatible scenario, and absence of command/path/environment/class/launch-argument properties.
- [ ] **Step 2: Run** `./gradlew :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`; expect FAIL because operations are absent.
- [ ] **Step 3: Implement closed command/results, service routing, exact catalog schemas, handler decoding, examples, capability names, and ADR.** Keep the registry optional in session registration and return an explicit unavailable result when absent.
- [ ] **Step 4: Re-run protocol/MCP tests** and require PASS.
- [ ] **Step 5: Commit** `git add harness-protocol harness-mcp docs/adr/0017-bounded-scenario-lifecycle.md && git commit -m "Expose bounded scenario lifecycle"`.

### Task 5: Real fixture, full verification, PR, review, and merge

**Files:**
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiApplication.java`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ScenarioLifecycleFixtureTest.java`
- Modify: public examples only if the new optional registration API appears in existing examples.

- [ ] **Step 1: Add a failing real fixture** that resets mutable state, becomes ready after completed frames, repeats the same semantic start identity, rejects an incompatible scenario, times out readiness deterministically, cleans up cancellation, and exercises one restart-required registered display profile.
- [ ] **Step 2: Run** `./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`; expect FAIL before fixture wiring.
- [ ] **Step 3: Wire the fixture through the production registry/runner/coordinator only;** do not add fixture-only execution paths.
- [ ] **Step 4: Run focused fixture, then** `./gradlew :harness-core:test :harness-scene2d:test :harness-lwjgl3:test :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`.
- [ ] **Step 5: Commit fixture changes, push, and open a ready PR** with `Fixes #39` and exact command results.
- [ ] **Step 6: Review the remote PR head** against every #39 criterion, full patch, comments, checks, lifecycle terminal states, bounds, security, and compatibility.
- [ ] **Step 7: For each verified finding, reproduce test-first, fix, rerun affected/full gates, push, and re-review the new exact SHA.**
- [ ] **Step 8: Merge the reviewed green SHA, verify PR state `MERGED` and issue #39 `CLOSED`, then fetch `origin/main` for #31.**
