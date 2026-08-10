# Keyboard Gesture V1 Design

- Status: Approved design
- Date: 2026-08-10

## Problem

The harness currently exposes `press`, which sends one key-down/key-up pair during a single
render-thread action. That contract cannot reproduce player input that remains active while the
application renders frames or advances deterministic simulation ticks. Independent key-down and
key-up requests would also allow cancellation, client loss, or invalid sequencing to leave an
application key stuck.

Keyboard gesture V1 adds one bounded atomic operation for ordered key transitions and frame or
simulation-tick waits. Input must continue to travel through the application's configured libGDX
`InputProcessor`. The harness does not create an alternate controller, mutate `Gdx.input`, or own
the render or simulation loop.

## Scope

V1 supports these ordered steps:

- `key-down`: dispatch one libGDX key-down callback;
- `wait-frames`: observe a positive number of application-published completed UI frames;
- `wait-ticks`: use an optional application-owned coordinator to advance a positive number of
  deterministic simulation ticks;
- `key-up`: dispatch one libGDX key-up callback.

Ordered key-down steps naturally express modifiers and simultaneous keys. The operation is
session-scoped rather than locator-scoped. A caller that needs widget keyboard focus first uses the
existing strict `focus` action in an earlier mutation.

`press` retains its released behavior, including its current printable-key `keyTyped` synthesis.
Gesture key-down and key-up steps send only their named callbacks and never synthesize
`keyTyped`.

## Non-goals

Keyboard gesture V1 does not add:

- pointer holds, pointer timelines, or held drag paths;
- gamepad buttons or analog axes;
- text-entry or key-repeat synthesis;
- global keyboard-state mutation through `Gdx.input`;
- game-world input actions, entities, physics, ECS data, or gameplay state;
- caller-provided code, callbacks, class names, reflection targets, filesystem paths, commands,
  environment variables, or network listeners;
- automatic pause, resume, restart, or scenario reset;
- frame-to-tick approximation or wall-clock waits.

Those capabilities require separate reviewed designs.

## Public contract

The semantic core owns immutable, backend-neutral `keyboard-gesture/v1` request and result models.
The request carries schema version `1` and an ordered closed step union. A monotonic `Deadline` is
supplied by the execution boundary rather than serialized inside the core model.

The trust-boundary limits are:

| Dimension | Bound |
|---|---:|
| Schema version | exactly `1` |
| Steps | 2 through 64 |
| Key code | 0 through 255 (`Input.Keys.MAX_KEYCODE` in libGDX 1.14.2) |
| Simultaneously held keys | 16 |
| One frame or tick wait | 1 through 10,000 |
| Cumulative frame waits | 10,000 |
| Cumulative tick waits | 10,000 |
| Step evidence entries | 64 |
| Cleanup evidence entries | 16 |
| MCP deadline | 1 through 120,000 milliseconds |
| Cleanup release deadline | 1,000 milliseconds after normal execution terminates |

An installed tick coordinator may impose a lower tick bound. Validation uses overflow-safe
arithmetic before input dispatch.

The protocol command is `keyboard-gesture`. MCP exposes it as the mutating
`ui_keyboard_gesture` tool. Its closed input has this shape:

```json
{
  "sessionId": "game",
  "schemaVersion": 1,
  "steps": [
    {"kind": "key-down", "keycode": 29},
    {"kind": "wait-ticks", "count": 30},
    {"kind": "key-up", "keycode": 29}
  ],
  "deadlineMillis": 30000
}
```

Every step variant rejects unknown members. The MCP catalog publishes exact input/output schemas
and valid examples. Capability discovery distinguishes base keyboard-gesture support from an
installed exact controlled-tick coordinator. Registration does not imply that runtime control is
currently paused or otherwise ready; each request preflights current state.

A well-formed admitted request always reaches one structured terminal result. `completed` is the
only successful terminal outcome; rejected, failed, timed-out, cancelled, and session-closed
outcomes retain their bounded evidence and MCP marks the call as an error without discarding its
structured content. Malformed JSON, unknown members, unsupported schema versions, and model-bound
violations fail at the protocol boundary before a gesture result exists.

## Structural validation

Before acquiring the gesture lease or dispatching input, the runner simulates the complete
sequence and rejects any request where:

- a key is pressed while already held by the same gesture;
- a key is released without being held by the same gesture;
- no key transition exists;
- a wait occurs while no key is held;
- the final simulated held-key set is non-empty;
- a per-step or cumulative bound is exceeded;
- arithmetic overflows;
- a tick wait is present but exact tick control cannot pass preflight.

Preflight failure dispatches no input. Keys already held by the real player or application are
outside harness ownership and are neither inferred nor released.

## Architecture

### `harness-core`

The module adds the versioned request, closed step union, immutable execution evidence, terminal
and cleanup status enums, and a provider-neutral exact-tick coordination interface. Core remains
JDK-only and does not depend on libGDX or agent-runtime.

