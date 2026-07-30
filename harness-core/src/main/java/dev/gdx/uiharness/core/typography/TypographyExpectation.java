package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/** Named reference and thresholds for one text control. */
public record TypographyExpectation(
        String controlId,
        String applicationId,
        String viewportId,
        long revision,
        long frame,
        String referenceArtifactId,
        String currentArtifactId,
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

    /** Validates identities, expected values, and non-negative thresholds. */
    public TypographyExpectation {
        TypographySupport.requireNonBlank(controlId, "controlId");
        TypographySupport.requireNonBlank(applicationId, "applicationId");
        TypographySupport.requireNonBlank(viewportId, "viewportId");
        if (revision < 0 || frame < 0) {
            throw new IllegalArgumentException("revision and frame must be non-negative");
        }
        TypographySupport.requireNonBlank(referenceArtifactId, "referenceArtifactId");
        TypographySupport.requireNonBlank(currentArtifactId, "currentArtifactId");
        TypographySupport.requireNonBlank(expectedFontSourceId, "expectedFontSourceId");
        TypographySupport.requirePositiveFinite(expectedNominalSize, "expectedNominalSize");
        TypographySupport.requirePositiveFinite(
                expectedGeneratedGlyphSize, "expectedGeneratedGlyphSize");
        TypographySupport.requirePositiveFinite(expectedBitmapScaleX, "expectedBitmapScaleX");
        TypographySupport.requirePositiveFinite(expectedBitmapScaleY, "expectedBitmapScaleY");
        TypographySupport.requireNonBlank(
                expectedMinificationFilter, "expectedMinificationFilter");
        TypographySupport.requireNonBlank(
                expectedMagnificationFilter, "expectedMagnificationFilter");
        TypographySupport.requirePositiveFinite(expectedDeviceScaleX, "expectedDeviceScaleX");
        TypographySupport.requirePositiveFinite(expectedDeviceScaleY, "expectedDeviceScaleY");
        Objects.requireNonNull(expectedWeight, "expectedWeight");
        Objects.requireNonNull(expectedLetterSpacing, "expectedLetterSpacing");
        Objects.requireNonNull(expectedInkBounds, "expectedInkBounds");
        TypographySupport.requireFinite(expectedBaseline, "expectedBaseline");
        TypographySupport.requireNonNegativeFinite(
                inkBoundsTolerance, "inkBoundsTolerance");
        TypographySupport.requireNonNegativeFinite(
                baselineTolerance, "baselineTolerance");
        TypographySupport.requireNonNegativeFinite(
                transformTolerance, "transformTolerance");
        TypographySupport.requireNonNegativeFinite(
                rasterResidualThreshold, "rasterResidualThreshold");
        TypographySupport.requireNonBlank(
                expectedTransformSha256, "expectedTransformSha256");
    }

    /** Returns a copy requiring another observed weight. */
    public TypographyExpectation withExpectedWeight(EvidenceValue<Double> value) {
        return copy(revision, frame, value, expectedTransformSha256);
    }

    /** Returns a copy bound to another frame. */
    public TypographyExpectation withFrame(long value) {
        return copy(revision, value, expectedWeight, expectedTransformSha256);
    }

    /** Returns a copy bound to another revision. */
    public TypographyExpectation withRevision(long value) {
        return copy(value, frame, expectedWeight, expectedTransformSha256);
    }

    /** Returns a copy requiring another transform digest. */
    public TypographyExpectation withExpectedTransformSha256(String value) {
        return copy(revision, frame, expectedWeight, value);
    }

    /** Returns a copy requiring another bitmap scale on both axes. */
    public TypographyExpectation withExpectedBitmapScale(double x, double y) {
        return new TypographyExpectation(controlId, applicationId, viewportId,
                revision, frame, referenceArtifactId, currentArtifactId,
                expectedFontSourceId, expectedNominalSize, expectedGeneratedGlyphSize,
                x, y, expectedMinificationFilter, expectedMagnificationFilter,
                expectedDeviceScaleX, expectedDeviceScaleY, expectedWeight,
                expectedLetterSpacing, expectedInkBounds, expectedBaseline,
                inkBoundsTolerance, baselineTolerance, transformTolerance,
                rasterResidualThreshold, expectedTransformSha256);
    }

    private TypographyExpectation copy(
            long nextRevision,
            long nextFrame,
            EvidenceValue<Double> nextWeight,
            String nextTransformSha256) {
        return new TypographyExpectation(controlId, applicationId, viewportId,
                nextRevision, nextFrame, referenceArtifactId, currentArtifactId,
                expectedFontSourceId, expectedNominalSize, expectedGeneratedGlyphSize,
                expectedBitmapScaleX, expectedBitmapScaleY, expectedMinificationFilter,
                expectedMagnificationFilter, expectedDeviceScaleX, expectedDeviceScaleY,
                nextWeight, expectedLetterSpacing, expectedInkBounds, expectedBaseline,
                inkBoundsTolerance, baselineTolerance, transformTolerance,
                rasterResidualThreshold, nextTransformSha256);
    }
}
