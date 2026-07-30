package dev.gdx.uiharness.core.typography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TypographyEvaluatorTest {
    private final TypographyEvaluator evaluator = new TypographyEvaluator();

    @Test
    void acceptsMatchingSharpEvidence() {
        TypographyReport report = evaluator.evaluate(observation(), expectation());

        assertEquals(TypographyStatus.PIXEL_SHARP, report.status());
        assertTrue(report.diagnostics().isEmpty());
        assertEquals(List.of("bitmap-scale=2.8"), report.sourceMechanisms());
        assertEquals(List.of("magnification may soften glyph edges"), report.unresolvedHypotheses());
    }

    @Test
    void blocksAChangedSettingWithAttributedExpectedAndObservedValues() {
        TypographyObservation changed = observation().withFont(
                observation().font().withMagnificationFilter(
                        EvidenceValue.available("Linear")));

        TypographyReport report = evaluator.evaluate(changed, expectation());

        assertEquals(TypographyStatus.NOT_PIXEL_SHARP, report.status());
        TypographyDiagnostic diagnostic = report.diagnostics().getFirst();
        assertEquals("title", diagnostic.controlId());
        assertEquals("font.atlas.magFilter", diagnostic.path());
        assertEquals("Nearest", diagnostic.expected());
        assertEquals("Linear", diagnostic.observed());
        assertEquals("enum", diagnostic.units());
        assertEquals("reference-title", diagnostic.referenceArtifactId());
        assertEquals("current-title", diagnostic.currentArtifactId());
    }

    @Test
    void unavailableRequiredEvidenceFailsClosed() {
        TypographyObservation incomplete = observation().withFont(
                observation().font().withWeight(
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED,
                                "BitmapFont does not expose weight")));
        TypographyExpectation requiringWeight = expectation().withExpectedWeight(
                EvidenceValue.available(400.0));

        TypographyReport report = evaluator.evaluate(incomplete, requiringWeight);

        assertEquals(TypographyStatus.NOT_DIAGNOSABLE, report.status());
        assertEquals("font.weight", report.diagnostics().getFirst().path());
        assertEquals("unsupported", report.diagnostics().getFirst().observed());
    }

    @Test
    void mismatchedFrameOrRevisionIsStale() {
        TypographyExpectation stale = expectation().withFrame(18).withRevision(11);

        TypographyReport report = evaluator.evaluate(observation(), stale);

        assertEquals(TypographyStatus.STALE, report.status());
        assertTrue(report.diagnostics().stream()
                .anyMatch(value -> value.path().equals("frame")));
        assertTrue(report.diagnostics().stream()
                .anyMatch(value -> value.path().equals("revision")));
    }

    @Test
    void changingTransformsAreNotStable() {
        TypographyExpectation expected = expectation().withExpectedTransformSha256(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        TypographyReport report = evaluator.evaluate(observation(), expected);

        assertEquals(TypographyStatus.NOT_STABLE, report.status());
        assertEquals("transformSha256", report.diagnostics().getFirst().path());
    }

    @Test
    void rejectsInvalidAvailableAndUnavailableShapes() {
        assertThrows(NullPointerException.class, () -> EvidenceValue.available(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvidenceValue<>(
                        Availability.AVAILABLE,
                        "value",
                        UnavailableReason.UNSUPPORTED,
                        "reason"));
    }

    @Test
    void affineMappingReportsScaleRotationShearAndFractionalTranslation() {
        AffineTransformObservation transform = AffineTransformObservation.fromMatrix(
                CoordinateSpace.LOCAL,
                CoordinateSpace.STAGE,
                0,
                -3,
                10.5,
                2,
                1,
                20.25);

        assertEquals(2, transform.effectiveScaleX(), 1e-9);
        assertEquals(3, transform.effectiveScaleY(), 1e-9);
        assertEquals(90, transform.rotationDegrees(), 1e-9);
        assertEquals(0.5, transform.shear(), 1e-9);
        assertEquals(0.5, transform.fractionalTranslationX(), 1e-9);
        assertEquals(0.25, transform.fractionalTranslationY(), 1e-9);
        assertTrue(transform.invertible());
    }

    @Test
    void controlledMatrixAttributesExactlyTheOneChangedInput() {
        TypographyObservation control = observation();
        List<ControlledCase> cases = List.of(
                changed("font.sourceId",
                        control.withFont(control.font().withSourceId(
                                EvidenceValue.available("font/other.fnt")))),
                changed("font.nominalSize",
                        control.withFont(control.font().withNominalSize(
                                EvidenceValue.available(16.0)))),
                changed("font.generatedGlyphSize",
                        control.withFont(control.font().withGeneratedGlyphSize(
                                EvidenceValue.available(16.0)))),
                changed("font.bitmapScaleX",
                        control.withFont(control.font().withBitmapScale(3.0, 2.8))),
                changed("font.atlas.minFilter",
                        control.withFont(control.font().withMinificationFilter(
                                EvidenceValue.available("Linear")))),
                changed("font.atlas.magFilter",
                        control.withFont(control.font().withMagnificationFilter(
                                EvidenceValue.available("Linear")))),
                changed("display.deviceScaleX",
                        control.withDisplay(new DisplayObservation(
                                "fixture-app", "initial-1280x720",
                                1280, 720, 1280, 720, 2560, 720, 2, 1))),
                changed("geometry.transformResidual",
                        control.withGeometry(geometryWithResidual(0.5, 0))),
                changed("geometry.baseline",
                        control.withGeometry(geometryWithBaseline(61))),
                changed("font.weight",
                        control.withFont(control.font().withWeight(
                                EvidenceValue.available(500.0)))),
                changed("font.letterSpacing",
                        control.withFont(control.font().withLetterSpacing(
                                EvidenceValue.available(1.0)))),
                changed("rasterResidual", control.withRasterResidual(0.6)));

        for (ControlledCase changed : cases) {
            TypographyReport report = evaluator.evaluate(changed.observation(), expectation());
            assertEquals(TypographyStatus.NOT_PIXEL_SHARP, report.status(), changed.path());
            assertEquals(List.of(changed.path()),
                    report.diagnostics().stream().map(TypographyDiagnostic::path).toList(),
                    changed.path());
            TypographyDiagnostic diagnostic = report.diagnostics().getFirst();
            assertEquals("title", diagnostic.controlId());
            assertEquals("reference-title", diagnostic.referenceArtifactId());
            assertEquals("current-title", diagnostic.currentArtifactId());
        }
    }

    @Test
    void nonInvertibleTransformFailsClosed() {
        TransformChain nonInvertible = new TransformChain(
                AffineTransformObservation.fromMatrix(
                        CoordinateSpace.LOCAL, CoordinateSpace.PARENT,
                        0, 0, 0, 0, 0, 0),
                AffineTransformObservation.identity(
                        CoordinateSpace.PARENT, CoordinateSpace.STAGE),
                AffineTransformObservation.identity(
                        CoordinateSpace.STAGE, CoordinateSpace.SCREEN),
                AffineTransformObservation.identity(
                        CoordinateSpace.SCREEN, CoordinateSpace.FRAMEBUFFER));

        TypographyReport report = evaluator.evaluate(
                observation().withTransforms(
                        nonInvertible,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                expectation());

        assertEquals(TypographyStatus.NOT_DIAGNOSABLE, report.status());
        assertEquals("transforms.invertible", report.diagnostics().getFirst().path());
        assertEquals("non-invertible", report.diagnostics().getFirst().observed());
    }

    @Test
    void retainedALikeBitmapMagnificationIsBlockingAgainstUnscaledReference() {
        TypographyReport report = evaluator.evaluate(
                observation(), expectation().withExpectedBitmapScale(1, 1));

        assertEquals(TypographyStatus.NOT_PIXEL_SHARP, report.status());
        assertEquals(List.of("font.bitmapScaleX", "font.bitmapScaleY"),
                report.diagnostics().stream().map(TypographyDiagnostic::path).toList());
        assertTrue(report.diagnostics().stream()
                .allMatch(value -> value.observed().equals("2.8")));
    }

    private static TypographyObservation observation() {
        FontObservation font = new FontObservation(
                EvidenceValue.available("classpath:reference-ui/lsans-15.fnt"),
                List.of("classpath:reference-ui/lsans-15.png"),
                EvidenceValue.available(15.0),
                EvidenceValue.available(15.0),
                42.0,
                42.0,
                2.8,
                2.8,
                EvidenceValue.available("Nearest"),
                EvidenceValue.available("Nearest"),
                EvidenceValue.unavailable(
                        UnavailableReason.UNSUPPORTED,
                        "bitmap font has no distance-field smoothing"),
                EvidenceValue.available(400.0),
                EvidenceValue.available(0.0));
        DisplayObservation display = new DisplayObservation(
                "fixture-app", "initial-1280x720", 1280, 720, 1280, 720, 1280, 720, 1, 1);
        TypographyGeometry geometry = new TypographyGeometry(
                points(40, 650, 40, 70),
                points(40, 660, 40, 60),
                bounds(40, 40, 220, 48),
                bounds(40, 42, 218, 44),
                0,
                0);
        return new TypographyObservation(
                "typography/v1",
                "title",
                "n4",
                "SKIRMISH",
                0,
                8,
                List.of(new GlyphRunObservation(
                        0,
                        8,
                        "SKIRMISH",
                        new CoordinatePoint(CoordinateSpace.LOCAL, 0, 0),
                        new CoordinatePoint(CoordinateSpace.LOCAL, 0, 0),
                        new CoordinateBounds(CoordinateSpace.LOCAL, 0, 0, 218, 44))),
                10,
                17,
                "current-title",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                font,
                display,
                transformChain(),
                geometry,
                0.4,
                List.of("bitmap-scale=2.8"),
                List.of("magnification may soften glyph edges"));
    }

    private static TransformChain transformChain() {
        return new TransformChain(
                AffineTransformObservation.identity(
                        CoordinateSpace.LOCAL, CoordinateSpace.PARENT),
                AffineTransformObservation.identity(
                        CoordinateSpace.PARENT, CoordinateSpace.STAGE),
                AffineTransformObservation.identity(
                        CoordinateSpace.STAGE, CoordinateSpace.SCREEN),
                AffineTransformObservation.identity(
                        CoordinateSpace.SCREEN, CoordinateSpace.FRAMEBUFFER));
    }

    private static List<CoordinatePoint> points(
            double stageX, double stageY, double framebufferX, double framebufferY) {
        return List.of(
                new CoordinatePoint(CoordinateSpace.LOCAL, 0, 0),
                new CoordinatePoint(CoordinateSpace.STAGE, stageX, stageY),
                new CoordinatePoint(CoordinateSpace.SCREEN, framebufferX, framebufferY),
                new CoordinatePoint(
                        CoordinateSpace.FRAMEBUFFER, framebufferX, framebufferY));
    }

    private static List<CoordinateBounds> bounds(
            double x, double y, double width, double height) {
        return List.of(
                new CoordinateBounds(CoordinateSpace.LOCAL, 0, 0, width, height),
                new CoordinateBounds(CoordinateSpace.STAGE, x, 600 - y, width, height),
                new CoordinateBounds(CoordinateSpace.SCREEN, x, y, width, height),
                new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, x, y, width, height));
    }

    private static TypographyGeometry geometryWithResidual(double x, double y) {
        TypographyGeometry source = observation().geometry();
        return new TypographyGeometry(
                source.origins(), source.baselines(), source.layoutBounds(),
                source.inkBounds(), x, y);
    }

    private static TypographyGeometry geometryWithBaseline(double framebufferY) {
        TypographyGeometry source = observation().geometry();
        List<CoordinatePoint> baselines = source.baselines().stream()
                .map(point -> point.space() == CoordinateSpace.FRAMEBUFFER
                        ? new CoordinatePoint(point.space(), point.x(), framebufferY)
                        : point)
                .toList();
        return new TypographyGeometry(
                source.origins(), baselines, source.layoutBounds(),
                source.inkBounds(), source.fractionalTranslationX(),
                source.fractionalTranslationY());
    }

    private static ControlledCase changed(String path, TypographyObservation observation) {
        return new ControlledCase(path, observation);
    }

    private record ControlledCase(String path, TypographyObservation observation) {}

    private static TypographyExpectation expectation() {
        return new TypographyExpectation(
                "title",
                "fixture-app",
                "initial-1280x720",
                10,
                17,
                "reference-title",
                "current-title",
                "classpath:reference-ui/lsans-15.fnt",
                15,
                15,
                2.8,
                2.8,
                "Nearest",
                "Nearest",
                1,
                1,
                EvidenceValue.available(400.0),
                EvidenceValue.available(0.0),
                new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 40, 42, 218, 44),
                60,
                1,
                0.5,
                1e-6,
                0.5,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }
}