The tick coordination boundary consumes a validated positive tick count and the remaining
monotonic deadline. It reports the requested/completed count, start/final tick, execution epoch,
first/final runtime frame identities, and any proven UI-frame correlation. It never accepts
application code or transport data.

### `harness-scene2d`

The Scene2D runner owns one active gesture lease. It receives the configured `InputProcessor`,
`RenderThreadScheduler`, `FrameSignal`, monotonic clock/deadline support, and an optional exact-tick
coordinator. Every key transition and cleanup release is submitted through the render scheduler.
Frame and tick callbacks only request the next scheduled transition; they never mutate Stage or
input state directly from a foreign thread.

The runner does not require an Actor or Stage locator. No Actor, Stage, libGDX collection, backend
object, or input processor crosses into core, protocol, or MCP models.

### `harness-agent-runtime`

The reference tick coordinator targets the repository's released
`agent-runtime-core:1.0.0` API. Application integration supplies one positive configured
`fixedDeltaNanos`; the adapter does not infer a timestep. It requires:

- an available registered simulation controller;
- a known paused state that is currently paused;
- an application command dispatcher;
- a tick count within both harness and runtime control limits;
- one unchanged `AgentRuntime.currentEpoch()` across the operation.

The adapter invokes
`SimulationControlRegistry.advance(requestId, ticks, fixedDeltaNanos, timeout)` and observes the
idempotently correlated `ControlOperation` without sleeps or a hidden worker thread. It uses the
application's existing command dispatcher and completed-frame pumping to reach a terminal result.
The operation's requested/completed counts and first/final runtime frame IDs are authoritative.
The fixed delta is explicit configured request evidence; V1 does not claim that agent-runtime 1.0.0
independently acknowledged the application's executed delta.

### `harness-protocol`

The protocol adds a closed command/result pair and an optional session coordinator. Existing
session constructors remain source and binary compatible. Schema rejection, unknown fields,
unsupported versions, and limit failures occur before coordinator invocation.

### `harness-mcp`

The MCP catalog adds one mutating tool. The existing per-session mutation lane remains occupied
until the gesture and cleanup reach a terminal state, so other MCP mutations for the session do
not overlap it. Direct coordinator callers receive a typed session-busy failure when another
gesture owns the lease.

## Execution semantics

### Key transitions

Immediately before invoking `InputProcessor.keyDown`, the runner records the key in its ordered
held set. This ensures a release is attempted if the callback partially handles the event and then
throws.

`InputProcessor.keyUp` removes the key from the held set only after the callback returns
successfully. A failed explicit key-up remains eligible for cleanup.

Success requires every requested step to complete and the held set to be empty.

### Frame waits

`wait-frames(N)` counts exactly `N` completed `FrameSignal.Frame` events published after the
preceding key transition. Revisions, scheduler submissions, wall time, and repeated observation of
one frame do not increment the count. The subscription is closed when the step completes or the
gesture terminates.

The harness observes application-pumped frames; it does not call `Stage.act`, render, or advance an
application frame. Controlled fixture tests may advance their application-owned clock explicitly.

### Tick waits

`wait-ticks(N)` keeps the current gesture keys held while the optional coordinator advances exactly
`N` configured controlled ticks. The coordinator neither pauses nor resumes the simulation. A
request made without a known paused registered controller rejects before input dispatch.

Tick identity is the pair `(executionEpochId, tick)`. Runtime 1.0.0 controlled tick numbers are not
treated as epoch-local, and tick numbers from different epochs are never compared as one timeline.
An epoch change during advancement fails the step and begins cleanup.

Frame waits and tick waits may appear in one gesture. Each step retains its own before/after
evidence; neither wait type is substituted for the other.

## Cancellation, failure, and cleanup

One gesture may run per session. A second direct request fails with the existing typed session-busy
category without affecting the owner.

Timeout, cancellation, key callback failure, frame-source closure, tick rejection/failure,
execution-epoch change, session closure, or scheduler rejection stops normal step execution.
Cleanup then attempts `keyUp` for every still-held key in reverse press order through the same
configured `InputProcessor` on the render thread.

The externally observed operation does not become terminal until release attempts finish.
Cancellation signals the internal runner; it does not abandon cleanup. Cleanup remains active even
if the caller-facing future or transport is no longer interested in the result. The session's
gesture lease is released only after cleanup reaches its own terminal state.

Cleanup never reuses an expired request deadline. Normal execution termination creates one fresh
1,000-millisecond monotonic cleanup deadline, matching the existing Scene2D harness shutdown bound.
If the application does not pump the render scheduler before that deadline, cleanup terminates as
failed with the unreleased key identities retained; it never claims that callbacks were delivered.

