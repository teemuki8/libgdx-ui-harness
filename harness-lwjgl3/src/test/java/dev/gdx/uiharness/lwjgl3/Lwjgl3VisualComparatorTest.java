package dev.gdx.uiharness.lwjgl3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.visual.CurrentVisualEvidence;
import dev.gdx.uiharness.core.visual.DifferenceCategory;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class Lwjgl3VisualComparatorTest {
    @Test
    void attributesKnownSemanticChangesAndRetainsRasterResidual() throws Exception {
        byte[] expectedPng = png(0xff000000);
        byte[] actualPng = png(0xffffffff);
        VisualReference reference = reference(
                expectedPng, snapshot("Seed", new Bounds(1, 1, 2, 2)));
        CurrentVisualEvidence current = current(
                actualPng, snapshot("Random seed", new Bounds(2, 1, 2, 2)));

        var result = new Lwjgl3VisualComparator()
                .compare(reference, current, VisualPolicy.pixelExactV1());

        assertEquals(4, result.metrics().differingPixels());
        assertTrue(result.differences().stream().anyMatch(difference ->
                difference.category() == DifferenceCategory.TEXT
                        && "seed".equals(difference.controlId())));
        assertTrue(result.differences().stream().anyMatch(difference ->
                difference.category() == DifferenceCategory.BOUNDS
                        && "seed".equals(difference.controlId())));
        assertTrue(result.differences().stream().anyMatch(difference ->
                difference.category() == DifferenceCategory.RASTER_RESIDUAL
                        && difference.controlId() == null));
    }

    @Test
    void exactFreshImagesHaveZeroMeasurementsAndDifferences() throws Exception {
        byte[] png = png(0xff112233);
        SemanticSnapshot semantics = snapshot("Seed", new Bounds(1, 1, 2, 2));

        var result = new Lwjgl3VisualComparator().compare(
                reference(png, semantics), current(png, semantics),
                VisualPolicy.pixelExactV1());

        assertEquals(0, result.metrics().differingPixels());
        assertEquals(0, result.metrics().meanAbsoluteError());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void rasterOnlyReferenceDoesNotInventSemanticAttribution() throws Exception {
        byte[] png = png(0xff112233);
        VisualReference reference = reference(png, null);

        var result = new Lwjgl3VisualComparator().compare(
                reference, current(
                        png, snapshot("Changed", new Bounds(0, 0, 1, 1))),
                VisualPolicy.pixelExactV1());

        assertEquals(0, result.metrics().differingPixels());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void attributesKnownPaddingChangeToStableControlId() throws Exception {
        byte[] png = png(0xff112233);
        SemanticSnapshot expected = snapshot(
                "Seed", new Bounds(1, 1, 2, 2), Map.of("paddingLeft", "8"));
        SemanticSnapshot observed = snapshot(
                "Seed", new Bounds(1, 1, 2, 2), Map.of("paddingLeft", "12"));

        var result = new Lwjgl3VisualComparator().compare(
                reference(png, expected), current(png, observed),
                VisualPolicy.pixelExactV1());

        assertTrue(result.differences().stream().anyMatch(difference ->
                difference.category() == DifferenceCategory.PADDING
                        && "seed".equals(difference.controlId())));
    }

    private static VisualReference reference(byte[] png, SemanticSnapshot snapshot) {
        return new VisualReference(
                "reference", "app", "golden", "main", png, sha(png),
                2, 2, new CapturedImage.Scale(1, 1), Instant.EPOCH,
                snapshot, null);
    }

    private static CurrentVisualEvidence current(byte[] png, SemanticSnapshot snapshot) {
        return new CurrentVisualEvidence(
                "session", "app", "main",
                new CapturedImage(
                        png, sha(png), 3, 2, 2, 2,
                        new CapturedImage.Scale(1, 1)),
                Instant.EPOCH, snapshot, null);
    }

    private static SemanticSnapshot snapshot(String text, Bounds bounds) {
        return snapshot(text, bounds, Map.of());
    }

    private static SemanticSnapshot snapshot(
            String text, Bounds bounds, Map<String, String> properties) {
        SemanticNode root = node(
                "n0", null, List.of("n1"), Role.GROUP, "Root", null,
                null, new Bounds(0, 0, 2, 2), Map.of());
        SemanticNode seed = node(
                "n1", "n0", List.of(), Role.TEXT_FIELD, "Seed", text,
                "seed", bounds, properties);
        Map<String, SemanticNode> nodes = new LinkedHashMap<>();
        nodes.put(root.id(), root);
        nodes.put(seed.id(), seed);
        return new SemanticSnapshot(2, 3, root.id(), nodes);
    }

    private static SemanticNode node(
            String id,
            String parent,
            List<String> children,
            Role role,
            String name,
            String text,
            String testId,
            Bounds bounds,
            Map<String, String> properties) {
        return new SemanticNode(
                id, parent, children, role, name, text, null, testId,
                id, role.name(), new SemanticState(
                        true, true, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        false, true, 1, false, true, true),
                bounds, bounds, bounds, 0, properties);
    }

    private static byte[] png(int color) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                image.setRGB(x, y, color);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
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
