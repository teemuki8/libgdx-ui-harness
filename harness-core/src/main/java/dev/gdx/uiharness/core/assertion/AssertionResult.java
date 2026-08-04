package dev.gdx.uiharness.core.assertion;

import java.util.Objects;

/** Immutable result of one assertion evaluation. */
public record AssertionResult(Status status, AssertionEvidence evidence, long elapsedNanos) {
    public AssertionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidence, "evidence");
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException("elapsedNanos must be non-negative");
        }
    }

    public enum Status { PASSED, FAILED }
}
