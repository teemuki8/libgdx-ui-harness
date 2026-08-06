# ADR 0025: Explicit UI-to-runtime entity bindings

## Status

Accepted

## Context

Semantic UI nodes describe controls but cannot declare which runtime entity or property they present, so agents cannot reliably find the UI for an entity, compare displayed and runtime values, or correlate UI changes with the same simulation frame. Bindings must be explicit application metadata, never inferred from labels, actor names, reflection, or object identity, and the harness must remain fully usable without any runtime provider dependency.

## Decision

Add typed `RuntimeBinding` metadata to semantic nodes, closed entity and entity-property locator filters, the Scene2D binding facade, and the closed `runtime-compare` protocol operation with the `ui_runtime_compare` MCP tool.

Core `RuntimeBinding` carries bounded entity ID, property ID, optional value-format, comparator, and correlation identities. `SemanticNode` gains an additive nullable binding component with a backward-compatible constructor. The closed `EntityLocator` and `EntityPropertyLocator` variants select nodes by explicit binding with strict zero/multiple semantics; protocol wire variants map both directions.

`Semantics` exposes `bindEntity`, `bindProperty`, and `bind` for weak session-owned application metadata; the snapshotter carries bindings atomically into every semantic observation. Applications without bindings behave exactly as before.

The `RuntimeComparator` compares a strictly resolved bound node's displayed value against an optional application-supplied read-only `RuntimeObservationSource`. Equality is claimed only for typed values on provably correlated frames; missing, unavailable, stale, uncorrelated, and ambiguous states remain distinct closed statuses with bounded details. No runtime object, entity object, reflection target, or unrestricted runtime query crosses the protocol or MCP boundary.

The protocol session gains an optional `RuntimeCompareCoordinator` boundary with backward-compatible constructors; the closed request carries a strict locator and a deadline bound; the response carries the typed comparison.

## Consequences

Agents can find UI for runtime entities, compare displayed and runtime values with explicit correlation, and correlate UI changes to simulation frames without a mandatory runtime library. Adding a binding field or comparator mode requires protocol golden updates, schema review, and the exact MCP catalog update.
