package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.visual.ComparisonStatus;
import dev.gdx.uiharness.core.visual.InspectCaptureCompareRequest;
import dev.gdx.uiharness.core.visual.VisualComparator;
import dev.gdx.uiharness.core.visual.VisualMetrics;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class InspectCaptureCompareServiceTest {
    private static final MonotonicClock CLOCK = () -> 1;
    private static final VisualPolicy POLICY = VisualPolicy.pixelExactV1();

    @Test
    void acceptedFreshFullFrameCanConverge() {
        var service = service(
                CompletableFuture.completedFuture(image(2, 3)),
                snapshot(2, 3), reference(2, 2));

        var result = service.execute(
                request(), Deadline.after(CLOCK, Duration.ofSeconds(2)))
                .toCompletableFuture().join();

        assertEquals(ComparisonStatus.CONVERGED, result.status());
        assertEquals("session", result.current().sessionId());
        assertEquals("reference", result.reference().referenceId());
        assertEquals(1, result.iterations());
    }

    @Test
    void missingCaptureAndStaleRevisionCannotConverge() {
        var missing = service(
                CompletableFuture.failedFuture(new IllegalStateException("rejected")),
                snapshot(2, 3), reference(2, 2));
        var missingResult = missing.execute(
                request(), Deadline.after(CLOCK, Duration.ofSeconds(2)))
                .toCompletableFuture().join();
        assertEquals(ComparisonStatus.INCOMPLETE, missingResult.status());
        assertEquals("CURRENT_CAPTURE_REQUIRED",
                missingResult.diagnostics().getFirst().code());

        var stale = service(
                CompletableFuture.completedFuture(image(1, 2)),
                snapshot(2, 3), reference(2, 2));
        var staleResult = stale.execute(
                request(), Deadline.after(CLOCK, Duration.ofSeconds(2)))
                .toCompletableFuture().join();
        assertEquals(ComparisonStatus.STALE, staleResult.status());
        assertEquals("CAPTURE_REVISION_STALE",
                staleResult.diagnostics().getFirst().code());
    }

    @Test
    void incompatibleReferenceViewportFailsBeforeComparison() {
        var service = service(
                CompletableFuture.completedFuture(image(2, 3)),
                snapshot(2, 3), reference(3, 2));

        var result = service.execute(
                request(), Deadline.after(CLOCK, Duration.ofSeconds(2)))
                .toCompletableFuture().join();

        assertEquals(ComparisonStatus.INCOMPLETE, result.status());
        assertEquals("REFERENCE_VIEWPORT_INCOMPATIBLE",
                result.diagnostics().getFirst().code());
        assertNotNull(result.current());
    }

    @Test
    void crossApplicationReferenceFailsClosedBeforeCapture() {
        VisualReference reference = new VisualReference(
                "reference", "other-app", "golden", "main",
                new byte[] {1}, sha(new byte[] {1}), 2, 2,
                new CapturedImage.Scale(1, 1), Instant.EPOCH,
                snapshot(0, 0), null);
        var service = service(
                CompletableFuture.completedFuture(image(2, 3)),
                snapshot(2, 3), reference);

        var result = service.execute(
                request(), Deadline.after(CLOCK, Duration.ofSeconds(2)))
                .toCompletableFuture().join();

        assertEquals(ComparisonStatus.INCOMPLETE, result.status());
        assertEquals("REFERENCE_APPLICATION_MISMATCH",
                result.diagnostics().getFirst().code());
    }

    private static InspectCaptureCompareService service(
            CompletionStage<CapturedImage> capture,
            SemanticSnapshot snapshot,
            VisualReference reference) {
        Harness harness = new Harness() {
            @Override public CompletionStage<ActionResult> perform(
                    Locator locator, Action action, Deadline deadline) {
                return CompletableFuture.failedFuture(
                        new UnsupportedOperationException());
            }

            @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
                return CompletableFuture.completedFuture(snapshot);
            }
        };
        ScreenCapture screenCapture = new ScreenCapture() {
            @Override public CompletionStage<CapturedImage> capture(
                    CaptureRequest request, Deadline deadline) {
                assertEquals(Optional.empty(), request.actorLocator());
                return capture;
            }

            @Override public void close() {}
        };
        VisualComparator comparator = (expected, current, policy) ->
                new VisualComparator.Comparison(
                        new VisualMetrics(0, 0, 0), List.of());
        return new InspectCaptureCompareService(
                "session", "app", "main", harness, screenCapture, null,
                id -> "reference".equals(id) ? Optional.of(reference) : Optional.empty(),
                List.of(POLICY), comparator, CLOCK,
                InstantSource.fixed(Instant.EPOCH));
    }

    private static InspectCaptureCompareRequest request() {
        return new InspectCaptureCompareRequest(
                "reference", "pixel-exact", 1, "main", 1,
                Duration.ofSeconds(1), new CaptureRequest.Limits(10, 10, 100, 1024));
    }

    private static VisualReference reference(int width, int height) {
        byte[] png = {1};
        return new VisualReference(
                "reference", "app", "golden", "main", png, sha(png),
                width, height, new CapturedImage.Scale(1, 1), Instant.EPOCH,
                snapshot(0, 0), null);
    }

    private static CapturedImage image(long revision, long frame) {
        return new CapturedImage(
                new byte[] {1}, sha(new byte[] {1}), frame, revision,
                2, 2, new CapturedImage.Scale(1, 1));
    }

    private static SemanticSnapshot snapshot(long revision, long frame) {
        Bounds bounds = new Bounds(0, 0, 2, 2);
        SemanticNode root = new SemanticNode(
                "root", null, List.of(), Role.GROUP, "Root", null, null,
                null, "root", "Group", new SemanticState(
                        true, true, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        false, false, 1, false, true, true),
                bounds, bounds, bounds, 0, Map.of());
        return new SemanticSnapshot(revision, frame, root.id(), Map.of(root.id(), root));
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
