package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.typography.AffineTransformObservation;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinatePoint;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.DisplayObservation;
import dev.gdx.uiharness.core.typography.EvidenceValue;
import dev.gdx.uiharness.core.typography.FontObservation;
import dev.gdx.uiharness.core.typography.GlyphRunObservation;
import dev.gdx.uiharness.core.typography.TransformChain;
import dev.gdx.uiharness.core.typography.TypographyControlReference;
import dev.gdx.uiharness.core.typography.TypographyDiagnosticRequest;
import dev.gdx.uiharness.core.typography.TypographyGeometry;
import dev.gdx.uiharness.core.typography.TypographyObservation;
import dev.gdx.uiharness.core.typography.TypographyReference;
import dev.gdx.uiharness.core.typography.TypographyStatus;
import dev.gdx.uiharness.core.typography.UnavailableReason;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class TypographyDiagnosticServiceTest {
    @Test
    void capturesAndEvaluatesMatchingActorEvidence() {
        CapturedImage current = current();
        TypographyDiagnosticService service = service(
                current, Optional.of(reference()), observation(current));

        var result = service.execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(TypographyStatus.PIXEL_SHARP, result.status());
        assertEquals(1, result.reports().size());
        assertEquals("title", result.reports().getFirst().observation().controlId());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void missingReferenceFailsClosedWithoutCapturing() {
        boolean[] captured = {false};
        TypographyDiagnosticService service = new TypographyDiagnosticService(
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
                (reference, current, deadline) -> CompletableFuture.completedFuture(List.of()),
                new FixedClock());

        var result = service.execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(TypographyStatus.INCOMPLETE, result.status());
        assertEquals("REFERENCE_NOT_FOUND", result.diagnostics().getFirst().code());
        assertTrue(!captured[0]);
    }

    @Test
    void mismatchedCaptureDigestIsStale() {
        CapturedImage current = current();
        TypographyObservation stale = observation(current).withCaptureSha256(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        TypographyDiagnosticService service =
                service(current, Optional.of(reference()), stale);

        var result = service.execute(request(), deadline()).toCompletableFuture().join();

        assertEquals(TypographyStatus.STALE, result.status());
        assertEquals("CAPTURE_EVIDENCE_STALE", result.diagnostics().getFirst().code());
    }

    private static TypographyDiagnosticService service(
            CapturedImage current,
            Optional<TypographyReference> reference,
            TypographyObservation observation) {
        ScreenCapture capture = capture(current);
        return new TypographyDiagnosticService(
                "fixture-app",
                "main",
                capture,
                ignored -> reference,
                (registered, image, deadline) ->
                        CompletableFuture.completedFuture(List.of(observation)),
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

    private static TypographyDiagnosticRequest request() {
        return new TypographyDiagnosticRequest(
                "reference-title",
                "main",
                Duration.ofSeconds(1),
                8,
                CaptureRequest.Limits.defaults());
    }

    private static Deadline deadline() {
        return Deadline.after(new FixedClock(), Duration.ofSeconds(1));
    }

    private static TypographyReference reference() {
        return new TypographyReference(
                "reference-title",
                "fixture-app",
                "main",
                "reference-artifact",
                new byte[] {1},
                "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a",
                2,
                2,
                new CapturedImage.Scale(1, 1),
                List.of(new TypographyControlReference(
                        "title",
                        "font.fnt",
                        15,
                        15,
                        1,
                        1,
                        "Nearest",
                        "Nearest",
                        1,
                        1,
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED, "weight unavailable"),
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED, "spacing unavailable"),
                        bounds().getLast(),
                        1,
                        1,
                        0.5,
                        1e-6,
                        0.5,
                        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")));
    }

    private static CapturedImage current() {
        return new CapturedImage(
                new byte[] {2},
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                3,
                2,
                2,
                2,
                new CapturedImage.Scale(1, 1));
    }

    private static TypographyObservation observation(CapturedImage current) {
        TransformChain transforms = new TransformChain(
                AffineTransformObservation.identity(
                        CoordinateSpace.LOCAL, CoordinateSpace.PARENT),
                AffineTransformObservation.identity(
                        CoordinateSpace.PARENT, CoordinateSpace.STAGE),
                AffineTransformObservation.identity(
                        CoordinateSpace.STAGE, CoordinateSpace.SCREEN),
                AffineTransformObservation.identity(
                        CoordinateSpace.SCREEN, CoordinateSpace.FRAMEBUFFER));
        return new TypographyObservation(
                "typography/v1",
                "title",
                "n1",
                "A",
                0,
                1,
                List.of(new GlyphRunObservation(
                        0,
                        1,
                        "A",
                        new CoordinatePoint(CoordinateSpace.LOCAL, 0, 0),
                        new CoordinatePoint(CoordinateSpace.LOCAL, 0, 1),
                        bounds().getFirst())),
                current.revision(),
                current.frame(),
                "capture:" + current.sha256(),
                current.sha256(),
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                new FontObservation(
                        EvidenceValue.available("font.fnt"),
                        List.of("font.png"),
                        EvidenceValue.available(15.0),
                        EvidenceValue.available(15.0),
                        15,
                        15,
                        1,
                        1,
                        EvidenceValue.available("Nearest"),
                        EvidenceValue.available("Nearest"),
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED, "distance field unavailable"),
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED, "weight unavailable"),
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED, "spacing unavailable")),
                new DisplayObservation("fixture-app", "main", 2, 2, 2, 2, 2, 2, 1, 1),
                transforms,
                new TypographyGeometry(points(0), points(1), bounds(), bounds(), 0, 0),
                0,
                List.of("font-source=font.fnt"),
                List.of());
    }

    private static List<CoordinatePoint> points(double y) {
        return List.of(
                new CoordinatePoint(CoordinateSpace.LOCAL, 0, y),
                new CoordinatePoint(CoordinateSpace.STAGE, 0, y),
                new CoordinatePoint(CoordinateSpace.SCREEN, 0, y),
                new CoordinatePoint(CoordinateSpace.FRAMEBUFFER, 0, y));
    }

    private static List<CoordinateBounds> bounds() {
        return List.of(
                new CoordinateBounds(CoordinateSpace.LOCAL, 0, 0, 1, 1),
                new CoordinateBounds(CoordinateSpace.STAGE, 0, 0, 1, 1),
                new CoordinateBounds(CoordinateSpace.SCREEN, 0, 0, 1, 1),
                new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 1, 1));
    }

    private static final class FixedClock implements MonotonicClock {
        @Override public long nanoTime() {
            return 0;
        }
    }
}
