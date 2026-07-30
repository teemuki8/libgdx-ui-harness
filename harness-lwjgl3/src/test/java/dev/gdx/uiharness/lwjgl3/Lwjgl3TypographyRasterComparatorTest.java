package dev.gdx.uiharness.lwjgl3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class Lwjgl3TypographyRasterComparatorTest {
    @Test
    void measuresOnlyTheAttributedFramebufferRectangle() throws Exception {
        BufferedImage expected = image(4, 4, 0xff000000);
        BufferedImage current = image(4, 4, 0xff000000);
        current.setRGB(1, 1, 0xffffffff);

        double residual = new Lwjgl3TypographyRasterComparator().meanAbsoluteError(
                png(expected),
                png(current),
                4,
                4,
                new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 2, 2),
                new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 2, 2));

        assertEquals(47.8125, residual, 1e-9);
    }

    @Test
    void rejectsNonFramebufferOrDifferentSizedRegions() throws Exception {
        byte[] png = png(image(4, 4, 0xff000000));
        Lwjgl3TypographyRasterComparator comparator =
                new Lwjgl3TypographyRasterComparator();

        assertThrows(
                IllegalArgumentException.class,
                () -> comparator.meanAbsoluteError(
                        png,
                        png,
                        4,
                        4,
                        new CoordinateBounds(CoordinateSpace.STAGE, 0, 0, 2, 2),
                        new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 2, 2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> comparator.meanAbsoluteError(
                        png,
                        png,
                        4,
                        4,
                        new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 2, 2),
                        new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 3, 2)));
    }

    private static BufferedImage image(int width, int height, int color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
