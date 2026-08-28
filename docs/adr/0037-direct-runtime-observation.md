# ADR 0037: Direct correlated runtime observation

## Status

Accepted — 2026-08-27.

## Context

`ui_runtime_compare` intentionally requires a semantically resolved Actor with a declared runtime binding. Exact game-state evidence such as copied physics angles may be authoritative and agent-visible without belonging in the player HUD. Adding hidden or visible automation-only actors violates semantics-by-construction and conflates runtime evidence with presentation.

The optional runtime observation SPI already accepts explicit bounded entity/property bindings and enforces frame correlation.

## Decision

Add read-only `ui_runtime_observe`. It observes one explicit entity/property/token through an optional session coordinator and returns only a bounded typed completed-frame value or UNAVAILABLE. It performs no Stage/Actor access, reflection, traversal, mutation, or inference.

Advertise the capability only when installed. Keep `ui_runtime_compare` unchanged for display/domain agreement.

## Consequences

Applications can expose authoritative non-UI evidence without fake actors. Callers must know stable public entity/property IDs and the correlation token. The tool cannot enumerate entities, inspect arbitrary objects, or bypass runtime registration/correlation.
