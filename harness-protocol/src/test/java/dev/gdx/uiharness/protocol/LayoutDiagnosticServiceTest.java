package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.layout.LayoutClip;
import dev.gdx.uiharness.core.layout.LayoutControlReference;
import dev.gdx.uiharness.core.layout.LayoutDiagnosticRequest;
import dev.gdx.uiharness.core.layout.LayoutEvidence;
import dev.gdx.uiharness.core.layout.LayoutObservation;
import dev.gdx.uiharness.core.layout.LayoutPadding;
import dev.gdx.uiharness.core.layout.LayoutQuiescenceResult;
import dev.gdx.uiharness.core.layout.LayoutReference;
import dev.gdx.uiharness.core.layout.LayoutScroll;
import dev.gdx.uiharness.core.layout.LayoutStabilitySample;
import dev.gdx.uiharness.core.layout.LayoutStatus;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.typography.AffineTransformObservation;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.DisplayObservation;
import dev.gdx.uiharness.core.typography.TransformChain;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class LayoutDiagnosticServiceTest {
    @Test
    void evaluatesOnlyCaptureBoundQuiescentEvidence() {
        CapturedImage current = current();
        LayoutDiagnosticService service = service(
                current, Optional.of(reference()), evidence(observation(current)));

        var result = service.execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(LayoutStatus.CONFORMANT, result.status());
        assertEquals("row", result.reports().getFirst().observation().controlId());
        assertTrue(result.settling().settled());
        assertTrue(result.captures().settled());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void missingReferenceFailsClosedWithoutCapturing() {
        boolean[] captured = {false};
        LayoutDiagnosticService service = new LayoutDiagnosticService(
                "fixture-app",
                "main",
                new ScreenCapture() {
                    @Override public CompletionStage<CapturedImage> capture(
                            CaptureRequest request, Deadline deadline) {
                        captured[0] = true;
                        return CompletableFuture.completedFuture(current());
                    }

                    @Override public void close() {}
                },
                ignored -> Optional.empty(),
                (reference, current, deadline) ->
                        CompletableFuture.completedFuture(evidence(observation(current))),
                new FixedClock());

        var result = service.execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(LayoutStatus.INCOMPLETE, result.status());
        assertEquals("REFERENCE_NOT_FOUND", result.diagnostics().getFirst().code());
        assertFalse(captured[0]);
    }

    @Test
    void mismatchedCaptureDigestIsStale() {
        CapturedImage current = current();
        LayoutObservation value = observation(current);
        LayoutObservation stale = new LayoutObservation(
                value.schemaVersion(), value.controlId(), value.actorId(), value.parentActorId(),
                value.layoutOwnerId(), value.scrollOwnerId(), value.observedClipOwnerId(),
                value.layoutRole(), value.revision(), value.frame(), value.layoutRevision(),
                value.currentArtifactId(), "b".repeat(64), value.layoutSha256(),
                value.display(), value.transforms(), value.bounds(), value.padding(),
                value.clipChain(), value.visibleIntersection(), value.scroll());

        var result = service(
                        current, Optional.of(reference()), evidence(stale))
                .execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(LayoutStatus.STALE, result.status());
        assertEquals("CAPTURE_EVIDENCE_STALE", result.diagnostics().getFirst().code());
    }

    @Test
    void cFixtureMissingPersistentHeaderCannotPassGlobalFrameCheck() {
        CapturedImage current = current();
        LayoutReference cReference = new LayoutReference(
                "reference-layout",
                "fixture-app",
                "main",
                "reference-artifact",
                List.of(control("row"), control("persistent-title")));

        var result = service(
                        current, Optional.of(cReference), evidence(observation(current)))
                .execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(LayoutStatus.INCOMPLETE, result.status());
        assertEquals(
                "CONTROL_EVIDENCE_MISSING",
                result.diagnostics().stream()
                        .filter(value -> value.path().endsWith("persistent-title"))
                        .findFirst()
                        .orElseThrow()
                        .code());
        assertTrue(result.reports().isEmpty());
    }

    @Test
    void unsettledSmoothScrollReturnsNotStableWithoutEvaluation() {
        CapturedImage current = current();
        LayoutQuiescenceResult moving = new LayoutQuiescenceResult(
                false, "not-stable", 1, Duration.ofSeconds(2), List.of());
        LayoutEvidence evidence = new LayoutEvidence(
                List.of(observation(current)), moving, quiescence(5, 4));

        var result = service(current, Optional.of(reference()), evidence)
                .execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(LayoutStatus.NOT_STABLE, result.status());
        assertTrue(result.reports().isEmpty());
        assertEquals("LAYOUT_NOT_QUIESCENT", result.diagnostics().getFirst().code());
    }

    private static LayoutDiagnosticService service(
            CapturedImage current,
            Optional<LayoutReference> reference,
            LayoutEvidence evidence) {
        return new LayoutDiagnosticService(
                "fixture-app",
                "main",
                capture(current),
                ignored -> reference,
                (registered, image, deadline) -> CompletableFuture.completedFuture(evidence),
                new FixedClock());
    }

    private static ScreenCapture capture(CapturedImage image) {
        return new ScreenCapture() {
            @Override public CompletionStage<CapturedImage> capture(
                    CaptureRequest request, Deadline deadline) {
                return CompletableFuture.completedFuture(image);
            }

            @Override public void close() {}
        };
    }

    private static LayoutDiagnosticRequest request() {
        return new LayoutDiagnosticRequest(
                "reference-layout", "main", Duration.ofSeconds(1), 8,
                CaptureRequest.fullWindow());
    }

    private static LayoutEvidence evidence(LayoutObservation observation) {
        return new LayoutEvidence(
                List.of(observation),
                quiescence(3, 1),
                quiescence(5, 4));
    }

    private static LayoutQuiescenceResult quiescence(int count, long firstFrame) {
        List<LayoutStabilitySample> samples = java.util.stream.LongStream
                .range(firstFrame, firstFrame + count)
                .mapToObj(LayoutDiagnosticServiceTest::sample)
                .toList();
        return new LayoutQuiescenceResult(
                true, "settled", count, Duration.ofMillis(50), samples);
    }

    private static LayoutStabilitySample sample(long frame) {
        return new LayoutStabilitySample(
                frame, 3, 7, 0, 0, 0, 0,
                "viewport", "content", "clip", "layout", "framebuffer", false);
    }

    private static LayoutReference reference() {
        return new LayoutReference(
                "reference-layout",
                "fixture-app",
                "main",
                "reference-artifact",
                List.of(control("row")));
    }

    private static LayoutControlReference control(String controlId) {
        return new LayoutControlReference(
                controlId, "parent", "form", "scroll", "scroll", "scrolling-row",
                spaces(framebufferBounds()), framebufferBounds(),
                new LayoutPadding(4, 4, 4, 4),
                0, 0, null, "d".repeat(64));
    }

    private static LayoutObservation observation(CapturedImage current) {
        CoordinateBounds framebuffer = framebufferBounds();
        CoordinateBounds clip = new CoordinateBounds(
                CoordinateSpace.FRAMEBUFFER, 0, 0, 2, 2);
        return new LayoutObservation(
                "layout/v1", "row", "actor", "parent", "form", "scroll", "scroll",
                "scrolling-row", current.revision(), current.frame(), 7,
                "capture:" + current.sha256(), current.sha256(), "d".repeat(64),
                new DisplayObservation(
                        "fixture-app", "main", 2, 2, 2, 2, 2, 2, 1, 1),
                transforms(), spaces(framebuffer), new LayoutPadding(4, 4, 4, 4),
                List.of(new LayoutClip(
                        "scroll",
                        withSpace(clip, CoordinateSpace.STAGE),
                        withSpace(clip, CoordinateSpace.SCREEN),
                        clip)),
                framebuffer,
                new LayoutScroll(0, 0, 0, 0, clip, clip, false));
    }

    private static CapturedImage current() {
        return new CapturedImage(
                new byte[] {2}, "a".repeat(64), 3, 2, 2, 2,
                new CapturedImage.Scale(1, 1));
    }

    private static CoordinateBounds framebufferBounds() {
        return new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 1, 1);
    }

    private static List<CoordinateBounds> spaces(CoordinateBounds value) {
        return List.of(
                withSpace(value, CoordinateSpace.LOCAL),
                withSpace(value, CoordinateSpace.STAGE),
                withSpace(value, CoordinateSpace.SCREEN),
                value);
    }

    private static CoordinateBounds withSpace(
            CoordinateBounds value, CoordinateSpace space) {
        return new CoordinateBounds(
                space, value.x(), value.y(), value.width(), value.height());
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

    private static Deadline deadline() {
        return Deadline.after(new FixedClock(), Duration.ofSeconds(1));
    }

    private static final class FixedClock implements MonotonicClock {
        @Override public long nanoTime() {
            return 0;
        }
    }
}
