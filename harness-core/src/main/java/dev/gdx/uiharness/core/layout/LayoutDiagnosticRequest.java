package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.capture.CaptureRequest;
import java.time.Duration;
import java.util.Objects;

/** Bounded request for one fresh layout diagnosis. */
public record LayoutDiagnosticRequest(
        String referenceId,
        String viewportId,
        Duration maxDuration,
        int maxResults,
        CaptureRequest capture) {
    /** Validates finite identity, duration, result count, and capture limits. */
    public LayoutDiagnosticRequest {
        LayoutSupport.nonBlank(referenceId, "referenceId");
        LayoutSupport.nonBlank(viewportId, "viewportId");
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.isZero() || maxDuration.isNegative()
                || maxDuration.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException(
                    "maxDuration must be between 1 ns and 2 seconds");
        }
        if (maxResults < 1 || maxResults > 256) {
            throw new IllegalArgumentException("maxResults must be between 1 and 256");
        }
        Objects.requireNonNull(capture, "capture");
    }
}
