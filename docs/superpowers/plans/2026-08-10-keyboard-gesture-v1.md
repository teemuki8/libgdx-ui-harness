# Keyboard Gesture V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one bounded atomic `ui_keyboard_gesture` operation that holds real libGDX keyboard input across exact completed frames or optional controlled simulation ticks, always releases gesture-owned keys, and returns immutable correlated evidence.

**Architecture:** Backend-neutral request, result, validation, and exact-tick contracts live in `harness-core`. A per-session Scene2D state machine serializes one gesture, dispatches every key callback and cleanup release through the configured `InputProcessor` on the render thread, observes `FrameSignal` without advancing the application loop, and delegates exact tick waits to an optional coordinator. `harness-agent-runtime` adapts released agent-runtime 1.0.0 control operations, while protocol and MCP add a closed command/result and one mutating tool without locator semantics.

**Tech Stack:** Java 25 without preview APIs, Gradle Wrapper 9.6.1, libGDX 1.14.2, agent-runtime-core 1.0.0, Jackson, MCP Java SDK, JUnit 5, LWJGL3, Xvfb.

## Global Constraints

- Preserve the approved behavior in `docs/superpowers/specs/2026-08-10-keyboard-gesture-v1-design.md`.
- Preserve existing `press` behavior, including printable-key `keyTyped` synthesis; gesture transitions invoke only `keyDown` and `keyUp`.
- Never mutate `Gdx.input`, invoke Scene2D listeners directly, advance `Stage.act`, render a frame, approximate ticks with frames, or accept wall-clock wait steps.
- Keep `Actor`, `Stage`, `InputProcessor`, libGDX collections, and agent-runtime types out of core and protocol models.
- Perform complete structural validation and exact-tick preflight before the first input callback.
- Bound steps to 2..64, keycodes to 0..255, simultaneous held keys to 16, and individual and cumulative frame/tick waits to 10,000.
- Hold the MCP per-session mutation lane until cancellation/failure cleanup is terminal; transport cancellation may discard the response but must not release the lane early.
- Use a fresh 1,000 ms monotonic cleanup deadline, preserve the primary failure, and record every reverse-order release attempt.
- Treat runtime tick identity as `(executionEpochId, tick)` and never claim that agent-runtime 1.0.0 acknowledged the configured executed delta.
- Add no pointer, gamepad, analog, gameplay-world, reflection, filesystem, command, script, or network capability in this slice.
- Start every production behavior with a focused failing behavioral test and record the RED failure before implementation.

---

### Task 1: Add immutable core gesture and tick contracts

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/gesture/KeyboardGestureRequest.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/gesture/KeyboardGestureResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/gesture/ExactTickCoordinator.java`
- Create: `harness-core/src/test/java/dev/gdx/uiharness/core/gesture/KeyboardGestureRequestTest.java`
- Create: `harness-core/src/test/java/dev/gdx/uiharness/core/gesture/KeyboardGestureResultTest.java`

**Interfaces:**

```java
public record KeyboardGestureRequest(int schemaVersion, List<Step> steps) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_STEPS = 64;
    public static final int MAX_KEYCODE = 255;
    public static final int MAX_HELD_KEYS = 16;
    public static final int MAX_WAIT = 10_000;

    public sealed interface Step permits KeyDown, WaitFrames, WaitTicks, KeyUp {}
    public record KeyDown(int keycode) implements Step {}
    public record WaitFrames(int count) implements Step {}
    public record WaitTicks(int count) implements Step {}
    public record KeyUp(int keycode) implements Step {}
}
```

The compact constructor must defensively copy the list and simulate it in order with a
`LinkedHashSet<Integer>`. It rejects duplicate down, unmatched up, a wait with no held key, no key
transition, more than 16 held keys, a non-empty terminal held set, and individual/cumulative
bounds using `Math.addExact`.

```java
public interface ExactTickCoordinator {
    TickPreflight preflight(int ticks, Deadline deadline);
    CompletionStage<TickAdvanceResult> advance(int ticks, Deadline deadline);

    sealed interface TickPreflight permits TickPreflight.Ready, TickPreflight.Rejected {
        record Ready(int maximumTicks) implements TickPreflight {}
        record Rejected(TickFailure failure) implements TickPreflight {}
    }

    sealed interface TickAdvanceResult
            permits TickAdvanceResult.Completed, TickAdvanceResult.Failed {
        record Completed(TickEvidence evidence) implements TickAdvanceResult {}
        record Failed(TickFailure failure) implements TickAdvanceResult {}
    }

