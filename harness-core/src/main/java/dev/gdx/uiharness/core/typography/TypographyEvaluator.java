package dev.gdx.uiharness.core.typography;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates one current text-rendering observation against one named reference. */
public final class TypographyEvaluator {

    /** Returns a fail-closed attributed diagnosis. */
    public TypographyReport evaluate(
            TypographyObservation observed, TypographyExpectation expected) {
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(expected, "expected");
        List<TypographyDiagnostic> diagnostics = new ArrayList<>();

        compareIdentity(diagnostics, observed, expected);
        if (observed.revision() != expected.revision()) {
            add(diagnostics, observed, expected, "revision",
                    expected.revision(), observed.revision(), "revision", null);
        }
        if (observed.frame() != expected.frame()) {
            add(diagnostics, observed, expected, "frame",
                    expected.frame(), observed.frame(), "frame", null);
        }
        if (!diagnostics.isEmpty()) {
            return report(TypographyStatus.STALE, observed, diagnostics);
        }

        if (!observed.transforms().invertible()) {
            add(diagnostics, observed, expected, "transforms.invertible",
                    true, "non-invertible", "boolean", null);
            return report(TypographyStatus.NOT_DIAGNOSABLE, observed, diagnostics);
        }

        if (!observed.transformSha256().equals(expected.expectedTransformSha256())) {
            add(diagnostics, observed, expected, "transformSha256",
                    expected.expectedTransformSha256(), observed.transformSha256(),
                    "sha256", null);
            return report(TypographyStatus.NOT_STABLE, observed, diagnostics);
        }

        compareRequiredEvidence(diagnostics, observed, expected);
        if (diagnostics.stream().anyMatch(value ->
                value.observed().equals("unsupported")
                        || value.observed().equals("not-registered")
                        || value.observed().equals("not-exposed")
                        || value.observed().equals("missing")
                        || value.observed().equals("unknown")
                        || value.observed().equals("non-invertible"))) {
            return report(TypographyStatus.NOT_DIAGNOSABLE, observed, diagnostics);
        }

        compareGeometryAndRaster(diagnostics, observed, expected);
        TypographyStatus status = diagnostics.isEmpty()
                ? TypographyStatus.PIXEL_SHARP
                : TypographyStatus.NOT_PIXEL_SHARP;
        return report(status, observed, diagnostics);
    }

    private static void compareIdentity(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected) {
        compare(diagnostics, observed, expected, "controlId",
                expected.controlId(), observed.controlId(), "id", null);
        compare(diagnostics, observed, expected, "applicationId",
                expected.applicationId(), observed.display().applicationId(), "id", null);
        compare(diagnostics, observed, expected, "viewportId",
                expected.viewportId(), observed.display().viewportId(), "id", null);
        compare(diagnostics, observed, expected, "currentArtifactId",
                expected.currentArtifactId(), observed.currentArtifactId(), "id", null);
    }

    private static void compareRequiredEvidence(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected) {
        compareEvidence(diagnostics, observed, expected, "font.sourceId",
                expected.expectedFontSourceId(), observed.font().sourceId(), "id");
        compareEvidence(diagnostics, observed, expected, "font.nominalSize",
                expected.expectedNominalSize(), observed.font().nominalSize(), "logical-px");
        compareEvidence(diagnostics, observed, expected, "font.generatedGlyphSize",
                expected.expectedGeneratedGlyphSize(),
                observed.font().generatedGlyphSize(), "logical-px");
        compare(diagnostics, observed, expected, "font.bitmapScaleX",
                expected.expectedBitmapScaleX(), observed.font().bitmapScaleX(),
                "ratio", null);
        compare(diagnostics, observed, expected, "font.bitmapScaleY",
                expected.expectedBitmapScaleY(), observed.font().bitmapScaleY(),
                "ratio", null);
        compareEvidence(diagnostics, observed, expected, "font.atlas.minFilter",
                expected.expectedMinificationFilter(),
                observed.font().minificationFilter(), "enum");
        compareEvidence(diagnostics, observed, expected, "font.atlas.magFilter",
                expected.expectedMagnificationFilter(),
                observed.font().magnificationFilter(), "enum");
        compare(diagnostics, observed, expected, "display.deviceScaleX",
                expected.expectedDeviceScaleX(), observed.display().deviceScaleX(),
                "ratio", null);
        compare(diagnostics, observed, expected, "display.deviceScaleY",
                expected.expectedDeviceScaleY(), observed.display().deviceScaleY(),
                "ratio", null);
        compareExpectedEvidence(diagnostics, observed, expected, "font.weight",
                expected.expectedWeight(), observed.font().weight(), "font-weight");
        compareExpectedEvidence(diagnostics, observed, expected, "font.letterSpacing",
                expected.expectedLetterSpacing(), observed.font().letterSpacing(), "logical-px");
    }

