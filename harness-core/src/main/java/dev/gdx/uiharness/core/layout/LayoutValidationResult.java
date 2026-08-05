package dev.gdx.uiharness.core.layout;

import java.util.List;
import java.util.Objects;

/**
 * Bounded, deterministically ordered whole-stage layout validation result.
 *
 * @param status CI gate status
 * @param findings closed findings in stable order
 * @param examinedNodes number of nodes examined before any coverage cap
 * @param truncated whether findings or examined nodes were truncated
 * @param appliedConfig configuration whose thresholds produced this result
 */
public record LayoutValidationResult(
        Status status,
        List<LayoutFinding> findings,
        int examinedNodes,
        boolean truncated,
        LayoutValidationConfig appliedConfig) {
    /** Validates and defensively copies the result. */
    public LayoutValidationResult {
        Objects.requireNonNull(status, "status");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (examinedNodes < 0) {
            throw new IllegalArgumentException("examinedNodes must be non-negative");
        }
        Objects.requireNonNull(appliedConfig, "appliedConfig");
    }

    /** CI gate classification. */
    public enum Status {
        /** No finding at or above the severity gate. */
        PASS,
        /** At least one finding at or above the severity gate. */
        FAIL,
        /** Validation could not examine the full target. */
        INCOMPLETE
    }
}
