package dev.gdx.uiharness.core.model;

/**
 * An axis-aligned rectangle in a declared coordinate space.
 *
 * @param x finite horizontal coordinate
 * @param y finite vertical coordinate
 * @param width finite, non-negative width
 * @param height finite, non-negative height
 */
public record Bounds(double x, double y, double width, double height) {
    /** Validates the rectangle components. */
    public Bounds {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(width, "width");
        requireFinite(height, "height");
        if (width < 0.0) {
            throw new IllegalArgumentException("width must be non-negative");
        }
        if (height < 0.0) {
            throw new IllegalArgumentException("height must be non-negative");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
