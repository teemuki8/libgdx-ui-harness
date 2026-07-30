# ADR 0005: Actor-attributed typography and HiDPI diagnostics

- Status: Accepted
- Date: 2026-07-30

## Context

Framebuffer capture records scale and pixels but cannot explain which text
control, font atlas, filter, transform, origin, or baseline produced a raster
difference. Scene2D `BitmapFont` exposes some rendering facts but does not
universally expose semantic font weight, letter spacing, nominal generation
size, or durable application asset identity.

## Decision

The semantic core owns immutable, versioned, backend-neutral typography
observations and diagnostic results. The Scene2D adapter extracts observable
`Label`, `GlyphLayout`, `BitmapFontData`, texture-filter, and affine-transform
facts on the render thread. Applications may attach explicit font provenance
through the session-owned `Semantics` facade; the adapter never guesses missing
provenance.

Every optional rendering fact is represented as either available data or an
unavailable value with a machine-readable reason. Coordinates use named spaces:
actor local, parent, stage, top-left logical screen, and top-left framebuffer.
Mappings publish full affine matrices and derived scale, rotation, shear, and
fractional framebuffer translation.

LWJGL3 adds current window/framebuffer geometry and raster evidence. Diagnostic
evaluation requires matching application, viewport, control, revision, frame,
capture hash, and reference identities. Missing or mismatched required evidence
fails closed. Observed metadata, source-established mechanisms, controlled-test
results, and unresolved hypotheses remain separate collections in the public
result.

The MCP adapter exposes the bounded result through a closed-schema
`ui_typography_diagnose` tool. It does not mutate application fonts or actors.

## Consequences

- Protocol models contain no Scene2D or backend types.
- Unsupported weight and letter spacing cannot be confused with zero or
  defaults.
- Applications that need source-level attribution must register stable font
  provenance explicitly.
- Public `typography/v1` additions are subject to released API and MCP golden
  schema compatibility checks.
- Typography diagnosis is read-only and cannot silently repair rendering.
