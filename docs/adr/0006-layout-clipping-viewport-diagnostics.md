# ADR 0006: Capture-bound layout, clipping, and viewport diagnostics

## Status

Accepted

## Context

A visually present control can still be unusable because it is owned by the wrong layout or
scroll container, clipped by an internal viewport, mapped through a stale transform, or shifted
by one framebuffer pixel. Global screen-edge checks cannot identify those failures.

## Decision

The semantic core owns immutable `layout/v1` observations and expectations. Each selected
control names its stable actor, parent, layout owner, optional scroll and clip owners, layout
role, padding, bounds in local/stage/screen/framebuffer spaces, transform chain, clip chain,
visible intersection, scroll geometry, layout revision, and layout digest.

The Scene2D adapter extracts this evidence on the render thread. Protocol services bind it to
one fresh capture and reject missing, unexpected, duplicate, stale, moving, or non-invertible
evidence. Quiescence requires three consecutive completed stable frames within 120 frames and
two monotonic seconds, followed by five identical capture samples.

MCP exposes the bounded `ui_layout_diagnose` tool. Its structured response contains concise
actor-attributed summaries while its immutable JSON evidence artifact retains the full
expected-versus-observed contract.

## Consequences

- Internal clipping and cross-control drift are independently diagnosable.
- Backend actors and libGDX collections remain behind the adapter boundary.
- Smooth scrolling must settle reproducibly or the result is `not-stable`.
- Public `layout/v1` additions require released API and MCP golden-schema review.