    record TickEvidence(
            int requestedTicks,
            int completedTicks,
            long startTick,
            long finalTick,
            long executionEpoch,
            OptionalLong firstRuntimeFrame,
            OptionalLong finalRuntimeFrame,
            OptionalLong firstUiFrame,
            OptionalLong finalUiFrame,
            long configuredDeltaNanos) {}

    record TickFailure(TickFailureCategory category, Map<String, String> evidence) {}

    enum TickFailureCategory {
        UNSUPPORTED_CAPABILITY,
        INVALID_STATE,
        LIMIT_EXCEEDED,
        TIMED_OUT,
        CALLBACK_FAILED,
        EPOCH_CHANGED,
        CANCELLED,
        INTERNAL_FAILURE
    }
}
```

`TickFailure` evidence is capped at 16 entries with keys and values of at most 512 characters and
never includes an arbitrary throwable message.

`KeyboardGestureResult` must expose these closed nested types and validate every list/bound:

```java
public record KeyboardGestureResult(
        int schemaVersion,
        TerminalOutcome outcome,
        int requestedSteps,
        int startedSteps,
        int completedSteps,
        long startRevision,
        long startFrame,
        long endRevision,
        long endFrame,
        long elapsedNanos,
        List<StepEvidence> steps,
        OptionalInt failureStep,
        Optional<FailureCategory> failure,
        List<Integer> heldKeys,
        CleanupStatus cleanupStatus,
        List<CleanupAttempt> cleanup,
        Optional<String> traceId) {
    public record StepEvidence(
            int index,
            StepKind kind,
            StepStatus status,
            OptionalInt keycode,
            OptionalInt count,
            long beforeRevision,
            long beforeFrame,
            long afterRevision,
            long afterFrame,
            List<Integer> heldKeys,
            Optional<ExactTickCoordinator.TickEvidence> tick) {}

    public record CleanupAttempt(int keycode, CleanupAttemptStatus status) {}
}
```

`StepKind` has `KEY_DOWN`, `WAIT_FRAMES`, `WAIT_TICKS`, and `KEY_UP`; `StepStatus` has
`COMPLETED` and `FAILED`; and `CleanupAttemptStatus` has `RELEASED`, `DISPATCH_FAILED`,
`DEADLINE_EXCEEDED`, and `SCHEDULER_REJECTED`. Use `TerminalOutcome` values `COMPLETED`,
`REJECTED`, `FAILED`, `TIMED_OUT`, `CANCELLED`, and
`SESSION_CLOSED`; `FailureCategory` values `INVALID_REQUEST`, `UNSUPPORTED_TICK_CAPABILITY`,
`INVALID_RUNTIME_STATE`, `SESSION_BUSY`, `KEY_DISPATCH_FAILURE`, `FRAME_SOURCE_CLOSED`,
`TICK_ADVANCE_FAILURE`, `EPOCH_CHANGED`, `TIMEOUT`, `CANCELLED`, `SESSION_CLOSED`, and
`CLEANUP_FAILURE`; and cleanup statuses `NOT_REQUIRED`, `COMPLETED`, and `FAILED`.

- [ ] **Step 1: Write request validation tests**

Cover the four valid step types, a balanced modifier chord, frame and tick cumulative boundaries,
defensive copies, and every invalid sequence/bound listed above. Assert each rejected constructor
throws before any execution dependency exists.

- [ ] **Step 2: Run the request tests for RED**

Run:

```bash
./gradlew :harness-core:test \
  --tests '*KeyboardGestureRequestTest' --warning-mode=fail
```

Expected: test compilation fails because `KeyboardGestureRequest` does not exist.

- [ ] **Step 3: Implement `KeyboardGestureRequest` minimally**

Add only the closed records, constants, defensive copies, and overflow-safe simulation required by
the tests. Do not add backend, serialization, or execution behavior.

- [ ] **Step 4: Run the request tests for GREEN**

Run the command from Step 2. Expected: all request tests pass.

- [ ] **Step 5: Write result and tick-contract tests**

Test exact upper bounds of 64 step evidence entries and 16 cleanup attempts, immutable nested
collections, optional tick correlation, completed-result invariants, failure-step consistency, and
the rule that any failed cleanup cannot coexist with `COMPLETED`.

- [ ] **Step 6: Run the result tests for RED**

Run:

```bash
./gradlew :harness-core:test \
  --tests '*KeyboardGestureResultTest' --warning-mode=fail
