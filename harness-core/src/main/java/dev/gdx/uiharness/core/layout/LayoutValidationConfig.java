package dev.gdx.uiharness.core.layout;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded configuration for one whole-stage layout validation.
 *
 * @param enabledChecks checks that run; false-positive-prone checks are opt-ins
 * @param minTargetWidth minimum stage width for {@code BELOW_TARGET_SIZE}
 * @param minTargetHeight minimum stage height for {@code BELOW_TARGET_SIZE}
 * @param maxAlignmentDelta maximum sibling left-edge deviation for alignment checks
 * @param minSpacing minimum sibling gap for spacing checks
 * @param failOn severity threshold that fails the CI gate
 * @param maxFindings maximum retained findings before truncation
 * @param maxNodes maximum examined nodes before incomplete coverage
 */
public record LayoutValidationConfig(
        Set<LayoutValidationCheck> enabledChecks,
        double minTargetWidth,
        double minTargetHeight,
        double maxAlignmentDelta,
        double minSpacing,
        LayoutValidationSeverity failOn,
        int maxFindings,
        int maxNodes) {
    private static final int MAX_FINDINGS = 4_096;
    private static final int MAX_NODES = 10_000;
    private static final double DEFAULT_TARGET_SIZE = 64.0;
    private static final double DEFAULT_ALIGNMENT_DELTA = 1.0;
    private static final double DEFAULT_SPACING = 1.0;

    /** High-confidence checks enabled by default. */
    public static final Set<LayoutValidationCheck> DEFAULT_CHECKS = Set.of(
            LayoutValidationCheck.OUTSIDE_VIEWPORT,
            LayoutValidationCheck.CLIPPED_TEXT,
            LayoutValidationCheck.TEXT_COLLISION,
            LayoutValidationCheck.INTERACTIVE_OVERLAP,
            LayoutValidationCheck.ZERO_SIZE,
            LayoutValidationCheck.DUPLICATE_TEST_ID,
            LayoutValidationCheck.MISSING_ACCESSIBLE_NAME,
            LayoutValidationCheck.KEYBOARD_UNREACHABLE,
            LayoutValidationCheck.OBSCURED);

    /** Validates bounds and defensively copies the enabled check set. */
    public LayoutValidationConfig {
        enabledChecks = Set.copyOf(Objects.requireNonNull(enabledChecks, "enabledChecks"));
        requireFiniteNonNegative(minTargetWidth, "minTargetWidth");
        requireFiniteNonNegative(minTargetHeight, "minTargetHeight");
        requireFiniteNonNegative(maxAlignmentDelta, "maxAlignmentDelta");
        requireFiniteNonNegative(minSpacing, "minSpacing");
        Objects.requireNonNull(failOn, "failOn");
        if (maxFindings < 1 || maxFindings > MAX_FINDINGS) {
            throw new IllegalArgumentException("maxFindings must be between 1 and " + MAX_FINDINGS);
        }
        if (maxNodes < 1 || maxNodes > MAX_NODES) {
            throw new IllegalArgumentException("maxNodes must be between 1 and " + MAX_NODES);
        }
    }

    /** Returns the shared default configuration with high-confidence checks only. */
    public static LayoutValidationConfig defaults() {
        return new LayoutValidationConfig(
                DEFAULT_CHECKS,
                DEFAULT_TARGET_SIZE,
                DEFAULT_TARGET_SIZE,
                DEFAULT_ALIGNMENT_DELTA,
                DEFAULT_SPACING,
                LayoutValidationSeverity.ERROR,
                256,
                MAX_NODES);
    }

    /** Starts a builder seeded with the default configuration. */
    public static Builder builder() {
        return new Builder(defaults());
    }

    /** Returns whether the named check is enabled. */
    public boolean isEnabled(LayoutValidationCheck check) {
        return enabledChecks.contains(Objects.requireNonNull(check, "check"));
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    /** Mutable builder for one validation configuration. */
    public static final class Builder {
        private final EnumSet<LayoutValidationCheck> checks;
        private double minTargetWidth;
        private double minTargetHeight;
        private double maxAlignmentDelta;
        private double minSpacing;
        private LayoutValidationSeverity failOn;
        private int maxFindings;
        private int maxNodes;

        private Builder(LayoutValidationConfig seed) {
            checks = EnumSet.copyOf(seed.enabledChecks());
            minTargetWidth = seed.minTargetWidth();
            minTargetHeight = seed.minTargetHeight();
            maxAlignmentDelta = seed.maxAlignmentDelta();
            minSpacing = seed.minSpacing();
            failOn = seed.failOn();
            maxFindings = seed.maxFindings();
            maxNodes = seed.maxNodes();
        }

        /** Enables one check in addition to the defaults. */
        public Builder enable(LayoutValidationCheck check) {
            checks.add(Objects.requireNonNull(check, "check"));
            return this;
        }

        /** Disables one default check. */
        public Builder disable(LayoutValidationCheck check) {
            checks.remove(Objects.requireNonNull(check, "check"));
            return this;
        }

        /** Sets the target-size thresholds. */
        public Builder minTargetSize(double width, double height) {
            minTargetWidth = width;
            minTargetHeight = height;
            return this;
        }

        /** Sets the alignment deviation threshold. */
        public Builder maxAlignmentDelta(double delta) {
            maxAlignmentDelta = delta;
            return this;
        }

        /** Sets the spacing deviation threshold. */
        public Builder minSpacing(double spacing) {
            minSpacing = spacing;
            return this;
        }

        /** Sets the CI severity gate. */
        public Builder failOn(LayoutValidationSeverity severity) {
            failOn = Objects.requireNonNull(severity, "severity");
            return this;
        }

        /** Sets the maximum retained findings. */
        public Builder maxFindings(int maximum) {
            maxFindings = maximum;
            return this;
        }

        /** Sets the maximum examined nodes. */
        public Builder maxNodes(int maximum) {
            maxNodes = maximum;
            return this;
        }

        /** Builds the immutable configuration. */
        public LayoutValidationConfig build() {
            return new LayoutValidationConfig(
                    checks, minTargetWidth, minTargetHeight, maxAlignmentDelta, minSpacing,
                    failOn, maxFindings, maxNodes);
        }
    }
}
