package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.awt.image.BufferedImage;
import java.util.Objects;

/** Computes a deterministic raster residual for one actor-attributed framebuffer region. */
public final class Lwjgl3TypographyRasterComparator {

    /** Returns mean absolute ARGB-channel error for matching bounded actor regions. */
    public double meanAbsoluteError(
            byte[] referencePng,
            byte[] currentPng,
            int width,
            int height,
            CoordinateBounds referenceBounds,
            CoordinateBounds currentBounds) {
        BufferedImage reference =
                Lwjgl3VisualComparator.decode(referencePng, width, height, "reference");
        BufferedImage current =
                Lwjgl3VisualComparator.decode(currentPng, width, height, "current");
        PixelRegion expected = region(referenceBounds, width, height, "referenceBounds");
        PixelRegion observed = region(currentBounds, width, height, "currentBounds");
        if (expected.width() != observed.width() || expected.height() != observed.height()) {
            throw new IllegalArgumentException(
                    "reference and current typography regions must have equal pixel dimensions");
        }
        long totalChannelDelta = 0;
        for (int offsetY = 0; offsetY < expected.height(); offsetY++) {
            for (int offsetX = 0; offsetX < expected.width(); offsetX++) {
                int first = reference.getRGB(
                        expected.x() + offsetX, expected.y() + offsetY);
                int second = current.getRGB(
                        observed.x() + offsetX, observed.y() + offsetY);
                totalChannelDelta += channelDelta(first, second, 24);
                totalChannelDelta += channelDelta(first, second, 16);
                totalChannelDelta += channelDelta(first, second, 8);
                totalChannelDelta += channelDelta(first, second, 0);
            }
        }
        return totalChannelDelta / (4.0 * expected.width() * expected.height());
    }

    private static PixelRegion region(
            CoordinateBounds bounds, int width, int height, String name) {
        Objects.requireNonNull(bounds, name);
        if (bounds.space() != CoordinateSpace.FRAMEBUFFER) {
            throw new IllegalArgumentException(name + " must use framebuffer coordinates");
        }
        int left = (int) Math.floor(bounds.x());
        int top = (int) Math.floor(bounds.y());
        int right = (int) Math.ceil(bounds.x() + bounds.width());
        int bottom = (int) Math.ceil(bounds.y() + bounds.height());
        if (left < 0 || top < 0 || right > width || bottom > height
                || right <= left || bottom <= top) {
            throw new IllegalArgumentException(name + " must be a non-empty in-frame region");
        }
        return new PixelRegion(left, top, right - left, bottom - top);
    }

    private static int channelDelta(int first, int second, int shift) {
        return Math.abs(
                ((first >>> shift) & 0xff) - ((second >>> shift) & 0xff));
    }

    private record PixelRegion(int x, int y, int width, int height) {}
}