```

Expected: test compilation fails because the result and tick contracts do not exist.

- [ ] **Step 7: Implement result and tick contracts minimally**

Use records, sealed interfaces, `OptionalLong`-equivalent nullable-free value objects where
appropriate, and closed enums. Normalize no arbitrary exception text into the models.

- [ ] **Step 8: Run the core gesture tests for GREEN**

Run:

```bash
./gradlew :harness-core:test \
  --tests '*KeyboardGesture*Test' --warning-mode=fail
```

Expected: all gesture model tests pass with no warnings.

- [ ] **Step 9: Commit**

Commit message: `feat(core): define bounded keyboard gestures`

### Task 2: Execute successful key and frame timelines on the render thread

**Files:**
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dKeyboardGestureRunner.java`
- Create: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dKeyboardGestureRunnerTest.java`
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dInputDispatcher.java`
- Modify: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dInputDispatcherTest.java`

**Interfaces:**

```java
public final class Scene2dKeyboardGestureRunner implements AutoCloseable {
    public Scene2dKeyboardGestureRunner(
            String sessionId,
            InputProcessor input,
            RenderThreadScheduler scheduler,
            FrameSignal frames,
            LongSupplier revisions,
            LongSupplier frameNumbers,
            DeadlineScheduler deadlines,
            Optional<ExactTickCoordinator> ticks,
            Consumer<TraceEvent> traceSink);

    public CompletionStage<KeyboardGestureResult> execute(
            String requestId, KeyboardGestureRequest request, Deadline deadline);
}
```

Add package-private `keyDown(int)` and `keyUp(int)` methods to `Scene2dInputDispatcher`, each
calling only the configured `InputProcessor`. The runner owns an insertion-ordered held-key set,
subscribes to completed `FrameSignal.Frame` events only for an active `wait-frames`, and submits
the next transition to `RenderThreadScheduler`; frame callbacks never call input directly.

- [ ] **Step 1: Write dispatcher transition tests**

Assert direct transition methods call the configured processor in exact order and never call
`keyTyped`. Preserve the existing `press` test proving it still synthesizes its current typed
character.

- [ ] **Step 2: Run dispatcher tests for RED**

Run:

```bash
./gradlew :harness-scene2d:test \
  --tests '*Scene2dInputDispatcherTest' --warning-mode=fail
```

Expected: new tests fail because the transition methods are absent.

- [ ] **Step 3: Add the minimal dispatcher methods and verify GREEN**

Run the Step 2 command. Expected: direct transitions and existing action dispatch tests pass.

- [ ] **Step 4: Write runner happy-path tests**

Use a recording `InputProcessor`, manual `FrameSignal`, controlled monotonic clock, and real
`RenderThreadScheduler`. Prove:

- `down(A), wait-frames(30), up(A)` emits one down, no up during the first 29 distinct published
  completed frames, and one up after the 30th;
- duplicate publication of one frame identity is not counted twice;
- revision-only changes do not count as frames;
- modifier and ordinary-key downs and successful ups preserve request order;
- all input callbacks run on the scheduler owner thread;
- a frame-only result contains no runtime evidence and reports exact step/frame identities.

- [ ] **Step 5: Run runner tests for RED**

Run:

```bash
./gradlew :harness-scene2d:test \
  --tests '*Scene2dKeyboardGestureRunnerTest' --warning-mode=fail
