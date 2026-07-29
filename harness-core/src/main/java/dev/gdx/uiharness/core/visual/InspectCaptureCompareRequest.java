package dev.gdx.uiharness.core.visual;

import dev.gdx.uiharness.core.capture.CaptureRequest;
import java.time.Duration;
import java.util.Objects;

/** Explicit bounds and allowlisted identities for one full inspect-capture-compare invocation. */
public record InspectCaptureCompareRequest(
        String referenceId,
        String policyId,
        int policyVersion,
        String viewportId,
        int maxIterations,
        Duration maxDuration,
        CaptureRequest.Limits captureLimits) {
    /** Validates all identities and hard operation bounds. */
    public InspectCaptureCompareRequest {
        VisualSupport.identifier(referenceId, "referenceId");
        VisualSupport.identifier(policyId, "policyId");
        VisualSupport.identifier(viewportId, "viewportId");
        if (policyId.length() > 240
                || policyVersion <= 0 || maxIterations <= 0 || maxIterations > 64) {
            throw new IllegalArgumentException(
                    "policyVersion and maxIterations must be positive and bounded");
        }
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.isZero() || maxDuration.isNegative()
                || maxDuration.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException(
                    "maxDuration must be between 1 nanosecond and 2 minutes");
        }
        Objects.requireNonNull(captureLimits, "captureLimits");
    }
}
