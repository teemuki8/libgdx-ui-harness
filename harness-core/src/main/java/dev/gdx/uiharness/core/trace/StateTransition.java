package dev.gdx.uiharness.core.trace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One compact transition projected from adjacent retained semantic observations. Causality is
 * reported only when the retained trace proves it.
 */
public record StateTransition(
        TransitionKind kind,
        long beforeSequence,
        long afterSequence,
        long beforeFrame,
        long afterFrame,
        long beforeRevision,
        long afterRevision,
        String actorIdentity,
        List<String> propertyPaths,
        Map<String, String> beforeValues,
        Map<String, String> afterValues,
        Long causeSequence) {
    private static final int MAX_PATHS = 16;

    /** Validates and defensively copies the transition. */
    public StateTransition {
        Objects.requireNonNull(kind, "kind");
        if (beforeSequence < 0 || afterSequence <= beforeSequence) {
            throw new IllegalArgumentException("sequences must be non-negative and advance");
        }
        if (beforeFrame < 0 || afterFrame < beforeFrame
                || beforeRevision < 0 || afterRevision < beforeRevision) {
            throw new IllegalArgumentException("frames and revisions must not regress");
        }
        Objects.requireNonNull(actorIdentity, "actorIdentity");
        propertyPaths = List.copyOf(Objects.requireNonNull(propertyPaths, "propertyPaths"));
        if (propertyPaths.size() > MAX_PATHS) {
            throw new IllegalArgumentException("too many transition property paths");
        }
        beforeValues = Map.copyOf(Objects.requireNonNull(beforeValues, "beforeValues"));
        afterValues = Map.copyOf(Objects.requireNonNull(afterValues, "afterValues"));
        if (causeSequence != null && causeSequence < 0) {
            throw new IllegalArgumentException("causeSequence must be non-negative");
        }
    }
}