```

Expected: test compilation fails because the runner is absent.

- [ ] **Step 6: Implement the successful state machine**

Preflight every tick step before dispatch, acquire one synchronized per-runner lease, record a key
in the held set immediately before `keyDown`, remove it only after successful `keyUp`, and close the
frame subscription as soon as the requested number of distinct later frames is observed.

- [ ] **Step 7: Verify the focused Scene2D happy path**

Run the Step 5 command. Expected: all happy-path and ordering tests pass.

- [ ] **Step 8: Commit**

Commit message: `feat(scene2d): run keyboard gestures across frames`

### Task 3: Make timeout, cancellation, concurrency, and cleanup terminal-safe

**Files:**
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dKeyboardGestureRunner.java`
- Modify: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dKeyboardGestureRunnerTest.java`

**Interfaces:**

The future returned by `execute` must override `cancel(boolean)` to signal cancellation and return
`false`; it remains non-terminal until cleanup has completed and then carries a `CANCELLED` result.
The runner also exposes an integration shutdown boundary:

```java
public CompletionStage<Void> stop();
@Override public void close();
```

`stop()` rejects new work, cancels an active operation, and completes after cleanup. `close()` is
idempotent and valid only after the stop stage is terminal; integration code remains responsible
for pumping the render scheduler between the two calls.

- [ ] **Step 1: Add failing failure/cleanup tests**

Cover key-down callback failure, explicit key-up failure, timeout during frame wait, future
cancellation, frame-source closure, scheduler rejection, and session stop. For every case assert:

- still-held keys are released in reverse press order on the render thread;
- the public result is not terminal before cleanup callbacks run;
- cleanup uses a fresh deadline after the request deadline;
- cleanup failure preserves the primary category and lists unreleased keys;
- failed explicit key-up remains cleanup-eligible;
- a successful key-up is never repeated;
- trace-sink failure is ignored and does not replace the input result.

- [ ] **Step 2: Add failing lease and shutdown tests**

Assert a second direct `execute` returns a bounded `SESSION_BUSY` result with zero input callbacks,
the lease remains held throughout cleanup, `stop()` rejects new gestures as `SESSION_CLOSED`, and
one render-scheduler drain lets shutdown cleanup finish.

- [ ] **Step 3: Run lifecycle tests for RED**

Run:

```bash
./gradlew :harness-scene2d:test \
  --tests '*Scene2dKeyboardGestureRunnerTest' --warning-mode=fail
```

Expected: new cleanup, cancellation, and lease assertions fail.

- [ ] **Step 4: Implement one terminal funnel**

Route success, timeout, cancellation, closure, and callback failure through one method that closes
subscriptions/deadline registrations, creates `Deadline.after(clock, Duration.ofSeconds(1))`,
submits each reverse-order key-up separately, records every attempt, and releases the lease only
after the last attempt or cleanup deadline signal.

- [ ] **Step 5: Implement stop/close without loop ownership**

Do not call `Stage.act`, render, or fabricate frames. `stop()` schedules cleanup and returns its
stage. `close()` closes owned subscriptions/deadline registrations only after `stop()` is done.

- [ ] **Step 6: Verify lifecycle GREEN**

Run the Step 3 command. Expected: all runner tests pass, including thread identity and delayed
terminal assertions.

- [ ] **Step 7: Run the affected Scene2D suite**

Run:

```bash
./gradlew :harness-scene2d:test --warning-mode=fail
```

Expected: all existing action, navigation, scenario, session, and new gesture tests pass.

- [ ] **Step 8: Commit**

Commit message: `fix(scene2d): guarantee gesture key cleanup`

### Task 4: Adapt exact controlled ticks from agent-runtime 1.0.0

**Files:**
- Create: `harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/AgentRuntimeTickCoordinator.java`
- Create: `harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/AgentRuntimeTickCoordinatorTest.java`

**Interfaces:**

```java
public final class AgentRuntimeTickCoordinator implements ExactTickCoordinator, AutoCloseable {
    public AgentRuntimeTickCoordinator(
            AgentRuntime runtime,
            String uiSessionId,
            long fixedDeltaNanos,
            FrameSignal completionFrames,
            DeadlineScheduler deadlines);
}
```

`preflight` checks `runtime.commands().isPresent()`, `controls().available()`,
`controls().paused()`, the positive configured delta against `maximumDeltaNanos`, the requested
count against both 10,000 and `ticksPerOperation`, and a non-expired deadline. It returns a closed
rejection rather than throwing arbitrary application text.

`advance` records epoch and current tick, invokes
`controls.advance(internalRequestId, ticks, fixedDeltaNanos, deadline.remaining())`, and if the
operation is pending, polls the same id only after a completed `FrameSignal` notification. A
deadline signal terminates a no-frame wait; there is no sleep or hidden executor. Completion
requires `ControlStopReason.COMPLETED`, equal requested/completed counts, and an unchanged epoch.
Correlations are accepted only when `UiCorrelationRegistry` proves the same runtime frame and UI
session.

- [ ] **Step 1: Write adapter preflight tests**

Build real in-memory agent runtimes for absent dispatcher, absent controller, not paused, invalid
fixed delta, runtime tick limit, expired deadline, and ready state. Assert the controller tick
callback has not run after every rejection.

- [ ] **Step 2: Run preflight tests for RED**

Run:

```bash
./gradlew :harness-agent-runtime:test \
  --tests '*AgentRuntimeTickCoordinatorTest' --warning-mode=fail
