package dev.gdx.uiharness.core.trace;

import java.util.List;
import java.util.Objects;

/** Bounded deterministic projection result with explicit gaps and truncation. */
public record TransitionQueryResult(
        String traceId,
        List<StateTransition> transitions,
        boolean truncated,
        int gapCount,
        int unknownCauseCount) {
    /** Validates and defensively copies the result. */
    public TransitionQueryResult {
        Objects.requireNonNull(traceId, "traceId");
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        if (gapCount < 0 || unknownCauseCount < 0) {
            throw new IllegalArgumentException("gap and cause counts must be non-negative");
        }
    }
}
