package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/** Immutable expected typography settings and thresholds for one stable control. */
public record TypographyControlReference(
        String controlId,
        String expectedFontSourceId,
        double expectedNominalSize,
        double expectedGeneratedGlyphSize,
        double expectedBitmapScaleX,
        double expectedBitmapScaleY,
        String expectedMinificationFilter,
        String expectedMagnificationFilter,
        double expectedDeviceScaleX,
        double expectedDeviceScaleY,
        EvidenceValue<Double> expectedWeight,
        EvidenceValue<Double> expectedLetterSpacing,
        CoordinateBounds expectedInkBounds,
        double expectedBaseline,
        double inkBoundsTolerance,
        double baselineTolerance,
        double transformTolerance,
        double rasterResidualThreshold,
        String expectedTransformSha256) {

    /** Validates expected values through the evaluator contract. */
    public TypographyControlReference {
        new TypographyExpectation(
                controlId,
                "validation-app",
                "validation-viewport",
                0,
                0,
                "validation-reference",
                "validation-current",
                expectedFontSourceId,
                expectedNominalSize,
                expectedGeneratedGlyphSize,
                expectedBitmapScaleX,
                expectedBitmapScaleY,
                expectedMinificationFilter,
                expectedMagnificationFilter,
                expectedDeviceScaleX,
                expectedDeviceScaleY,
                expectedWeight,
                expectedLetterSpacing,
                expectedInkBounds,
                expectedBaseline,
                inkBoundsTolerance,
                baselineTolerance,
                transformTolerance,
                rasterResidualThreshold,
                expectedTransformSha256);
        Objects.requireNonNull(controlId, "controlId");
    }

    /** Binds this immutable reference to one current completed capture. */
    public TypographyExpectation bind(
            TypographyReference reference,
            long revision,
            long frame,
            String currentArtifactId) {
        return new TypographyExpectation(
                controlId,
                reference.applicationId(),
                reference.viewportId(),
                revision,
                frame,
                reference.referenceArtifactId(),
                currentArtifactId,
                expectedFontSourceId,
                expectedNominalSize,
                expectedGeneratedGlyphSize,
                expectedBitmapScaleX,
                expectedBitmapScaleY,
                expectedMinificationFilter,
                expectedMagnificationFilter,
                expectedDeviceScaleX,
                expectedDeviceScaleY,
                expectedWeight,
                expectedLetterSpacing,
                expectedInkBounds,
                expectedBaseline,
                inkBoundsTolerance,
                baselineTolerance,
                transformTolerance,
                rasterResidualThreshold,
                expectedTransformSha256);
    }
}
