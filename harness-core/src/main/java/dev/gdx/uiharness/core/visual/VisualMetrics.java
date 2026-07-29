package dev.gdx.uiharness.core.visual;

/** Deterministic raster measurements retained separately from the convergence decision. */
public record VisualMetrics(
        long differingPixels, double meanAbsoluteError, int maximumChannelDelta) {
    /** Validates finite non-negative raster measurements. */
    public VisualMetrics {
        if (differingPixels < 0 || !Double.isFinite(meanAbsoluteError)
                || meanAbsoluteError < 0 || maximumChannelDelta < 0
                || maximumChannelDelta > 255) {
            throw new IllegalArgumentException("invalid visual metrics");
        }
    }
}
