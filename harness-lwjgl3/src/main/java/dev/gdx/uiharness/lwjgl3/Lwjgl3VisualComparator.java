package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.visual.CurrentVisualEvidence;
import dev.gdx.uiharness.core.visual.DifferenceCategory;
import dev.gdx.uiharness.core.visual.VisualComparator;
import dev.gdx.uiharness.core.visual.VisualDifference;
import dev.gdx.uiharness.core.visual.VisualMetrics;
import dev.gdx.uiharness.core.visual.VisualHeatmap;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import dev.gdx.uiharness.core.visual.VisualRegion;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Bounded deterministic PNG comparator for LWJGL3 full-frame evidence. */
public final class Lwjgl3VisualComparator implements VisualComparator {
    private static final long MAX_PIXELS = 33_554_432L;

    /** Compares exact-size PNGs and attributes available semantic differences by test ID. */
    @Override public Comparison compare(
            VisualReference reference,
            CurrentVisualEvidence current,
            VisualPolicy policy) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(policy, "policy");
        if (reference.width() != current.image().width()
                || reference.height() != current.image().height()) {
            throw new IllegalArgumentException("reference and current dimensions differ");
        }
        BufferedImage expected = decode(
                reference.pngBytes(), reference.width(), reference.height(), "reference");
        BufferedImage observed = decode(
                current.image().pngBytes(), current.image().width(),
                current.image().height(), "current");

