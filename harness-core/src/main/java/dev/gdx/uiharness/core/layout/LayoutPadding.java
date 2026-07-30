package dev.gdx.uiharness.core.layout;

/** Padding in logical pixels, ordered by physical edge. */
public record LayoutPadding(double top, double right, double bottom, double left) {
    /** Requires finite non-negative edges. */
    public LayoutPadding {
        require(top, "top");
        require(right, "right");
        require(bottom, "bottom");
        require(left, "left");
    }

    private static void require(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
