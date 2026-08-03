# ADR 0016: Spatial visual comparison evidence

- Status: Accepted
- Date: 2026-08-03

## Context

An aggregate differing-pixel count and mean absolute error can prove that a frame diverged while
giving an agent no useful location or semantic cause. The benchmark also had three canonical
references but exposed only the 1280 by 720 initial state through its harness treatment.

## Decision

Visual comparison retains aggregate metrics and additionally returns at most 256 bounded regions.
Each region uses framebuffer coordinates with a top-left origin and reports its difference
category, optional trustworthy semantic control ID, differing-pixel count, and mean absolute
error. Semantic comparison classifies text, value, bounds, padding, visibility, and clipping;
unattributed changed tiles remain explicit raster residuals.

Every completed comparison also creates one full-frame PNG heatmap. The heatmap is immutable,
dimension-bound to the compared images, SHA-256 verified in core and protocol models, and exposed
through the existing opaque artifact publisher rather than a caller-selected path.

The Palisade harness registers all three digest-bound references. Launch viewport selection is a
closed choice between `desktop-1280x720` and `desktop-1920x1080`; the captured dimensions and
reference identity still have to agree. Bottom-state comparison requires navigation through real
input before capture.

## Consequences

- Agents can prioritize a stable control and framebuffer region before inspecting the full PNG.
- Raster-only references do not invent semantic attribution.
- The MCP output schema grows additively while retaining the bounded existing evidence artifact.
- Typography-specific native glyph size, bitmap scaling, filtering, and rasterization remain the
  responsibility of `ui_typography_diagnose`; spatial comparison links the residual location.

## Verification

```bash
./gradlew :harness-core:test :harness-lwjgl3:test :harness-protocol:test :harness-mcp:test --warning-mode=fail
python3 benchmarks/agentic-palisade/scripts/test-qualification.py
python3 benchmarks/agentic-palisade/scripts/test-convergence-qualification.py
./gradlew check javadoc --warning-mode=fail
```
