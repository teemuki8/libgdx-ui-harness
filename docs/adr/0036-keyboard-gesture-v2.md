# ADR 0036: Versioned long keyboard gesture timelines

## Status

Accepted — 2026-08-27.

## Context

Keyboard gesture schema v1 deliberately caps one request at 64 steps. Applications with long fixed-tick combat or locomotion timelines must split the logical action into several requests. The exact-tick coordinator lease is request-scoped, so the application resumes its normal loop between requests. Those uncontrolled ticks make the combined timeline differ from deterministic in-process execution and vary with MCP/client latency.

Raising the v1 limit would silently change a published validation contract. A cross-request lease would add session state, abandonment cleanup and concurrency semantics to every transport.

## Decision

Add schema version 2 to the existing `ui_keyboard_gesture` tool with a 256-step hard bound. Keep v1 at 64 steps. Both versions retain the same key, held-key, wait, deadline, cleanup and evidence limits. One request owns one coordinator lease for its complete preflighted timeline.

Sessions advertise additive capability `ui_keyboard_gesture_v2`. No cross-request transaction or persistent lease is introduced.

## Consequences

Long deterministic actions can fit in one bounded request without normal-loop gaps. Existing v1 clients are unchanged. Implementations and result models must handle at most 256 step-evidence entries, increasing bounded worst-case result size. Actions exceeding v2 still require mechanic/test redesign rather than an unbounded timeline.