    private static void compareGeometryAndRaster(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected) {
        CoordinateBounds actualBounds =
                observed.geometry().inkBounds(CoordinateSpace.FRAMEBUFFER);
        CoordinateBounds expectedBounds = expected.expectedInkBounds();
        if (actualBounds.space() != expectedBounds.space()
                || maximumEdgeDifference(actualBounds, expectedBounds)
                        > expected.inkBoundsTolerance()) {
            add(diagnostics, observed, expected, "geometry.inkBounds",
                    expectedBounds, actualBounds, "px", expectedBounds.space().name());
        }
        double actualBaseline =
                observed.geometry().baseline(CoordinateSpace.FRAMEBUFFER).y();
        if (Math.abs(actualBaseline - expected.expectedBaseline())
                > expected.baselineTolerance()) {
            add(diagnostics, observed, expected, "geometry.baseline",
                    expected.expectedBaseline(), actualBaseline, "px",
                    CoordinateSpace.FRAMEBUFFER.name());
        }
        double residual = observed.geometry().transformResidual();
        if (residual > expected.transformTolerance()) {
            add(diagnostics, observed, expected, "geometry.transformResidual",
                    expected.transformTolerance(), residual, "px",
                    CoordinateSpace.FRAMEBUFFER.name());
        }
        if (observed.rasterResidual() > expected.rasterResidualThreshold()) {
            add(diagnostics, observed, expected, "rasterResidual",
                    expected.rasterResidualThreshold(), observed.rasterResidual(),
                    "mean-absolute-channel", CoordinateSpace.FRAMEBUFFER.name());
        }
    }

    private static <T> void compareEvidence(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected,
            String path,
            T expectedValue,
            EvidenceValue<T> actual,
            String units) {
        if (!actual.isAvailable()) {
            addUnavailable(diagnostics, observed, expected, path, expectedValue, actual, units);
        } else if (!expectedValue.equals(actual.value())) {
            add(diagnostics, observed, expected, path, expectedValue, actual.value(), units, null);
        }
    }

    private static <T> void compareExpectedEvidence(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected,
            String path,
            EvidenceValue<T> expectedValue,
            EvidenceValue<T> actual,
            String units) {
        if (!expectedValue.isAvailable()) {
            return;
        }
        compareEvidence(diagnostics, observed, expected, path, expectedValue.value(), actual, units);
    }

    private static <T> void addUnavailable(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected,
            String path,
            T expectedValue,
            EvidenceValue<T> actual,
            String units) {
        add(diagnostics, observed, expected, path, expectedValue,
                actual.unavailableReason().protocolValue(), units, null);
    }

    private static void compare(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected,
            String path,
            Object expectedValue,
            Object actualValue,
            String units,
            String coordinateSpace) {
        if (!Objects.equals(expectedValue, actualValue)) {
            add(diagnostics, observed, expected, path,
                    expectedValue, actualValue, units, coordinateSpace);
        }
    }

    private static void add(
            List<TypographyDiagnostic> diagnostics,
            TypographyObservation observed,
            TypographyExpectation expected,
            String path,
            Object expectedValue,
            Object actualValue,
            String units,
            String coordinateSpace) {
        diagnostics.add(new TypographyDiagnostic(
                observed.controlId(),
                path,
                String.valueOf(expectedValue),
                String.valueOf(actualValue),
                units,
                coordinateSpace,
                expected.referenceArtifactId(),
                observed.currentArtifactId()));
    }

    private static double maximumEdgeDifference(
            CoordinateBounds first, CoordinateBounds second) {
        double left = Math.abs(first.x() - second.x());
        double top = Math.abs(first.y() - second.y());
        double right = Math.abs(
                first.x() + first.width() - second.x() - second.width());
        double bottom = Math.abs(
                first.y() + first.height() - second.y() - second.height());
        return Math.max(Math.max(left, right), Math.max(top, bottom));
    }

    private static TypographyReport report(
            TypographyStatus status,
            TypographyObservation observation,
            List<TypographyDiagnostic> diagnostics) {
        List<String> controlledResults = diagnostics.stream()
                .map(value -> value.path() + ":" + value.expected() + "->" + value.observed())
                .toList();
        return new TypographyReport(
                "typography/v1",
                status,
                observation,
                diagnostics,
                observation.sourceMechanisms(),
                controlledResults,
                observation.unresolvedHypotheses());
    }
}