```

Expected: test compilation fails because the adapter is absent.

- [ ] **Step 3: Implement preflight only and verify its focused tests**

Keep all agent-runtime imports in this module. Run the Step 2 command; the preflight subset passes
while advance tests remain RED.

- [ ] **Step 4: Write exact advance tests**

Use a queued `ApplicationCommandDispatcher` controlled by the test. Prove the first call observes
pending, draining the application command executes exactly N configured-delta ticks, publishing a
completed frame triggers idempotent polling, and evidence reports exact tick/epoch/runtime frame
identities. Add callback failure, runtime timeout, cancellation, epoch change, and missing/incorrect
UI correlation cases.

- [ ] **Step 5: Implement event-driven advance and correlation**

Generate bounded internal request IDs independent of caller-controlled strings. Cancel the
runtime command through `CommandDispatch.cancel` when the adapter stage is cancelled, close frame
subscriptions/deadline registrations exactly once, and never compare ticks across epochs.

- [ ] **Step 6: Verify adapter GREEN**

Run the Step 2 command. Expected: all adapter and existing runtime-observation tests pass.

- [ ] **Step 7: Commit**

Commit message: `feat(runtime): coordinate exact gesture ticks`

### Task 5: Add the closed protocol command, result, capability, and session coordinator

**Files:**
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/Command.java`
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java`
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessProtocolService.java`
- Create: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/KeyboardGestureProtocolTest.java`
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java`
- Modify: `harness-protocol/src/test/resources/contracts/v1/requests.json`
- Modify: `harness-protocol/src/test/resources/contracts/v1/results.json`

**Interfaces:**

Add `keyboard-gesture` to the sealed `Command` union. Its protocol DTO uses a second closed tagged
union so Jackson can reject unknown variants and unknown members before execution:

```java
record KeyboardGesture(int schemaVersion, List<KeyboardGestureStep> steps) implements Command {
    KeyboardGestureRequest toCore() {
        return new KeyboardGestureRequest(
                schemaVersion, steps.stream().map(KeyboardGestureStep::toCore).toList());
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = KeyDown.class, name = "key-down"),
    @JsonSubTypes.Type(value = WaitFrames.class, name = "wait-frames"),
    @JsonSubTypes.Type(value = WaitTicks.class, name = "wait-ticks"),
    @JsonSubTypes.Type(value = KeyUp.class, name = "key-up")
})
sealed interface KeyboardGestureStep permits KeyDown, WaitFrames, WaitTicks, KeyUp {
    KeyboardGestureRequest.Step toCore();
}

record KeyDown(int keycode) implements KeyboardGestureStep {
    public KeyboardGestureRequest.Step toCore() {
        return new KeyboardGestureRequest.KeyDown(keycode);
    }
}

record WaitFrames(int count) implements KeyboardGestureStep {
    public KeyboardGestureRequest.Step toCore() {
        return new KeyboardGestureRequest.WaitFrames(count);
    }
}

record WaitTicks(int count) implements KeyboardGestureStep {
    public KeyboardGestureRequest.Step toCore() {
        return new KeyboardGestureRequest.WaitTicks(count);
    }
}

record KeyUp(int keycode) implements KeyboardGestureStep {
    public KeyboardGestureRequest.Step toCore() {
        return new KeyboardGestureRequest.KeyUp(keycode);
    }
}
```

Add `HarnessResponse.Result.KeyboardGesture` containing a protocol-owned immutable
`KeyboardGestureData.fromCore(result)` projection. Use stable lower-kebab spellings for terminal,
failure, step, cleanup, and tick fields instead of exposing Java enum names.

```java
@FunctionalInterface
public interface KeyboardGestureCoordinator {
    CompletionStage<KeyboardGestureResult> execute(
            String requestId, KeyboardGestureRequest request, Deadline deadline);
}
```

Append `Optional<KeyboardGestureCoordinator>` to `Session`. Retain explicit overloads for every
existing constructor descriptor, including the old 13-component canonical signature, forwarding
`Optional.empty()` so previously compiled integrations still link.

- [ ] **Step 1: Write protocol decode and bound tests**

Test all four step variants, canonical round-trip, unknown command/step members, unknown kind,
schema versions other than 1, steps 1 and 65, keycodes -1 and 256, counts 0 and 10,001, imbalance,
and cumulative overflow. Use a recording coordinator and assert its call count stays zero for all
decode/model rejections.

- [ ] **Step 2: Run protocol tests for RED**

Run:

