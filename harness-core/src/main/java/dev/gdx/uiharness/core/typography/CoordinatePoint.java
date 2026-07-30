package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/** A finite point in one explicitly named coordinate space. */
public record CoordinatePoint(CoordinateSpace space, double x, double y) {
    /** Validates the coordinate space and finite components. */
    public CoordinatePoint {
        Objects.requireNonNull(space, "space");
        TypographySupport.requireFinite(x, "x");
        TypographySupport.requireFinite(y, "y");
    }
}
