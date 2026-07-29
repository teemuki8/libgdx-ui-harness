package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.visual.CurrentVisualEvidence;
import dev.gdx.uiharness.core.visual.DifferenceCategory;
import dev.gdx.uiharness.core.visual.VisualComparator;
import dev.gdx.uiharness.core.visual.VisualDifference;
import dev.gdx.uiharness.core.visual.VisualMetrics;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
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

        MetricsAccumulator accumulator = raster(expected, observed);
        List<VisualDifference> differences = semanticDifferences(reference, current);
        if (accumulator.differingPixels() > 0) {
            boolean blocking =
                    accumulator.differingPixels() > policy.maxDifferingPixels()
                    || accumulator.meanAbsoluteError()
                    > policy.maxMeanAbsoluteError();
            differences.add(new VisualDifference(
                    DifferenceCategory.RASTER_RESIDUAL, null, "$.pixels",
                    "reference pixels", accumulator.differingPixels()
                            + " current pixels differ", blocking));
        }
        return new Comparison(
                new VisualMetrics(
                        accumulator.differingPixels(),
                        accumulator.meanAbsoluteError(),
                        accumulator.maximumChannelDelta()),
                differences);
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
            addDifference(differences, DifferenceCategory.VISIBILITY, controlId, "$.visible",
                    expectedNode.state().visible(), observedNode.state().visible());
            addDifference(differences, DifferenceCategory.BOUNDS, controlId, "$.stageBounds",
                    expectedNode.stageBounds(), observedNode.stageBounds());
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

    private static MetricsAccumulator raster(
            BufferedImage expected, BufferedImage observed) {
        long differingPixels = 0;
        long totalChannelDelta = 0;
        int maximumChannelDelta = 0;
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
            }
        }
        double mean = totalChannelDelta
                / (4.0 * expected.getWidth() * expected.getHeight());
        return new MetricsAccumulator(differingPixels, mean, maximumChannelDelta);
    }

    private static int channelDelta(int first, int second, int shift) {
        return Math.abs(
                ((first >>> shift) & 0xff) - ((second >>> shift) & 0xff));
    }

    private static BufferedImage decode(
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

    private record MetricsAccumulator(
            long differingPixels, double meanAbsoluteError, int maximumChannelDelta) {}
}
