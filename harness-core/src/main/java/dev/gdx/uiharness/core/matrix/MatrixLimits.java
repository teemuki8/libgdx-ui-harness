package dev.gdx.uiharness.core.matrix;

import java.util.Objects;

/** Hard bounds applied to one display matrix run. */
public record MatrixLimits(int maxCases) {
    private static final int MAX_CASES = 10_000;

    /** Validates the case bound. */
    public MatrixLimits {
        if (maxCases < 1 || maxCases > MAX_CASES) {
            throw new IllegalArgumentException("maxCases must be between 1 and " + MAX_CASES);
        }
    }

    /** Returns the shared default limits. */
    public static MatrixLimits defaults() {
        return new MatrixLimits(MAX_CASES);
    }

    /** Starts a builder seeded with the defaults. */
    public static Builder builder() {
        return new Builder(defaults());
    }

    /** Mutable builder for matrix limits. */
    public static final class Builder {
        private int maxCases;

        private Builder(MatrixLimits seed) {
            maxCases = seed.maxCases();
        }

        /** Sets the maximum expanded cases. */
        public Builder maxCases(int maximum) {
            maxCases = maximum;
            return this;
        }

        /** Builds the immutable limits. */
        public MatrixLimits build() {
            return new MatrixLimits(maxCases);
        }
    }
}