The original execution failure remains primary. Each cleanup attempt records the key code and a
closed success/failure outcome. Cleanup failures are retained without replacing the primary
failure, and any cleanup failure makes successful completion impossible. No serialized stack trace
or unrestricted exception text enters protocol evidence.

The application closes integration in this order:

1. stop accepting new gestures;
2. cancel active gesture work;
3. continue pumping the render scheduler until key releases finish or their cleanup deadline
   expires;
4. close the gesture runner;
5. close the scheduler/input integration.

## Evidence and diagnostics

The bounded result reports:

- schema version and a closed terminal outcome;
- requested, started, and completed step counts;
- gesture start/end revision, frame, and monotonic elapsed duration;
- per-step index, kind, status, key/count, and before/after frame identities;
- the ordered gesture-owned held-key set after each transition;
- the failure step and closed failure category, when present;
- cleanup status and ordered release attempts;
- an optional bounded trace ID;
- for tick waits, the execution epoch, requested/completed ticks, starting/final tick,
  first/final runtime frames, and any proven UI-frame correlation.

Tick fields are absent rather than guessed when no correlation source proves them. A frame-only
gesture can complete without runtime evidence. A tick wait cannot complete without exact tick
evidence from its coordinator.

Tracing emits bounded events for gesture acceptance, each transition/wait boundary, terminal
failure, cleanup release, and final completion. Trace recording failure follows the existing
fail-soft tracing policy and cannot replace an input or cleanup failure.

## Error behavior

Failures retain the protocol request/session identity, operation elapsed time, trace ID when
available, completed-step count, last step evidence, currently held gesture keys, and cleanup
outcome. Closed categories distinguish at least:

- invalid request or unsupported schema;
- unsupported tick capability or invalid runtime state;
- session busy or session closed;
- key dispatch failure;
- frame source closed;
- tick advance failure or epoch change;
- timeout or cancellation;
- cleanup failure.

No category silently retries a transition or falls back to an alternate input path.

## Documentation and architectural record

Implementation requires an ADR because the feature adds public Java/protocol/MCP APIs, a
cross-frame input lifecycle, and optional runtime-controlled tick semantics. The README and agent
tool guide must include a compilable/valid keyboard gesture example, capability discovery,
preflight behavior, cleanup guarantees, and the runtime 1.0.0 delta-evidence limitation. MCP docs
and golden catalog resources must remain exact peers.

## Acceptance criteria

1. A gesture containing `key-down(A)`, `wait-frames(30)`, and `key-up(A)` sends one down callback,
   keeps the application's callback-owned key state active through exactly 30 subsequent completed
   frame events, and sends one up callback.
2. A gesture containing modifier and non-modifier downs preserves request order and releases them
   in request order on success.
3. With the agent-runtime coordinator configured, paused, and dispatched, `wait-ticks(30)` reports
   30 requested and completed controlled ticks in one unchanged execution epoch, with the key held
   across those ticks.
4. Tick gestures reject before the first key callback when control, dispatch, pause state, fixed
   delta, bounds, or current capability is invalid.
5. Invalid balance, duplicate down, unmatched up, wait-without-held-key, unbalanced terminal state,
   unknown fields, unsupported versions, and every bound violation reject before input dispatch.
6. Callback failure, timeout, cancellation, frame-source closure, tick failure, epoch change, and
   session closure all attempt reverse-order release of every gesture-owned held key on the render
   thread.
7. The public operation reaches terminal state only after cleanup attempts finish; cleanup failure
   is explicit and can never produce a successful result.
8. Concurrent MCP mutations remain serialized for the whole gesture, and a competing direct
   gesture receives a typed session-busy result.
9. Protocol and MCP output contains only immutable bounded models and never exposes Stage, Actor,
   InputProcessor, runtime objects, stack traces, arbitrary callback data, or caller-selected
   resources.
10. Existing `press`, click, pointer, navigation, tracing, and sessions without a tick coordinator
    remain compatible.

## Verification

Implementation follows red-green-refactor. Each production behavior starts with a focused failing
behavioral test and records its expected failure before the minimal implementation is added.

Narrow model, Scene2D, runtime-adapter, protocol, and MCP proof:

```bash
./gradlew :harness-core:test :harness-scene2d:test \
  :harness-agent-runtime:test :harness-protocol:test \
  :harness-mcp:test --warning-mode=fail
```

Real LWJGL3 fixture/input path under an isolated display:

```bash
xvfb-run -a ./gradlew :harness-fixtures:test --warning-mode=fail
```

Affected benchmark and public compatibility gates:

```bash
./gradlew :benchmarks:test --warning-mode=fail
./gradlew apiCompatibility --warning-mode=fail
```

Full project and Javadoc gate under an isolated display:

```bash
xvfb-run -a ./gradlew check javadoc --warning-mode=fail
```

Verification must inspect the exact gesture path and event order; compilation or schema generation
alone is not sufficient evidence.
