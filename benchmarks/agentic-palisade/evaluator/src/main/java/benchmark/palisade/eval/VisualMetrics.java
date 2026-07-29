package benchmark.palisade.eval;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Deterministic, bounded image measurements for the frozen visual observations. */
public final class VisualMetrics {
    private static final int REQUIRED_CAPTURES = 5;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16_777_216L;
    private static final long MAX_PNG_BYTES = 64L * 1024L * 1024L;
    private static final int EDGE_THRESHOLD = 96;
    private static final int BACKGROUND_DISTANCE = 24;

    private VisualMetrics() {
    }

    /** Loads one reference and exactly five candidate captures using ImageIO. */
    public static Result compare(Path reference, List<Path> captures) throws IOException {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(captures, "captures");
        if (captures.size() != REQUIRED_CAPTURES) {
            throw new IllegalArgumentException("Exactly five captures are required");
        }
        BufferedImage referenceImage = readBoundedPng(reference);
        List<BufferedImage> candidateImages = new ArrayList<>(REQUIRED_CAPTURES);
        for (Path capture : captures) {
            candidateImages.add(readBoundedPng(capture));
        }
        return compare(referenceImage, candidateImages);
    }

    /** Compares one reference with exactly five captures at the same dimensions. */
    public static Result compare(BufferedImage reference, List<BufferedImage> captures) {
        validateImage(reference, "reference");
        Objects.requireNonNull(captures, "captures");
        if (captures.size() != REQUIRED_CAPTURES) {
            throw new IllegalArgumentException("Exactly five captures are required");
        }
        for (int index = 0; index < captures.size(); index++) {
            BufferedImage capture = captures.get(index);
            validateImage(capture, "capture " + index);
            if (capture.getWidth() != reference.getWidth()
                    || capture.getHeight() != reference.getHeight()) {
                throw new IllegalArgumentException("Image dimensions do not match the reference");
            }
        }

        BufferedImage candidate = captures.get(0);
        Bounds referenceBounds = bounds(reference);
        Bounds candidateBounds = bounds(candidate);
        return new Result(
                rgbMae(reference, candidate),
                luminanceSsim(reference, candidate, 1),
                luminanceSsim(reference, candidate, 2),
                luminanceSsim(reference, candidate, 4),
                sobelEdgeF1(reference, candidate),
                paletteDelta(reference, candidate),
                boundsDisplacement(referenceBounds, candidateBounds, reference.getWidth(), reference.getHeight()),
                clipping(candidateBounds, candidate.getWidth(), candidate.getHeight()),
                repeatability(captures),
                fontRasterResidual(reference, candidate));
    }

    static BufferedImage readBoundedPng(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Image must be a local regular file");
        }
        long bytes = Files.size(normalized);
        if (bytes < 8 || bytes > MAX_PNG_BYTES) {
            throw new IllegalArgumentException("Image byte length is outside the allowed bounds");
        }
        try (InputStream raw = Files.newInputStream(normalized);
                ImageInputStream input = ImageIO.createImageInputStream(raw)) {
            if (input == null) {
                throw new IllegalArgumentException("Could not open image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Unsupported image");
            }
            ImageReader reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    throw new IllegalArgumentException("Only PNG evidence is accepted");
                }
                reader.setInput(input, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                validateImage(image, "decoded image");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static void validateImage(BufferedImage image, String name) {
        if (image == null) {
            throw new IllegalArgumentException(name + " is missing");
        }
        validateDimensions(image.getWidth(), image.getHeight());
    }

    private static void validateDimensions(int width, int height) {
        long pixels = (long) width * height;
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || pixels > MAX_PIXELS) {
            throw new IllegalArgumentException("Image dimensions exceed the allowed bounds");
        }
    }