        RasterResult raster = raster(expected, observed);
        List<VisualDifference> differences = semanticDifferences(reference, current);
        if (raster.metrics().differingPixels() > 0) {
            boolean blocking =
                    raster.metrics().differingPixels() > policy.maxDifferingPixels()
                    || raster.metrics().meanAbsoluteError()
                    > policy.maxMeanAbsoluteError();
            differences.add(new VisualDifference(
                    DifferenceCategory.RASTER_RESIDUAL, null, "$.pixels",
                    "reference pixels", raster.metrics().differingPixels()
                            + " current pixels differ", blocking));
        }
        List<VisualRegion> regions = semanticRegions(
                reference, current, differences, expected, observed);
        for (VisualRegion region : raster.regions()) {
            if (regions.size() == 256) {
                break;
            }
            regions.add(region);
        }
        return new Comparison(raster.metrics(), differences, regions, raster.heatmap());
    }

    private static List<VisualDifference> semanticDifferences(
            VisualReference reference, CurrentVisualEvidence current) {
        if (reference.semanticSnapshot() == null) {
            return new ArrayList<>();
        }
        Map<String, SemanticNode> expected = byTestId(reference.semanticSnapshot().nodes());
        Map<String, SemanticNode> observed = byTestId(current.semanticSnapshot().nodes());
        List<VisualDifference> differences = new ArrayList<>();
        expected.forEach((controlId, expectedNode) -> {
            SemanticNode observedNode = observed.get(controlId);
            if (observedNode == null) {
                differences.add(new VisualDifference(
                        DifferenceCategory.VISIBILITY, controlId, "$.nodes." + controlId,
                        "present", "absent", true));
                return;
            }
            addDifference(differences, DifferenceCategory.TEXT, controlId, "$.text",
                    expectedNode.text(), observedNode.text());
            addDifference(differences, DifferenceCategory.VALUE, controlId, "$.value",
                    expectedNode.properties().get("value"),
                    observedNode.properties().get("value"));
            addDifference(differences, DifferenceCategory.VISIBILITY, controlId, "$.visible",
                    expectedNode.state().visible(), observedNode.state().visible());
            addDifference(differences, DifferenceCategory.BOUNDS, controlId, "$.stageBounds",
                    expectedNode.stageBounds(), observedNode.stageBounds());
            addDifference(differences, DifferenceCategory.CLIPPING, controlId, "$.state.clipped",
                    expectedNode.state().clipped(), observedNode.state().clipped());
            padding(expectedNode).forEach((property, expectedValue) ->
                    addDifference(
                            differences, DifferenceCategory.PADDING, controlId,
                            "$.properties." + property, expectedValue,
                            observedNode.properties().get(property)));
        });
        observed.forEach((controlId, observedNode) -> {
            if (!expected.containsKey(controlId)) {
                differences.add(new VisualDifference(
                        DifferenceCategory.VISIBILITY, controlId,
                        "$.nodes." + controlId, "absent", "present", true));
            }
        });
        return differences;
    }

    private static List<VisualRegion> semanticRegions(
            VisualReference reference,
            CurrentVisualEvidence current,
            List<VisualDifference> differences,
            BufferedImage expectedImage,
            BufferedImage observedImage) {
        if (reference.semanticSnapshot() == null) {
            return new ArrayList<>();
        }
        Map<String, SemanticNode> expected = byTestId(reference.semanticSnapshot().nodes());
        Map<String, SemanticNode> observed = byTestId(current.semanticSnapshot().nodes());
        List<VisualRegion> regions = new ArrayList<>();
        for (VisualDifference difference : differences) {
            if (difference.controlId() == null || regions.size() == 256) {
                continue;
            }
            SemanticNode expectedNode = expected.get(difference.controlId());
            SemanticNode observedNode = observed.get(difference.controlId());
            Bounds first = expectedNode == null ? null : expectedNode.screenBounds();
            Bounds second = observedNode == null ? null : observedNode.screenBounds();
            Bounds union = union(first, second);
            if (union == null) {
                continue;
            }
            int x = clamp((int) Math.floor(union.x()), 0, reference.width() - 1);
            int y = clamp((int) Math.floor(union.y()), 0, reference.height() - 1);
            int right = clamp((int) Math.ceil(union.x() + union.width()), x + 1,
                    reference.width());
            int bottom = clamp((int) Math.ceil(union.y() + union.height()), y + 1,
                    reference.height());
            RegionMetrics metrics = regionMetrics(
                    expectedImage, observedImage, x, y, right, bottom);
            regions.add(new VisualRegion(
                    difference.category(), difference.controlId(), x, y,
                    right - x, bottom - y,
                    metrics.differingPixels(),
                    metrics.meanAbsoluteError()));
        }
        return regions;
    }

    private static RegionMetrics regionMetrics(
            BufferedImage expected,
            BufferedImage observed,
            int left,
            int top,
            int right,
            int bottom) {
        long differing = 0;
        long channelDelta = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int first = expected.getRGB(x, y);
                int second = observed.getRGB(x, y);
                if (first != second) {
                    differing++;
                }
                channelDelta += channelDelta(first, second, 24)
                        + channelDelta(first, second, 16)
                        + channelDelta(first, second, 8)
                        + channelDelta(first, second, 0);
            }
        }
        long pixels = (long) (right - left) * (bottom - top);
        return new RegionMetrics(differing, channelDelta / (4.0 * pixels));
    }

    private static Bounds union(Bounds first, Bounds second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        double left = Math.min(first.x(), second.x());
        double top = Math.min(first.y(), second.y());
        double right = Math.max(first.x() + first.width(), second.x() + second.width());
        double bottom = Math.max(first.y() + first.height(), second.y() + second.height());
        return new Bounds(left, top, right - left, bottom - top);
    }

    private static Map<String, SemanticNode> byTestId(Map<String, SemanticNode> nodes) {
        Map<String, SemanticNode> indexed = new TreeMap<>();
        nodes.values().forEach(node -> {
            if (node.testId() != null) {
                SemanticNode previous = indexed.putIfAbsent(node.testId(), node);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate semantic test ID: " + node.testId());
                }
            }
        });
        return indexed;
    }

    private static Map<String, String> padding(SemanticNode node) {
        LinkedHashMap<String, String> padding = new LinkedHashMap<>();
        for (String key : List.of(
                "padding", "paddingTop", "paddingRight",
                "paddingBottom", "paddingLeft")) {
            String value = node.properties().get(key);
            if (value != null) {
                padding.put(key, value);
            }
        }
        node.properties().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("layout.padding."))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> padding.put(entry.getKey(), entry.getValue()));
        return padding;
    }

    private static void addDifference(
            List<VisualDifference> differences,
            DifferenceCategory category,
            String controlId,
            String path,
            Object expected,
            Object observed) {
        if (!Objects.equals(expected, observed)) {
            differences.add(new VisualDifference(
                    category, controlId, path,
                    String.valueOf(expected), String.valueOf(observed), true));
        }
    }

    private static RasterResult raster(
            BufferedImage expected, BufferedImage observed) {
        long differingPixels = 0;
        long totalChannelDelta = 0;
        int maximumChannelDelta = 0;
        BufferedImage heatmap = new BufferedImage(
                expected.getWidth(), expected.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int first = expected.getRGB(x, y);
                int second = observed.getRGB(x, y);
                if (first != second) {
                    differingPixels++;
                }
                int alpha = channelDelta(first, second, 24);
                int red = channelDelta(first, second, 16);
                int green = channelDelta(first, second, 8);
                int blue = channelDelta(first, second, 0);
                totalChannelDelta += alpha + red + green + blue;
                maximumChannelDelta = Math.max(
                        maximumChannelDelta,
                        Math.max(Math.max(alpha, red), Math.max(green, blue)));
                int heat = Math.max(Math.max(alpha, red), Math.max(green, blue));
                heatmap.setRGB(x, y, (heat << 24) | 0x00ff0000);
            }
        }
        double mean = totalChannelDelta
                / (4.0 * expected.getWidth() * expected.getHeight());
        VisualMetrics metrics = new VisualMetrics(
                differingPixels, mean, maximumChannelDelta);
        List<VisualRegion> regions = rasterRegions(expected, observed);
        byte[] encoded = encodeHeatmap(heatmap);
        return new RasterResult(
                metrics,
                regions,
                new VisualHeatmap(
                        encoded, sha256(encoded), expected.getWidth(), expected.getHeight()));
    }

    private static List<VisualRegion> rasterRegions(
            BufferedImage expected, BufferedImage observed) {
        int tileWidth = Math.max(1, (expected.getWidth() + 15) / 16);
        int tileHeight = Math.max(1, (expected.getHeight() + 15) / 16);
        List<VisualRegion> regions = new ArrayList<>();
        for (int top = 0; top < expected.getHeight(); top += tileHeight) {
            for (int left = 0; left < expected.getWidth(); left += tileWidth) {
                int right = Math.min(expected.getWidth(), left + tileWidth);
                int bottom = Math.min(expected.getHeight(), top + tileHeight);
                long differing = 0;
                long channelDelta = 0;
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        int first = expected.getRGB(x, y);
                        int second = observed.getRGB(x, y);
                        if (first != second) {
                            differing++;
                        }
                        channelDelta += channelDelta(first, second, 24)
                                + channelDelta(first, second, 16)
                                + channelDelta(first, second, 8)
                                + channelDelta(first, second, 0);
                    }
                }
                if (differing > 0) {
                    long pixels = (long) (right - left) * (bottom - top);
                    regions.add(new VisualRegion(
                            DifferenceCategory.RASTER_RESIDUAL, null,
                            left, top, right - left, bottom - top, differing,
                            channelDelta / (4.0 * pixels)));
                }
            }
        }
        return regions;
    }

    private static byte[] encodeHeatmap(BufferedImage heatmap) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(heatmap, "png", output)) {
                throw new IllegalStateException("PNG heatmap encoder is unavailable");
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not encode visual heatmap", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int channelDelta(int first, int second, int shift) {
        return Math.abs(
                ((first >>> shift) & 0xff) - ((second >>> shift) & 0xff));
    }

    static BufferedImage decode(
            byte[] png, int expectedWidth, int expectedHeight, String name) {
        try (ByteArrayInputStream raw = new ByteArrayInputStream(png);
                ImageInputStream input = ImageIO.createImageInputStream(raw)) {
            if (input == null) {
                throw new IllegalArgumentException(name + " PNG cannot be opened");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException(name + " is not a supported image");
            }
            ImageReader reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    throw new IllegalArgumentException(name + " must be PNG");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width != expectedWidth || height != expectedHeight
                        || pixels <= 0 || pixels > MAX_PIXELS) {
                    throw new IllegalArgumentException(
                            name + " PNG dimensions do not match bounded metadata");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException(name + " PNG cannot be decoded");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(name + " PNG cannot be decoded", failure);
        }
    }

    private record RasterResult(
            VisualMetrics metrics,
            List<VisualRegion> regions,
            VisualHeatmap heatmap) {}

    private record RegionMetrics(long differingPixels, double meanAbsoluteError) {}
}
