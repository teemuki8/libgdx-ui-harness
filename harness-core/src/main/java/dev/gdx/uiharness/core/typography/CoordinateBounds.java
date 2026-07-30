package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/** A finite non-negative rectangle in one explicitly named coordinate space. */
public record CoordinateBounds(
        CoordinateSpace space, double x, double y, double width, double height) {
    /** Validates finite components and non-negative dimensions. */
    public CoordinateBounds {
        Objects.requireNonNull(space, "space");
        TypographySupport.requireFinite(x, "x");
        TypographySupport.requireFinite(y, "y");
        TypographySupport.requireNonNegativeFinite(width, "width");
        TypographySupport.requireNonNegativeFinite(height, "height");
    }
}
