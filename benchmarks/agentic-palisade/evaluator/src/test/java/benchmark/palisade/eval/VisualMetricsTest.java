package benchmark.palisade.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

final class VisualMetricsTest {
    @Test
    void identicalImagesHavePerfectMetrics() {
        BufferedImage reference = specimen(96, 64);

        VisualMetrics.Result result = VisualMetrics.compare(reference, repeated(reference));

        assertEquals(0.0, result.rgbMae());
        assertEquals(1.0, result.luminanceSsimScale1());
        assertEquals(1.0, result.luminanceSsimScale2());
        assertEquals(1.0, result.luminanceSsimScale4());
        assertEquals(1.0, result.sobelEdgeF1());
        assertEquals(0.0, result.paletteDelta());
        assertEquals(0.0, result.boundsDisplacement());
        assertFalse(result.clipping().any());
        assertEquals(0.0, result.repeatability());
        assertEquals(0.0, result.fontRasterResidual());
    }

    @Test
    void onePixelTranslationDegradesEdgesAndLayout() {
        BufferedImage reference = specimen(96, 64);
        BufferedImage translated = translate(reference, 1, 0);

        VisualMetrics.Result result = VisualMetrics.compare(reference, repeated(translated));

        assertTrue(result.sobelEdgeF1() < 1.0);
        assertTrue(result.boundsDisplacement() > 0.0);
        assertTrue(result.luminanceSsimScale1() < 1.0);
    }

    @Test
    void colorShiftDegradesPaletteWithoutMovingBounds() {
        BufferedImage reference = specimen(96, 64);
        BufferedImage shifted = recolorForeground(reference, new Color(44, 116, 180));

        VisualMetrics.Result result = VisualMetrics.compare(reference, repeated(shifted));

        assertTrue(result.paletteDelta() > 0.0);
        assertEquals(0.0, result.boundsDisplacement());
        assertFalse(result.clipping().any());
    }

    @Test
    void fineNoiseIsSeparatedIntoFontRasterResidual() {
        BufferedImage reference = specimen(96, 64);
        BufferedImage noisy = addFineNoise(reference);

        VisualMetrics.Result result = VisualMetrics.compare(reference, repeated(noisy));

        assertTrue(result.fontRasterResidual() > 0.0);
        assertTrue(result.fontRasterResidual() > result.paletteDelta());
        assertEquals(0.0, result.boundsDisplacement());
        assertEquals(0.0, result.repeatability());
    }

    @Test
    void fiveCaptureVariationIsReportedSeparately() {
        BufferedImage reference = specimen(96, 64);
        BufferedImage noisy = addFineNoise(reference);

        VisualMetrics.Result result = VisualMetrics.compare(
                reference, List.of(reference, reference, noisy, reference, noisy));

        assertTrue(result.repeatability() > 0.0);
    }

    @Test
    void dimensionsAndCaptureCountFailClosed() {
        BufferedImage reference = specimen(96, 64);
        BufferedImage wrongSize = specimen(95, 64);

        assertThrows(IllegalArgumentException.class,
                () -> VisualMetrics.compare(reference, repeated(wrongSize)));
        assertThrows(IllegalArgumentException.class,
                () -> VisualMetrics.compare(reference, List.of(reference)));
    }

    private static List<BufferedImage> repeated(BufferedImage image) {
        return List.of(image, image, image, image, image);
    }

    private static BufferedImage specimen(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(24, 28, 34));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(38, 92, 145));
        graphics.fillRect(12, 9, 66, 44);
        graphics.setColor(new Color(232, 234, 237));
        graphics.fillRect(20, 17, 38, 3);
        graphics.fillRect(20, 25, 48, 3);
        graphics.fillRect(20, 33, 31, 3);
        graphics.setColor(new Color(197, 151, 62));
        graphics.fillRect(54, 39, 16, 7);
        graphics.dispose();
        return image;
    }

    private static BufferedImage translate(BufferedImage source, int dx, int dy) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(source.getRGB(0, 0)));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.drawImage(source, dx, dy, null);
        graphics.dispose();
        return image;
    }

    private static BufferedImage recolorForeground(BufferedImage source, Color color) {
        BufferedImage image = copy(source);
        int background = source.getRGB(0, 0) & 0xffffff;
        int replacement = color.getRGB() & 0xffffff;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = source.getRGB(x, y) & 0xffffff;
                if (rgb != background && ((rgb >>> 16) & 0xff) < 100) {
                    image.setRGB(x, y, replacement);
                }
            }
        }
        return image;
    }

    private static BufferedImage addFineNoise(BufferedImage source) {
        BufferedImage image = copy(source);
        for (int y = 1; y < image.getHeight() - 1; y++) {
            for (int x = 1; x < image.getWidth() - 1; x++) {
                int rgb = source.getRGB(x, y);
                int delta = ((x + y) & 1) == 0 ? 3 : -3;
                int red = clamp(((rgb >>> 16) & 0xff) + delta);
                int green = clamp(((rgb >>> 8) & 0xff) + delta);
                int blue = clamp((rgb & 0xff) + delta);
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        image.setData(source.getData());
        return image;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
