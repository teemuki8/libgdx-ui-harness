# ADR 0013: Off-viewport structural coordinates

- Status: Accepted
- Date: 2026-07-30

## Context

A scrolled Scene2D hierarchy can place an actor above or to the left of the visible viewport.
Its top-left framebuffer `x` or `y` coordinate is then negative even though its width and height
remain valid. The structural observation decoder rejected every negative rectangle component,
making truthful off-viewport measurements schema-invalid.

Clamping those coordinates to zero would erase layout and clipping evidence and could make
different actor bounds indistinguishable.

## Decision

`structural-observation/v1` rectangles require finite `x`, `y`, `width`, and `height`.
Width and height must be non-negative. Position coordinates may be negative and must retain their
measured framebuffer values.

## Consequences

- Truthful off-viewport actor bounds remain schema-compatible.
- Negative extents and non-finite values still fail closed.
- Existing retained qualifications remain historical evidence and cannot qualify a release that
  uses the corrected evaluator.

## Verification

```bash
./gradlew -p benchmarks/agentic-palisade/evaluator test --warning-mode=fail
python3 benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py
./gradlew check javadoc --warning-mode=fail
```
