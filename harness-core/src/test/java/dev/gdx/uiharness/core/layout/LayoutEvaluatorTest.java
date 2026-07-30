package dev.gdx.uiharness.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.typography.AffineTransformObservation;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.DisplayObservation;
import dev.gdx.uiharness.core.typography.TransformChain;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LayoutEvaluatorTest {
    private final LayoutEvaluator evaluator = new LayoutEvaluator();

    @Test
    void contentWhollyInsideItsInternalClipIsConformant() {
        LayoutReport report = evaluator.evaluate(observation(bounds(20, 20, 100, 40)),
                expectation(bounds(20, 20, 100, 40), null));

        assertEquals(LayoutStatus.CONFORMANT, report.status());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void intentionallyClippedScrollableContentIsConformant() {
        LayoutObservation clipped = observation(bounds(20, 0, 100, 40));
        LayoutExpectation base = expectation(bounds(20, 0, 100, 40), null);
        CoordinateBounds expectedVisible = bounds(20, 10, 100, 30);
        LayoutExpectation expected = new LayoutExpectation(
                base.controlId(), base.applicationId(), base.viewportId(),
                base.revision(), base.frame(), base.layoutRevision(),
                base.referenceArtifactId(), base.currentArtifactId(),
                base.expectedParentActorId(), base.expectedLayoutOwnerId(),
                base.expectedScrollOwnerId(), base.expectedClipOwnerId(),
                base.expectedLayoutRole(), base.expectedBounds(), expectedVisible,
                base.expectedPadding(), base.boundsTolerance(), base.paddingTolerance(),
                base.relationship(), base.expectedLayoutSha256());

        LayoutReport report = evaluator.evaluate(clipped, expected);

        assertEquals(LayoutStatus.CONFORMANT, report.status());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void oneFramebufferPixelInternalCutIsBlockingWhenFrameEdgeIsUntouched() {
        LayoutObservation cut = observation(bounds(20, 9, 100, 40));

        LayoutReport report = evaluator.evaluate(
                cut, expectation(bounds(20, 20, 100, 40), null));

        assertEquals(LayoutStatus.NON_CONFORMANT, report.status());
        assertTrue(report.diagnostics().stream()
                .anyMatch(value -> value.path().equals("internalClip.intersection")));
        LayoutDiagnostic clip = report.diagnostics().stream()
                .filter(value -> value.path().equals("internalClip.intersection"))
                .findFirst()
                .orElseThrow();
        assertEquals("row", clip.controlId());
        assertEquals("FRAMEBUFFER", clip.coordinateSpace());
        assertEquals("reference-layout", clip.referenceArtifactId());
        assertEquals("current-layout", clip.currentArtifactId());
    }

    @Test
    void missingExpectedClipOwnerFailsClosed() {
        LayoutObservation missing = new LayoutObservation(
                "layout/v1", "row", "n4", "n3", "form",
                "scroll", null, "scrolling-row",
                4, 8, 12, "current-layout", "a".repeat(64), "b".repeat(64),
                display(), transforms(), spaces(bounds(20, 20, 100, 40)),
                new LayoutPadding(4, 4, 4, 4), List.of(),
                bounds(20, 20, 100, 40),
                scroll());

        LayoutReport report = evaluator.evaluate(
                missing, expectation(bounds(20, 20, 100, 40), null));

        assertEquals(LayoutStatus.INCOMPLETE, report.status());
        assertEquals("clipOwnerId", report.diagnostics().getFirst().path());
        assertEquals("missing", report.diagnostics().getFirst().observed());
    }

    @Test
    void dSurfaceFixtureReportsMissingClipOwnerAndLeakedIntersection() {
        assertMissingClipLeak("surface", "custom-surface-row");
    }

    @Test
    void eCanvasFixtureReportsMissingClipOwnerAndLeakedIntersection() {
        assertMissingClipLeak("canvas", "custom-canvas-row");
    }

    @Test
    void onePixelHeaderBodyDriftNamesTheRelationship() {
        LayoutObservation header = observationFor(
                "header", "n2", bounds(20, 20, 300, 50), "persistent-header");
        LayoutObservation body = observationFor(
                "body", "n3", bounds(21, 80, 300, 300), "scrolling-body");
        LayoutExpectation headerExpected = expectationFor(
                "header", bounds(20, 20, 300, 50), "persistent-header", null);
        LayoutExpectation bodyExpected = expectationFor(
                "body", bounds(21, 80, 300, 300), "scrolling-body",
                new LayoutRelationship("header", 0, 60, 0));

        List<LayoutReport> reports = evaluator.evaluate(
                List.of(header, body), List.of(headerExpected, bodyExpected));

        LayoutReport bodyReport = reports.getLast();
        assertEquals(LayoutStatus.NON_CONFORMANT, bodyReport.status());
        assertEquals("relationship.framebufferOrigin",
                bodyReport.diagnostics().getFirst().path());
        assertEquals("1.0,60.0", bodyReport.diagnostics().getFirst().observed());
    }

    @Test
    void exact1280ViewportGeometryRejectsOnePixelWidthDrift() {
        LayoutObservation observed = observation(bounds(0, 0, 1279, 720));

        LayoutReport report = evaluator.evaluate(
                observed, expectation(bounds(0, 0, 1280, 720), null));

        assertEquals(LayoutStatus.NON_CONFORMANT, report.status());
        assertTrue(report.diagnostics().stream()
                .anyMatch(value -> value.path().equals("bounds.framebuffer")));
    }

    @Test
    void nonInvertibleMappingIsNotDiagnosable() {
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
        LayoutObservation observed = observation(bounds(20, 20, 100, 40));
        observed = new LayoutObservation(
                observed.schemaVersion(), observed.controlId(), observed.actorId(),
                observed.parentActorId(), observed.layoutOwnerId(),
                observed.scrollOwnerId(), observed.observedClipOwnerId(),
                observed.layoutRole(), observed.revision(), observed.frame(),
                observed.layoutRevision(), observed.currentArtifactId(),
                observed.captureSha256(), observed.layoutSha256(), observed.display(),
                nonInvertible, observed.bounds(), observed.padding(), observed.clipChain(),
                observed.visibleIntersection(), observed.scroll());

        LayoutReport report = evaluator.evaluate(
                observed, expectation(bounds(20, 20, 100, 40), null));

        assertEquals(LayoutStatus.NOT_DIAGNOSABLE, report.status());
        assertEquals("transforms.invertible", report.diagnostics().getFirst().path());
    }

    private static LayoutObservation observation(CoordinateBounds framebufferBounds) {
        return observationFor("row", "n4", framebufferBounds, "scrolling-row");
    }

    private void assertMissingClipLeak(String controlId, String role) {
        LayoutObservation value = observationFor(
                controlId, "custom-actor", bounds(20, 0, 100, 40), role);
        LayoutObservation missing = new LayoutObservation(
                value.schemaVersion(), value.controlId(), value.actorId(),
                value.parentActorId(), value.layoutOwnerId(), null, null,
                value.layoutRole(), value.revision(), value.frame(),
                value.layoutRevision(), value.currentArtifactId(),
                value.captureSha256(), value.layoutSha256(), value.display(),
                value.transforms(), value.bounds(), value.padding(), List.of(),
                bounds(20, 0, 100, 40), value.scroll());
        LayoutExpectation base = expectationFor(
                controlId, bounds(20, 0, 100, 40), role, null);
        LayoutExpectation expected = new LayoutExpectation(
                base.controlId(), base.applicationId(), base.viewportId(),
                base.revision(), base.frame(), base.layoutRevision(),
                base.referenceArtifactId(), base.currentArtifactId(),
                base.expectedParentActorId(), base.expectedLayoutOwnerId(),
                base.expectedScrollOwnerId(), base.expectedClipOwnerId(),
                base.expectedLayoutRole(), base.expectedBounds(),
                bounds(20, 10, 100, 30), base.expectedPadding(),
                base.boundsTolerance(), base.paddingTolerance(),
                base.relationship(), base.expectedLayoutSha256());

        LayoutReport report = evaluator.evaluate(missing, expected);

        assertEquals(LayoutStatus.INCOMPLETE, report.status());
        assertTrue(report.diagnostics().stream()
                .anyMatch(value2 -> value2.path().equals("clipOwnerId")));
        assertTrue(report.diagnostics().stream()
                .anyMatch(value2 -> value2.path().equals("internalClip.intersection")));
    }

    private static LayoutObservation observationFor(
            String controlId,
            String actorId,
            CoordinateBounds framebufferBounds,
            String role) {
        CoordinateBounds clipBounds = bounds(10, 10, 400, 400);
        return new LayoutObservation(
                "layout/v1", controlId, actorId, "n3", "form",
                "scroll", "scroll", role,
                4, 8, 12, "current-layout", "a".repeat(64), "b".repeat(64),
                display(), transforms(), spaces(framebufferBounds),
                new LayoutPadding(4, 4, 4, 4),
                List.of(new LayoutClip(
                        "scroll",
                        stage(clipBounds),
                        screen(clipBounds),
                        clipBounds)),
                intersection(framebufferBounds, clipBounds),
                scroll());
    }

    private static LayoutExpectation expectation(
            CoordinateBounds framebufferBounds, LayoutRelationship relationship) {
        return expectationFor(
                "row", framebufferBounds, "scrolling-row", relationship);
    }

    private static LayoutExpectation expectationFor(
            String controlId,
            CoordinateBounds framebufferBounds,
            String role,
            LayoutRelationship relationship) {
        return new LayoutExpectation(
                controlId, "fixture-app", "bottom-1920x1080",
                4, 8, 12, "reference-layout", "current-layout",
                "n3", "form", "scroll", "scroll", role,
                spaces(framebufferBounds), framebufferBounds,
                new LayoutPadding(4, 4, 4, 4),
                0, 0, relationship, "b".repeat(64));
    }

    private static LayoutScroll scroll() {
        return new LayoutScroll(
                0, 300, 0, 300,
                bounds(10, 10, 400, 400),
                bounds(10, -290, 400, 700),
                false);
    }

    private static DisplayObservation display() {
        return new DisplayObservation(
                "fixture-app", "bottom-1920x1080",
                1920, 1080, 1920, 1080, 1920, 1080, 1, 1);
    }

    private static TransformChain transforms() {
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

    private static List<CoordinateBounds> spaces(CoordinateBounds framebuffer) {
        return List.of(
                new CoordinateBounds(
                        CoordinateSpace.LOCAL,
                        framebuffer.x(), framebuffer.y(),
                        framebuffer.width(), framebuffer.height()),
                stage(framebuffer),
                screen(framebuffer),
                framebuffer);
    }

    private static CoordinateBounds stage(CoordinateBounds value) {
        return new CoordinateBounds(
                CoordinateSpace.STAGE,
                value.x(), value.y(), value.width(), value.height());
    }

    private static CoordinateBounds screen(CoordinateBounds value) {
        return new CoordinateBounds(
                CoordinateSpace.SCREEN,
                value.x(), value.y(), value.width(), value.height());
    }

    private static CoordinateBounds bounds(double x, double y, double width, double height) {
        return new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, x, y, width, height);
    }

    private static CoordinateBounds intersection(
            CoordinateBounds first, CoordinateBounds second) {
        double x = Math.max(first.x(), second.x());
        double y = Math.max(first.y(), second.y());
        double right = Math.min(
                first.x() + first.width(), second.x() + second.width());
        double bottom = Math.min(
                first.y() + first.height(), second.y() + second.height());
        return bounds(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }
}
