# ADR 0035 — Bounded keyboard gesture lifecycle

## Status

Accepted.

## Context

The existing `press` action delivers one key-down/key-up pair within one render-thread turn and,
for printable keys, may synthesize `keyTyped`. It cannot represent a key held while later UI
frames complete or while an application-owned deterministic simulation advances. Exposing
independent remote key-down and key-up calls would let cancellation, timeout, client loss, or a
second mutation strand a pressed key.

The harness must preserve its existing boundaries: real libGDX input dispatch, application-owned
render and simulation loops, render-thread confinement, immutable bounded protocol models, and no
application callbacks or backend objects crossing into transport data.

## Decision

Add the versioned `KeyboardGestureRequest` and `KeyboardGestureResult` semantic-core contracts and
one session-scoped `ui_keyboard_gesture` MCP mutation. A gesture is one atomic ordered timeline of
`key-down`, `wait-frames`, `wait-ticks`, and `key-up` steps. It owns one session lease and the MCP
mutation lane from admission until normal completion or cleanup completion.

The request is structurally validated in full before execution. Schema version is exactly 1; a
request has 2 through 64 steps, keycodes from 0 through 255, no more than 16 simultaneously held
keys, and individual and cumulative frame/tick wait bounds of 10,000. A wait requires a held key,
duplicate downs and unmatched ups are rejected, and the simulated final held-key set must be
empty. Overflow and all tick preflight failures occur before input dispatch.

### Input and threading

Every requested transition calls `keyDown` or `keyUp` on the application's configured
`InputProcessor` through its render-thread scheduler. The operation does not invoke listeners,
mutate `Gdx.input`, or synthesize `keyTyped`; text entry and repeat generation remain outside this
contract. A focus-dependent gesture is preceded by the existing strict `focus` action because the
gesture itself is intentionally not locator-scoped.

The runner records a key as held immediately before key-down dispatch so cleanup covers a callback
that partially handles input and then throws. It removes a key only after key-up returns. No
`Actor`, `Stage`, libGDX collection, input processor, or LWJGL3 type enters core or protocol data.

### Frame and tick waits

`wait-frames(N)` counts exactly `N` later completed `FrameSignal` publications. The runner observes
the application loop; it never calls `Stage.act`, renders, or advances a frame. Revisions, repeated
reads of one frame, and wall time are not substitute frame events.

`wait-ticks(N)` uses an optional provider-neutral `ExactTickCoordinator`. Capability
`ui_keyboard_gesture` reports base frame-gesture support;
`ui_keyboard_gesture_ticks` reports that a tick coordinator is installed. Installation is not a
readiness claim. Before any input, every tick step must pass current controller, paused-state,
deadline, fixed-delta, and count limits. The coordinator never pauses or resumes implicitly.

The reference adapter polls the released `agent-runtime-core:1.0.0` control operation through the
application dispatcher and completed-frame signal without sleeps or a hidden simulation loop. It
requires one unchanged execution epoch, exact requested/completed tick counts, and first/final
runtime frame identities. UI frame endpoints are emitted only when both correlations are proven.
`configuredDeltaNanos` is explicit harness integration evidence; runtime 1.0.0 does not return an
independent acknowledgement that the callback executed that delta.

### Cancellation and cleanup

Completed is the only successful outcome. Rejection, callback or coordinator failure, timeout,
cancellation, frame-source closure, epoch change, session closure, and scheduler rejection produce
closed structured outcomes with bounded step and failure evidence.

Abnormal termination cancels active frame/tick observation and releases every harness-owned key in
reverse press order through the same configured input processor on the render thread. Cleanup uses
a fresh 1,000 ms monotonic deadline rather than the expired request deadline. It records at most 16
release attempts and retains unreleased key identities. Cleanup failure supplements, but never
replaces, the primary execution failure.

Transport cancellation signals the runner but does not detach cleanup. The per-session mutation
lane and gesture lease remain occupied until cleanup reaches a terminal state; therefore a later
mutation cannot overlap releases. Integration shutdown stops gesture admission, pumps the render
scheduler until active cleanup terminates, closes the runner and tick coordinator, and only then
closes the frame source, runtime, session, and scheduler.

Trace recording is fail-soft. Bounded `gesture-accepted`, `gesture-step`, `gesture-failed`,
`gesture-cleanup`, and `gesture-completed` events share the protocol request identity. A recorder
failure cannot replace transition or cleanup evidence.

## Alternatives rejected

- Separate remote key-down and key-up tools cannot provide atomic ownership or guaranteed cleanup.
- Invoking Scene2D listeners directly bypasses the application's actual input integration.
- Polling `Gdx.input.isKeyPressed` or mutating global input state confuses physical and
  harness-owned keys.
- Advancing the render loop from the harness violates application loop ownership.
- Treating frames, elapsed time, or an unpaused runtime as controlled ticks invents simulation
  semantics and unverifiable evidence.
- Automatically pausing or resuming around a tick changes application state outside the request.
- Completing transport cancellation before releases finish permits overlapping mutations and
  stuck keys.

## Consequences

- Agents can express modifiers and gameplay-style key holds with deterministic frame or exact-tick
  evidence through one closed operation.
- Applications retain ownership of focus, input routing, render pumping, simulation callbacks,
  pause state, and fixed timestep configuration.
- Tick support remains optional and may reject a request even when advertised if current runtime
  state is not ready.
- Cancellation and shutdown can take up to the bounded cleanup deadline and may report explicit
  unreleased keys instead of claiming success.
- The MCP catalog contains exactly twenty-four tools, with `ui_keyboard_gesture` classified as a
  mutating operation.
