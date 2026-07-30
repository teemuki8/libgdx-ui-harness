package dev.gdx.uiharness.core.layout;

import java.time.Duration;
import java.util.Objects;

/** Finite rendered-frame and monotonic-time bounds for layout settling. */
public record LayoutQuiescencePolicy(
        int consecutiveStableFrames,
        int maxFrames,
        Duration maxDuration,
        int captureFrames) {
    /** Validates finite positive limits. */
    public LayoutQuiescencePolicy {
        if (consecutiveStableFrames < 2 || maxFrames < consecutiveStableFrames
                || captureFrames < 1) {
            throw new IllegalArgumentException("quiescence frame limits are invalid");
        }
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }

    /** Issue #4 fixed policy: three stable frames, 120 frames/two seconds, five captures. */
    public static LayoutQuiescencePolicy issueFour() {
        return new LayoutQuiescencePolicy(3, 120, Duration.ofSeconds(2), 5);
    }
}