```bash
./gradlew :harness-protocol:test \
  --tests '*KeyboardGestureProtocolTest' --warning-mode=fail
```

Expected: test compilation fails because the command and result variants are absent.

- [ ] **Step 3: Implement DTOs and canonical goldens**

Delegate structural validation to `KeyboardGestureRequest`. Add one request and one completed
result golden entry, then extend the explicit variant sets in `ProtocolJsonContractTest` only for
the golden groups that contain the new entries.

- [ ] **Step 4: Write routing and compatibility tests**

Prove capability `ui_keyboard_gesture` is required, a missing coordinator returns
`unsupported-capability`, the request ID and same `Deadline` reach the coordinator, terminal
gesture failures remain structured success-envelope results, and every historical `Session`
constructor resolves and supplies an empty gesture coordinator.

- [ ] **Step 5: Implement routing and constructor preservation**

Add the route before the general command tail, add `ui_keyboard_gesture` to `capability(Command)`,
and map the coordinator's core result to `KeyboardGestureData` without backend objects or stack
traces.

- [ ] **Step 6: Verify protocol GREEN**

Run:

```bash
./gradlew :harness-protocol:test \
  --tests '*KeyboardGestureProtocolTest' \
  --tests '*ProtocolJsonContractTest' --warning-mode=fail
```

Expected: both focused suites pass and the protocol goldens round-trip canonically.

- [ ] **Step 7: Commit**

Commit message: `feat(protocol): expose keyboard gesture command`

### Task 6: Publish the mutating MCP tool and preserve cleanup-aware admission

**Files:**
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/RequestAdmissionTest.java`
- Modify: `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json`
- Modify: `benchmarks/src/main/java/dev/gdx/uiharness/benchmarks/BenchmarkRunner.java`
- Modify: `benchmarks/agentic-palisade/treatments/harness/src/main/java/benchmark/palisade/HarnessCli.java`

**Interfaces:**

Add `ui_keyboard_gesture` with `AccessMode.MUTATING`, a closed step `oneOf`,
`additionalProperties: false` at the envelope and every variant, and exact numeric/list limits.
The output schema contains the complete bounded terminal result, including step, tick, held-key,
failure, and cleanup evidence.

Map the tool to protocol type `keyboard-gesture`. A completed gesture sets MCP `isError=false`.
Every other terminal gesture outcome sets `isError=true` while preserving
`kind=keyboard-gesture-result` and the full structured content; it is not replaced by a generic
diagnostic envelope.

- [ ] **Step 1: Write catalog/schema tests**

Update the approved tool set and count from 23 to 24. Assert mutating access, valid frame/tick
examples, rejection of unknown fields at every nesting level, all numeric boundaries, no locator
property, and an exact output schema for every result field.

- [ ] **Step 2: Run catalog tests for RED**

Run:

```bash
./gradlew :harness-mcp:test \
  --tests '*HarnessToolCatalogTest' --warning-mode=fail
```

Expected: the approved-set/count/schema assertions fail.

- [ ] **Step 3: Implement the catalog and handler mapping**

Add one minimal example per wait kind, the command-type switch case, structured result mapping,
and outcome-sensitive `isError`. Do not expose an action locator or arbitrary application data.

- [ ] **Step 4: Regenerate and review the catalog golden**

Run:

```bash
UPDATE_TOOL_CATALOG_GOLDEN=true ./gradlew :harness-mcp:test \
  --tests '*HarnessToolCatalogTest.goldenCatalogMatchesTypedSchemas' --warning-mode=fail
