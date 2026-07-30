package dev.gdx.uiharness.core.typography;

import dev.gdx.uiharness.core.capture.CaptureRequest;
import java.time.Duration;
import java.util.Objects;

/** Bounded request for one capture-backed typography diagnosis. */
public record TypographyDiagnosticRequest(
        String referenceId,
        String viewportId,
        Duration maxDuration,
        int maxResults,
        CaptureRequest.Limits captureLimits) {

    /** Validates identities and trust-boundary limits. */
    public TypographyDiagnosticRequest {
        requireNonBlank(referenceId, "referenceId");
        requireNonBlank(viewportId, "viewportId");
        maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.isZero() || maxDuration.isNegative()
                || maxDuration.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("maxDuration must be between 1ns and 2 minutes");
        }
        if (maxResults <= 0 || maxResults > 256) {
            throw new IllegalArgumentException("maxResults must be between 1 and 256");
        }
        Objects.requireNonNull(captureLimits, "captureLimits");
    }

    private static void requireNonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
