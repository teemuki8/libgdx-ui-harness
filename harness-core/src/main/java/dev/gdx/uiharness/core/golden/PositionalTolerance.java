package dev.gdx.uiharness.core.golden;

import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.util.Objects;

/**
 * Named positional tolerance in one explicit coordinate space and unit. Tolerances never hide
 * semantic role, name, identity, or state mismatches.
 */
public record PositionalTolerance(
        String id,
        CoordinateSpace space,
        String units,
        double deltaX,
        double deltaY,
        double deltaWidth,
        double deltaHeight) {
    /** Validates the named tolerance. */
    public PositionalTolerance {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("tolerance id must not be blank");
        }
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(units, "units");
        if (units.isBlank()) {
            throw new IllegalArgumentException("tolerance units must not be blank");
        }
        for (double value : new double[] {deltaX, deltaY, deltaWidth, deltaHeight}) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("tolerance deltas must be finite and non-negative");
            }
        }
    }
}