git diff -- harness-mcp/src/test/resources/mcp/tool-catalog-v1.json
```

Expected: one new tool schema and no unrelated schema changes.

- [ ] **Step 5: Write handler and admission lifecycle tests**

Test completed and failed structured results. Start a long gesture, queue a second mutation for the
same session, cancel the first MCP request, and assert the second mutation does not start until the
gesture source reports cleanup terminal. Also assert another session can progress concurrently.

- [ ] **Step 6: Add a cancellation-transparent translation future**

For `ui_keyboard_gesture`, return a custom translation future whose `cancel` signals the protocol
source but returns `false` and stays non-terminal until the source result arrives. This prevents
Reactor's derived future from becoming cancelled before cleanup and lets `RequestAdmission` retain
the mutation permit. Preserve existing translation behavior for other tools.

- [ ] **Step 7: Update production-tool count consumers**

Change the exact production catalog assertion in `BenchmarkRunner` from 23 to 24 and update the
`HarnessCli` catalog comment. Do not add gesture capability to the benchmark treatment session or
change its scenario corpus.

- [ ] **Step 8: Verify MCP GREEN**

Run:

```bash
./gradlew :harness-mcp:test :benchmarks:test --warning-mode=fail
```

Expected: catalog, schema, handler, cancellation/admission, golden, and benchmark contract tests
all pass.

- [ ] **Step 9: Commit**

Commit message: `feat(mcp): add cleanup-safe keyboard gesture tool`

### Task 7: Wire production Scene2D, runtime ticks, tracing, and LWJGL3 evidence

**Files:**
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java`
- Modify: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/HarnessMcpClient.java`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/KeyboardGestureProductionFixtureTest.java`
- Modify: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/FixtureTracingLifecycleTest.java`

**Integration:**

- Construct `AgentRuntime` with the application dispatcher `Gdx.app::postRunnable`.
- Register one application-owned `SimulationControllerSpec`; its controlled tick callback records
  the configured delta and verifies the fixture's callback-owned key-held state.
- Pause it through `controls.control(true, "fixture-pause", Duration.ofSeconds(5))` and let the
  real render loop dispatch the command.
- Construct `AgentRuntimeTickCoordinator` with `FIXED_STEP.toNanos()`, the existing frame fence,
  and deadline scheduler.
- Construct one `Scene2dKeyboardGestureRunner` over the same configured Stage input processor and
  render scheduler used by existing actions.
- Advertise `ui_keyboard_gesture` and `ui_keyboard_gesture_ticks`; wire the protocol coordinator.
- Send fail-soft `TraceEvent` records with bounded `event` evidence values
  `gesture-accepted`, `gesture-step`, `gesture-failed`, `gesture-cleanup`, and
  `gesture-completed` through `ReferenceTraceController`.

- [ ] **Step 1: Write the real-process fixture test for RED**

Extend `HarnessMcpClient` with a gesture call and update its production tool count to 24. In the new
test, launch the real LWJGL3 process, assert both capabilities, execute:

```json
{"schemaVersion":1,"steps":[
  {"kind":"key-down","keycode":29},
  {"kind":"wait-frames","count":3},
  {"kind":"key-up","keycode":29}
]}
```

Then execute a three-tick variant. Assert exact callback order, completed counts, later completed
frame identities, unchanged runtime epoch, three completed ticks, configured delta evidence, and
no held/unreleased keys.

- [ ] **Step 2: Run the fixture test for RED**

Run:

```bash
xvfb-run -a ./gradlew :harness-fixtures:test \
  --tests '*KeyboardGestureProductionFixtureTest' --warning-mode=fail
```

Expected: the client sees only 23 tools and the gesture capabilities are absent.

- [ ] **Step 3: Implement fixture ownership and wiring**

Use the existing Stage as the configured `InputProcessor`. Add a fixture input listener that owns
its held-key flag; do not inspect `Gdx.input.isKeyPressed`. Keep the controller paused during exact
advancement and never pause/resume implicitly inside a gesture.

- [ ] **Step 4: Implement integration shutdown order**

Before closing the render scheduler, call `gestureRunner.stop()`, drain the scheduler on the render
thread, await the stop stage, close the runner, then close runtime and existing resources. Add an
active-gesture shutdown fixture test proving the release callback arrives before scheduler close.

- [ ] **Step 5: Add trace evidence assertions**

Start a trace, execute a gesture, stop the trace, inspect the archive, and assert bounded ordered
acceptance, step, cleanup when applicable, and completion events share the gesture request ID.

- [ ] **Step 6: Verify the focused real paths**

Run:

```bash
xvfb-run -a ./gradlew :harness-fixtures:test \
  --tests '*KeyboardGestureProductionFixtureTest' \
  --tests '*FixtureTracingLifecycleTest' --warning-mode=fail
