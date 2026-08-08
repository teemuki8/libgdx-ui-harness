# ADR 0032: Render-thread session ownership

- Status: Accepted
- Date: 2026-08-08

## Context

`Scene2dSession` captured `ownerThread` at construction but only `completedFrame` enforced it;
off-thread `snapshot`, `stateActionContract`, `typography`, `layout`, metadata facade, and
adapter-registry access could race or return nondeterministic Scene2D reads instead of failing
fast. Issue #21.

## Decision

Every `Scene2dSession` method that reads or mutates the Stage, actors, adapters, semantic
metadata, or completed-frame state verifies `Thread.currentThread() == ownerThread` and fails
immediately with `HarnessException(ErrorCode.RENDER_THREAD_VIOLATION)` carrying the operation
name, owner thread name, and caller thread name. Non-owner work MUST route through
`RenderThreadScheduler` (submit from any thread, drain on the owner). `isOpen()` and `close()`
stay thread-agnostic. The protocol wire adds the stable `render-thread-violation` error code; MCP
receives the existing typed failure translation unchanged. The success path stays allocation-light
(one reference comparison).

## Consequences

- Off-thread misuse fails fast with actionable evidence instead of racing.
- Scheduler-routed caller-thread waits remain supported.
- A new stable error code is visible end to end (core, protocol, MCP diagnostics).
- Correct render-thread access has no measurable cost.
