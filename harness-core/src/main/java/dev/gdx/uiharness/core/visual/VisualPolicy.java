package dev.gdx.uiharness.core.visual;

import java.util.Objects;

/** Named and versioned deterministic convergence policy. */
public record VisualPolicy(
        String id,
        int version,
        long maxDifferingPixels,
        double maxMeanAbsoluteError,
        boolean requireExactViewport,
        boolean requireExactScale) {
    /** Validates policy identity and finite non-negative thresholds. */
    public VisualPolicy {
        VisualSupport.identifier(id, "policy id");
        if (id.length() > 240 || version <= 0 || maxDifferingPixels < 0
                || !Double.isFinite(maxMeanAbsoluteError)
                || maxMeanAbsoluteError < 0) {
            throw new IllegalArgumentException("invalid visual policy limits");
        }
    }

    /** Returns the conservative exact-pixel V1 policy. */
    public static VisualPolicy pixelExactV1() {
        return new VisualPolicy("pixel-exact", 1, 0, 0, true, true);
    }

    /** Returns the stable compound policy identity. */
    public String wireName() {
        return Objects.requireNonNull(id) + "/v" + version;
    }
}