    private static double rgbMae(BufferedImage first, BufferedImage second) {
        long total = 0;
        int width = first.getWidth();
        int height = first.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = first.getRGB(x, y);
                int b = second.getRGB(x, y);
                total += Math.abs(((a >>> 16) & 0xff) - ((b >>> 16) & 0xff));
                total += Math.abs(((a >>> 8) & 0xff) - ((b >>> 8) & 0xff));
                total += Math.abs((a & 0xff) - (b & 0xff));
            }
        }
        return total / (3.0 * width * height);
    }

    private static double luminanceSsim(BufferedImage first, BufferedImage second, int scale) {
        int scaledWidth = (first.getWidth() + scale - 1) / scale;
        int scaledHeight = (first.getHeight() + scale - 1) / scale;
        double total = 0.0;
        int windows = 0;
        for (int top = 0; top < scaledHeight; top += 8) {
            for (int left = 0; left < scaledWidth; left += 8) {
                int right = Math.min(left + 8, scaledWidth);
                int bottom = Math.min(top + 8, scaledHeight);
                double sumA = 0.0;
                double sumB = 0.0;
                double sumAA = 0.0;
                double sumBB = 0.0;
                double sumAB = 0.0;
                int count = 0;
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        double a = scaledLuminance(first, x, y, scale);
                        double b = scaledLuminance(second, x, y, scale);
                        sumA += a;
                        sumB += b;
                        sumAA += a * a;
                        sumBB += b * b;
                        sumAB += a * b;
                        count++;
                    }
                }
                double meanA = sumA / count;
                double meanB = sumB / count;
                double varianceA = Math.max(0.0, sumAA / count - meanA * meanA);
                double varianceB = Math.max(0.0, sumBB / count - meanB * meanB);
                double covariance = sumAB / count - meanA * meanB;
                double c1 = 6.5025;
                double c2 = 58.5225;
                double numerator = (2.0 * meanA * meanB + c1) * (2.0 * covariance + c2);
                double denominator = (meanA * meanA + meanB * meanB + c1)
                        * (varianceA + varianceB + c2);
                total += denominator == 0.0 ? 1.0 : Math.max(-1.0, Math.min(1.0, numerator / denominator));
                windows++;
            }
        }
        double score = total / windows;
        return Math.abs(1.0 - score) < 1.0e-12 ? 1.0 : score;
    }

    private static double scaledLuminance(BufferedImage image, int scaledX, int scaledY, int scale) {
        int fromX = scaledX * scale;
        int fromY = scaledY * scale;
        int toX = Math.min(fromX + scale, image.getWidth());
        int toY = Math.min(fromY + scale, image.getHeight());
        double total = 0.0;
        int count = 0;
        for (int y = fromY; y < toY; y++) {
            for (int x = fromX; x < toX; x++) {
                total += luminance(image.getRGB(x, y));
                count++;
            }
        }
        return total / count;
    }

    private static double sobelEdgeF1(BufferedImage reference, BufferedImage candidate) {
        long truePositive = 0;
        long falsePositive = 0;
        long falseNegative = 0;
        for (int y = 1; y < reference.getHeight() - 1; y++) {
            for (int x = 1; x < reference.getWidth() - 1; x++) {
                boolean expected = sobel(reference, x, y) >= EDGE_THRESHOLD;
                boolean actual = sobel(candidate, x, y) >= EDGE_THRESHOLD;
                if (expected && actual) {
                    truePositive++;
                } else if (actual) {
                    falsePositive++;
                } else if (expected) {
                    falseNegative++;
                }
            }
        }
        if (truePositive == 0 && falsePositive == 0 && falseNegative == 0) {
            return 1.0;
        }
        return (2.0 * truePositive) / (2.0 * truePositive + falsePositive + falseNegative);
    }

    private static double sobel(BufferedImage image, int x, int y) {
        double topLeft = luminance(image.getRGB(x - 1, y - 1));
        double top = luminance(image.getRGB(x, y - 1));
        double topRight = luminance(image.getRGB(x + 1, y - 1));
        double left = luminance(image.getRGB(x - 1, y));
        double right = luminance(image.getRGB(x + 1, y));
        double bottomLeft = luminance(image.getRGB(x - 1, y + 1));
        double bottom = luminance(image.getRGB(x, y + 1));
        double bottomRight = luminance(image.getRGB(x + 1, y + 1));
        double horizontal = -topLeft + topRight - 2.0 * left + 2.0 * right - bottomLeft + bottomRight;
        double vertical = -topLeft - 2.0 * top - topRight + bottomLeft + 2.0 * bottom + bottomRight;
        return Math.hypot(horizontal, vertical);
    }

    private static double paletteDelta(BufferedImage first, BufferedImage second) {
        int[] a = new int[4096];
        int[] b = new int[4096];
        int width = first.getWidth();
        int height = first.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                a[paletteBin(first.getRGB(x, y))]++;
                b[paletteBin(second.getRGB(x, y))]++;
            }
        }
        long difference = 0;
        for (int index = 0; index < a.length; index++) {
            difference += Math.abs(a[index] - b[index]);
        }
        return difference / (2.0 * width * height);
    }

    private static int paletteBin(int rgb) {
        return (((rgb >>> 20) & 0xf) << 8) | (((rgb >>> 12) & 0xf) << 4) | ((rgb >>> 4) & 0xf);
    }

    private static Bounds bounds(BufferedImage image) {
        int background = image.getRGB(0, 0);
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (colorDistance(background, image.getRGB(x, y)) > BACKGROUND_DISTANCE) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? Bounds.absent() : new Bounds(minX, minY, maxX, maxY, false);
    }

    private static int colorDistance(int first, int second) {
        return Math.abs(((first >>> 16) & 0xff) - ((second >>> 16) & 0xff))
                + Math.abs(((first >>> 8) & 0xff) - ((second >>> 8) & 0xff))
                + Math.abs((first & 0xff) - (second & 0xff));
    }

    private static double boundsDisplacement(Bounds expected, Bounds actual, int width, int height) {
        if (expected.empty && actual.empty) {
            return 0.0;
        }
        if (expected.empty || actual.empty) {
            return 1.0;
        }
        double horizontal = Math.abs(expected.minX - actual.minX) + Math.abs(expected.maxX - actual.maxX);
        double vertical = Math.abs(expected.minY - actual.minY) + Math.abs(expected.maxY - actual.maxY);
        return (horizontal / width + vertical / height) / 4.0;
    }

    private static Clipping clipping(Bounds bounds, int width, int height) {
        if (bounds.empty) {
            return new Clipping(false, false, false, false);
        }
        return new Clipping(bounds.minX == 0, bounds.maxX == width - 1,
                bounds.minY == 0, bounds.maxY == height - 1);
    }

    private static double repeatability(List<BufferedImage> captures) {
        double total = 0.0;
        for (int index = 1; index < captures.size(); index++) {
            total += rgbMae(captures.get(0), captures.get(index));
        }
        return total / (captures.size() - 1);
    }

    private static double fontRasterResidual(BufferedImage reference, BufferedImage candidate) {
        if (reference.getWidth() < 3 || reference.getHeight() < 3) {
            return rgbMae(reference, candidate);
        }
        double total = 0.0;
        long count = 0;
        for (int y = 1; y < reference.getHeight() - 1; y++) {
            for (int x = 1; x < reference.getWidth() - 1; x++) {
                double referenceHighPass = luminance(reference.getRGB(x, y)) - neighborhoodMean(reference, x, y);
                double candidateHighPass = luminance(candidate.getRGB(x, y)) - neighborhoodMean(candidate, x, y);
                total += Math.abs(referenceHighPass - candidateHighPass);
                count++;
            }
        }
        return total / count;
    }

    private static double neighborhoodMean(BufferedImage image, int centerX, int centerY) {
        double total = 0.0;
        for (int y = centerY - 1; y <= centerY + 1; y++) {
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                total += luminance(image.getRGB(x, y));
            }
        }
        return total / 9.0;
    }

    private static double luminance(int rgb) {
        return 0.2126 * ((rgb >>> 16) & 0xff)
                + 0.7152 * ((rgb >>> 8) & 0xff)
                + 0.0722 * (rgb & 0xff);
    }

    /** Visual channels are independent measurements; no composite score is defined. */
    public record Result(
            double rgbMae,
            double luminanceSsimScale1,
            double luminanceSsimScale2,
            double luminanceSsimScale4,
            double sobelEdgeF1,
            double paletteDelta,
            double boundsDisplacement,
            Clipping clipping,
            double repeatability,
            double fontRasterResidual) {
    }

    /** Candidate non-background contact with each viewport edge. */
    public record Clipping(boolean left, boolean right, boolean top, boolean bottom) {
        public boolean any() {
            return left || right || top || bottom;
        }
    }

    private record Bounds(int minX, int minY, int maxX, int maxY, boolean empty) {
        private static Bounds absent() {
            return new Bounds(0, 0, 0, 0, true);
        }
    }
}
