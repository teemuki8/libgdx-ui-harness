# Task 3 fix round 6: blank-line advance scaling

## Regressions and RED

Two focused regressions set public `BitmapFontData.blankLineScale` to `0.5` before label layout:

- `multipleTrailingBlankLinesUseExactLabelScaleMetrics` now covers `"A\n\n"`, where GlyphLayout advances the newline after `A` by unscaled `down` and the subsequent blank line by `down * blankLineScale`.
- `allBlankLinesUseScaledBlankLineAdvance` covers `"\n\n"`, where both advances use `down * blankLineScale`, and verifies that exact zero-ink geometry remains available.

Focused RED command:

```text
xvfb-run -a ./gradlew :harness-scene2d:test \
  --tests '*Scene2dLayoutValidatorTest.multipleTrailingBlankLinesUseExactLabelScaleMetrics' \
  --tests '*Scene2dLayoutValidatorTest.allBlankLinesUseScaledBlankLineAdvance' \
  --warning-mode=fail
```

Pre-fix result:

```text
2 tests completed, 2 failed
BUILD FAILED
```

Both layouts were incorrectly marked unavailable.

## Correction

`Scene2dTextGeometry` now reconstructs the candidate GlyphLayout height in GlyphLayout's advance order. For a trailing newline sequence after non-empty content, it adds one unscaled public line advance and scales only the remaining blank-line advances. For all-newline text, every advance uses public `BitmapFontData.blankLineScale`. Text without trailing newlines adds no trailing advance.

The candidate comparison still uses exact public layout height evidence and preserves conservative unavailability when competing effective font metrics cannot be distinguished.

## GREEN

The complete focused validator and typography classes were run together:

```text
xvfb-run -a ./gradlew :harness-scene2d:test \
  --tests '*Scene2dLayoutValidatorTest*' \
  --tests '*Scene2dTypographyExtractorTest*' \
  --warning-mode=fail
```

Final result:

```text
BUILD SUCCESSFUL
```
