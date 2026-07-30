package dev.gdx.uiharness.core.layout;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Settling classification with the exact retained samples. */
public record LayoutQuiescenceResult(
        boolean settled,
        String status,
        int stableFrameCount,
        Duration elapsed,
        List<LayoutStabilitySample> samples) {
    /** Validates immutable bounded evidence. */
    public LayoutQuiescenceResult {
        LayoutSupport.nonBlank(status, "status");
        if (stableFrameCount < 0) {
            throw new IllegalArgumentException("stableFrameCount must be non-negative");
        }
        Objects.requireNonNull(elapsed, "elapsed");
        samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
        if (samples.size() > 125) {
            throw new IllegalArgumentException("samples exceeds fixed issue policy");
        }
    }
}
