# ADR 0018: Declarative UI assertions

## Status

Accepted

## Context

Clients previously queried semantic snapshots and reimplemented UI invariants. That produced inconsistent locator strictness, timing, and diagnostics. Assertions must preserve render-thread confinement, use fresh semantic state, observe completed frames without sleeps, and remain closed and bounded at every public boundary.

## Decision

Expose a version-1 sealed assertion union in `harness-core` and the closed `assert` protocol command through the `ui_assert` MCP tool. The thirteen variants are visible, hidden, enabled, disabled, focused, checked, text-equals, text-contains, count-equals, bounds-inside-viewport, does-not-overlap, stable-for-frames, and accessible-name-exists.

The protocol request contains `schemaVersion`, the existing recursively closed locator, an assertion discriminator, and the request's explicit monotonic deadline. Unknown versions, variants, properties, and nested locator fields fail closed. Wire discriminators use the existing kebab-case convention.

`WaitEngine.assertThat` connects the protocol session to the production assertion engine using the same immutable semantic snapshot source, locator engine, completed-frame signal, and monotonic clock as waits. No libGDX `Actor`, `Stage`, or backend type enters protocol or MCP models. Single-node assertions keep strict zero-match and multiple-match failures distinct; only count-equals accepts arbitrary cardinality. Hidden never means absent.

Successful and condition-failed assertion results include the original locator and assertion, expected and last-observed values, actionability, revision, frame, elapsed milliseconds, bounded candidates, truncation state, and an optional trace identifier. Strict locator failures continue through the established typed error envelope, retaining bounded candidates and trace evidence. Stability compares only the requested immutable properties across distinct completed semantic frames and keeps no unbounded history.

Capability discovery advertises `ui_assert`, and the exact MCP catalog publishes closed input and output schemas plus valid examples for all thirteen variants.

## Consequences

Clients receive one deterministic assertion operation instead of interpreting snapshots themselves. Assertion retries remain event-driven and share production session timing and frame semantics. Adding an assertion variant or changing evidence requires a schema-version decision, protocol golden updates, the exact MCP catalog update, and compatibility review.
