# ADR 0020: Deterministic navigation diagnostics

## Status

Accepted

## Context

Semantic snapshots exposed focus state, but agents could not deterministically inspect or validate keyboard and controller focus paths as first-class operations. Traversal must start from a repeatable known state, use the application's real configured libGDX input dispatch, advance only through completed rendered frames under an injected monotonic clock, and keep every step, cycle, dead end, modal escape, and unreachable control explicitly classified and bounded.

## Decision

Add `navigation-inspect` and `navigation-validate` protocol operations and the `ui_navigation_inspect`/`ui_navigation_validate` MCP tools, backed by a pure core validation model and a Scene2D execution adapter.

`harness-core` owns the closed navigation vocabulary: `NavigationInput`, `NavigationStep`, `NavigationPath`, `NavigationReason` (COMPLETE, CYCLE, DEAD_END, MODAL_ESCAPE, FOCUS_LOST, UNREACHABLE_CONTROL, UNSUPPORTED_CONTROLLER_PATH, DEADLINE, TRUNCATED), the bounded `NavigationRequest`/`NavigationResult`, and a pure `NavigationValidator` that classifies adapter-supplied observations without dispatching input. Step identities are stable semantic identities; the `state:no-focus` sentinel records a missing focus explicitly. A request may declare the observed default focus so an empty traversal still reports what was observed.

`harness-scene2d` owns `Scene2dNavigationRunner`: it acquires the registered scenario through the #39 lease API, captures the observed default focus and known focusables on a completed frame, dispatches each declared input through the application's configured `Scene2dInputDispatcher`, awaits a strictly later completed frame, appends one correlated step, and terminates with a closed reason. Scenario cleanup runs inline when the release happens during a render-thread drain and remains a queued render-thread operation otherwise, preserving the async-release contract. Cycle detection uses stable identity plus modal context, not snapshot-local node IDs. Traversal bounds cover steps, actors, durations, result bytes, and evidence bytes with explicit truncation.

The protocol session gains an optional `NavigationCoordinator` boundary; the closed `NavigationSpec` carries scenario identity, inputs, optional start focus, controller support, and hard bounds. The MCP catalog publishes the two tools with closed input/output schemas and examples, and capability discovery advertises them.

## Consequences

Agents can deterministically inspect and validate focus paths with real input dispatch, distinct closed reason codes, and per-step frame/revision correlation. The `Session` constructor gained an optional coordinator without breaking released signatures. Adding an input mode or reason code requires protocol golden updates, schema review, and the exact MCP catalog update.
