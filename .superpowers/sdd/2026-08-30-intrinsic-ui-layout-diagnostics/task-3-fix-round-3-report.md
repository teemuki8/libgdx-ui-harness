# Task 3 fix round 3: exact-or-unavailable Label geometry

## Correction

`Scene2dTextGeometry` now inspects only public `Label`, `GlyphLayout`, font, background-inset, and alignment state. The capture `Batch` and the `Label.draw` call were deleted. Geometry is reconstructed at the conceptual, unsnapped layout origin and real glyph ink is derived by walking the public glyph runs. No rendered cache state is consulted.

`Scene2dTextGeometry.placement` now returns `Optional<Placement>`. `Scene2dTextLayoutExtractor` converts one ambiguous label into `LayoutValidationEvidence.unavailable()` for the entire intrinsic observation, so every enabled intrinsic check produces the existing hard `CHECK_UNAVAILABLE` ERROR. `Scene2dTypographyExtractor` omits the corresponding ambiguous typography observation rather than publishing guessed coordinates.

## Exact ambiguity predicate

The public-state decision is:

1. `wrap=false` is the exact single-line branch.
2. An explicit newline, or public glyph runs on distinct baselines, proves the multiline Label branch and is exact.
3. For `wrap=true`, no newline, and one publicly visible line, one non-left-aligned glyph run exposes the layout target width through `GlyphRun.x`: matching the full content-width alignment offset proves effective wrapping was disabled; a differing offset proves effective wrapping.
4. In every remaining one-line case, the algorithm computes both conceptual origins allowed by public state: effective wrap and private-ellipsis-disabled wrap. If both float origins are identical, the geometry is exact. If either coordinate differs, placement is unavailable. This includes fitting or truncating left-line-aligned text whose center/right block alignment would change placement.

Zero-glyph non-wrapped layouts remain exact: their zero-area ink is placed at the computed aligned conceptual origin rather than `(0,0)`. A zero-glyph wrapped layout is subject to the same two-origin predicate and is declined when the alternatives differ.

## Regressions and RED

The regressions were added before the production correction and run with:

```text
xvfb-run -a ./gradlew :harness-scene2d:test \
  --tests '*Scene2dLayoutValidatorTest.validationNeverInvokesLabelOrBackgroundDraw' \
  --tests '*Scene2dLayoutValidatorTest.fittingOneLineWrapWithAmbiguousBlockPlacementIsHardUnavailable' \
  --tests '*Scene2dLayoutValidatorTest.exactPlacementPreservesHalfUnitOriginAndZeroGlyphOrigin' \
  --tests '*Scene2dTypographyExtractorTest.typographyNeverDrawsAndPreservesConceptualAndZeroGlyphOrigins' \
  --tests '*Scene2dTypographyExtractorTest.typographyDeclinesAmbiguousOneLineWrapPlacement' \
  --warning-mode=fail
```

Pre-fix result:

```text
5 tests completed, 5 failed
BUILD FAILED
```

The failures independently established application `Label`/background draw dispatch, snapped rather than half-unit conceptual placement, collapsed zero-glyph placement, available intrinsic evidence for ambiguous one-line wrap, and a published typography observation for the same ambiguity.

## GREEN and retained behavior

The complete focused classes were rerun together:

```text
xvfb-run -a ./gradlew :harness-scene2d:test \
  --tests '*Scene2dLayoutValidatorTest*' \
  --tests '*Scene2dTypographyExtractorTest*' \
  --warning-mode=fail

BUILD SUCCESSFUL
```

The focused suites retain exact ordinary multiline wrap, explicit newline, ordinary left/non-wrapped alignment, transformed geometry, real glyph ink, and ScrollPane actor-area clipping while enforcing hard unavailability for geometry that public libGDX 1.14.2 state cannot distinguish.