```

Expected: both production MCP/LWJGL3 and trace lifecycle tests pass.

- [ ] **Step 7: Run the full fixture suite**

Run:

```bash
xvfb-run -a ./gradlew :harness-fixtures:test --warning-mode=fail
```

Expected: existing press, click, pointer, navigation, scenario, tracing, and runtime fixture tests
remain green.

- [ ] **Step 8: Commit**

Commit message: `test: qualify keyboard gestures in LWJGL3`

### Task 8: Record the architecture and publish exact agent guidance

**Files:**
- Create: `docs/adr/0035-keyboard-gesture-lifecycle.md`
- Modify: `README.md`
- Modify: `docs/guides/agent-tools.md`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/DocsCatalogParityTest.java`
- Modify: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/PublicApiExampleTest.java`

- [ ] **Step 1: Write documentation parity tests for RED**

Require both public documents to list all 24 exact MCP tool names once, include the two gesture
capabilities, and include one parseable frame example plus one parseable tick example. Extend the
public example test with a compile-time construction of `KeyboardGestureRequest` and an
`ExactTickCoordinator` test double.

- [ ] **Step 2: Run docs/example tests for RED**

Run:

```bash
./gradlew :harness-mcp:test \
  --tests '*DocsCatalogParityTest' --warning-mode=fail
xvfb-run -a ./gradlew :harness-fixtures:test \
  --tests '*PublicApiExampleTest' --warning-mode=fail
```

Expected: docs parity and the new public API example fail before documentation is updated.

- [ ] **Step 3: Write ADR 0035**

Record atomicity, real input dispatch, render-thread confinement, frame observation without loop
ownership, optional exact-tick coordination, agent-runtime 1.0.0 polling/correlation limits,
cleanup deadline and reverse release, cancellation/admission semantics, capability names, bounds,
alternatives rejected, and consequences.

- [ ] **Step 4: Update README and agent tool guide**

Document focus-before-gesture, no `keyTyped`, structural and tick preflight before input, exact
capabilities, all bounds, frame versus tick semantics, cleanup guarantees, structured non-success
results, and that configured delta is evidence rather than a runtime 1.0.0 acknowledgement.

- [ ] **Step 5: Verify docs/example GREEN**

Run the commands from Step 2. Expected: parity and compilable examples pass.

- [ ] **Step 6: Scan changed documentation**

Run:

```bash
rg -n 'T[O]DO|T[B]D|F[I]XME|later[ ]fill|same[ ]as[ ]above' \
  docs/adr/0035-keyboard-gesture-lifecycle.md README.md docs/guides/agent-tools.md
git diff --check
```

Expected: no matches and no whitespace errors.

- [ ] **Step 7: Commit**

Commit message: `docs: explain deterministic keyboard gestures`

### Task 9: Verify, review, and prepare branch integration

**Files:**
- Review all files changed since `e231c73`

- [ ] **Step 1: Run narrow model and contract proof**

Run:

```bash
./gradlew :harness-core:test :harness-scene2d:test \
  :harness-agent-runtime:test :harness-protocol:test \
  :harness-mcp:test --warning-mode=fail
```

Expected: all affected unit and contract tests pass.

- [ ] **Step 2: Run real LWJGL3 proof**

Run:

```bash
xvfb-run -a ./gradlew :harness-fixtures:test --warning-mode=fail
```

Expected: all production fixture tests pass under the isolated display.

- [ ] **Step 3: Run public compatibility and benchmark gates**

Run:

```bash
./gradlew :benchmarks:test apiCompatibility --warning-mode=fail
```

Expected: the benchmark production-catalog contract and binary/API compatibility gate pass.

- [ ] **Step 4: Run full project and Javadoc gates**

Run:

```bash
xvfb-run -a ./gradlew check javadoc --warning-mode=fail
```

Expected: the complete build, static checks, tests, and Javadocs pass without project warnings.

- [ ] **Step 5: Inspect exact behavior evidence**

Record the focused test names and outputs proving 30-frame hold, three-tick exact advance,
reverse-order cleanup, cancellation lane retention, real LWJGL3 input, trace order, and no `keyTyped`
gesture callback. Compilation alone is not sufficient.

- [ ] **Step 6: Run implementation review**

Use the `requesting-code-review` skill against the exact branch head. Review especially sealed
union exhaustiveness, input-thread identity, cleanup races, deadline cancellation, runtime epoch
handling, admission lifetime, bounded serialization, historical `Session` constructors, and
absence of backend types beyond their modules. Fix findings test-first and rerun the affected gate.

- [ ] **Step 7: Reconcile spec and implementation**

Check every acceptance criterion in the approved design against one named automated proof. Run:

```bash
git diff --check e231c73 HEAD
git status --short
git log --oneline e231c73..HEAD
```

Expected: no diff errors, no unexplained worktree changes, and intentionally scoped commits.

- [ ] **Step 8: Choose integration action**

Use the `finishing-a-development-branch` skill. Present verified options and perform only the
integration, PR, merge, or local retention action explicitly authorized by the user.
