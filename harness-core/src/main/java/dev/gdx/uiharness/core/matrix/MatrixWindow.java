package dev.gdx.uiharness.core.matrix;

/** Authoritative window geometry for one matrix case. */
public record MatrixWindow(int width, int height) {
    /** Validates positive window geometry. */
    public MatrixWindow {
        if (width < 1) {
            throw new IllegalArgumentException("window width must be positive");
        }
        if (height < 1) {
            throw new IllegalArgumentException("window height must be positive");
        }
    }
}
