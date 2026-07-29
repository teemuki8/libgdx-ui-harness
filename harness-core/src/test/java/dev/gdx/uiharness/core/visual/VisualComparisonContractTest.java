package dev.gdx.uiharness.core.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class VisualComparisonContractTest {
    @Test
    void preservesEvidenceIdentityDifferenceOrderAndExplicitBounds() {
        VisualReference reference = new VisualReference(
                "launcher-1920", "palisade", "golden-session", "main",
                png(), sha(), 1, 1, new CapturedImage.Scale(1, 1),
                Instant.EPOCH, snapshot(4, 7), null);
        CurrentVisualEvidence current = new CurrentVisualEvidence(
                "candidate-ui", "palisade", "main",
                image(4, 7), Instant.EPOCH.plusSeconds(1), snapshot(4, 7), null);
        var differences = new java.util.ArrayList<>(List.of(
                new VisualDifference(
                        DifferenceCategory.TEXT, "seed", "$.text",
                        "Seed", "Seed value", true)));

        VisualComparisonResult result = new VisualComparisonResult(
                ComparisonStatus.NOT_CONVERGED, VisualPolicy.pixelExactV1(),
                reference, current, new VisualMetrics(1, 2.0, 5),
                differences, List.of(), 1, Duration.ofMillis(20));
        differences.clear();

        assertEquals("candidate-ui", result.current().sessionId());
        assertEquals(4, result.current().image().revision());
        assertEquals(List.of("seed"),
                result.differences().stream().map(VisualDifference::controlId).toList());
        assertEquals(1, result.iterations());
    }

    @Test
    void convergenceRequiresFreshCompatibleAcceptedCaptureAndZeroBlockingDifferences() {
        VisualReference reference = new VisualReference(
                "ref", "app", "golden", "main", png(), sha(),
                1, 1, new CapturedImage.Scale(1, 1), Instant.EPOCH,
                snapshot(2, 3), null);
        assertThrows(IllegalArgumentException.class, () -> new CurrentVisualEvidence(
                "session", "app", "main", image(1, 3), Instant.EPOCH,
                snapshot(2, 3), null));
        assertThrows(IllegalArgumentException.class, () -> new VisualComparisonResult(
                ComparisonStatus.CONVERGED, VisualPolicy.pixelExactV1(),
                reference, current(reference), new VisualMetrics(1, 0, 0),
                List.of(), List.of(), 1, Duration.ZERO));
    }

    @Test
    void incompleteEvidenceRetainsSpecificBoundedDiagnostic() {
        ComparisonDiagnostic diagnostic = new ComparisonDiagnostic(
                "CURRENT_CAPTURE_REQUIRED", "$.currentCapture",
                "accepted full-frame capture", "absent");
        VisualComparisonResult result = VisualComparisonResult.incomplete(
                VisualPolicy.pixelExactV1(), null, List.of(diagnostic),
                Duration.ofMillis(5));

        assertEquals(ComparisonStatus.INCOMPLETE, result.status());
        assertEquals("CURRENT_CAPTURE_REQUIRED", result.diagnostics().getFirst().code());
    }

    @Test
    void currentEvidenceRejectsContentThatDoesNotMatchItsClaimedHash() {
        CapturedImage corrupt = new CapturedImage(
                png(), "0".repeat(64), 3, 2, 1, 1,
                new CapturedImage.Scale(1, 1));

        assertThrows(IllegalArgumentException.class, () -> new CurrentVisualEvidence(
                "session", "app", "main", corrupt, Instant.EPOCH,
                snapshot(2, 3), null));
    }

    private static CurrentVisualEvidence current(VisualReference reference) {
        return new CurrentVisualEvidence(
                "session", reference.applicationId(), reference.viewportId(),
                image(2, 3), Instant.EPOCH, snapshot(2, 3), null);
    }

    private static CapturedImage image(long revision, long frame) {
        return new CapturedImage(
                png(), sha(), frame, revision, 1, 1,
                new CapturedImage.Scale(1, 1));
    }

    private static byte[] png() {
        return new byte[] {1};
    }

    private static SemanticSnapshot snapshot(long revision, long frame) {
        SemanticNode root = new SemanticNode(
                "n0", null, List.of(), Role.GROUP, "Root", null, null,
                null, "root", "Group", state(),
                new Bounds(0, 0, 1, 1), new Bounds(0, 0, 1, 1),
                new Bounds(0, 0, 1, 1), 0, Map.of());
        LinkedHashMap<String, SemanticNode> nodes = new LinkedHashMap<>();
        nodes.put(root.id(), root);
        return new SemanticSnapshot(revision, frame, root.id(), nodes);
    }

    private static SemanticState state() {
        return new SemanticState(
                true, true, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, false, 1,
                false, true, true);
    }

    private static String sha() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(png()));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
