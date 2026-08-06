# ADR 0024: Compact state-transition tracing

## Status

Accepted

## Context

The bounded causal trace archive holds the evidence needed to explain UI changes, but agents had to download and inspect the full archive to answer questions like which input appeared a control, which action disabled a button, or when text changed. Compact summaries must project retained evidence without inventing causality, keep full traces opaque, and report gaps, unknown causes, identity ambiguity, and truncation explicitly.

## Decision

Add the closed transition vocabulary and the pure `TransitionProjector` in `harness-core`, the bounded `SemanticObservationStore` retention, and the closed `trace-query` protocol operation with the `ui_trace_query` MCP tool.

The projector consumes bounded retained `SemanticObservation` values (sequence, frame, revision, snapshot, optional cause sequence) and a bounded `TransitionQuery` (trace identity, optional lazy locator, allowlisted kinds and property paths, inclusive frame range, and hard result bounds). Adjacent observations are matched by stable semantic keys (unique test ID, then role plus accessible name with parent context), never snapshot-local node IDs. Closed `TransitionKind` values classify appeared, disappeared, enabled, disabled, text-changed, bounds-changed, focus-changed, modal-changed, obscuration-changed, z-order-changed, and identity-ambiguous transitions. Changed nodes report bounded property paths and before/after values; a transition is attributed to a cause only when the after observation carries a proven cause sequence. Frame jumps are reported as gaps; unknown causes are counted. Output is immutable, deterministically ordered, and strictly bounded with explicit truncation.

`SemanticObservationStore` retains bounded observations per trace alongside archive recording; overflow drops the oldest observations and is surfaced as gaps. Full trace artifacts remain opaque and no archive path crosses protocol or MCP.

The protocol session routes `trace-query` through a new default `TraceController.query` method (additive; existing start/stop controllers remain source compatible) and advertises the `ui_trace_query` capability. The closed request carries the query spec; the response carries the compact projection.

## Consequences

Agents can answer common transition questions with compact, deterministic, bounded summaries without downloading trace archives. Adding a transition kind or changing key precedence requires protocol golden updates, schema review, and the exact MCP catalog update.
