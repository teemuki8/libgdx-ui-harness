package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import java.util.Objects;

/** Current scroll position, range, geometry, and active-motion state. */
public record LayoutScroll(
        double x,
        double y,
        double maxX,
        double maxY,
        CoordinateBounds viewportBounds,
        CoordinateBounds contentBounds,
        boolean active) {
    /** Validates finite ranges and framebuffer geometry. */
    public LayoutScroll {
        finite(x, "x");
        finite(y, "y");
        finite(maxX, "maxX");
        finite(maxY, "maxY");
        if (maxX < 0 || maxY < 0) {
            throw new IllegalArgumentException("scroll maxima must be non-negative");
        }
        viewportBounds = LayoutSupport.space(
                Objects.requireNonNull(viewportBounds), "viewportBounds", "FRAMEBUFFER");
        contentBounds = LayoutSupport.space(
                Objects.requireNonNull(contentBounds), "contentBounds", "FRAMEBUFFER");
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
