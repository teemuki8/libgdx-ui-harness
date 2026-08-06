package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.locator.Locator;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded transition query over one retained trace. Filters are allowlisted and bounded; they
 * never trigger an unbounded archive scan.
 */
public record TransitionQuery(
        String traceId,
        Locator locator,
        Set<TransitionKind> kinds,
        Set<String> propertyPaths,
        Long frameFrom,
        Long frameTo,
        int maxTransitions,
        int maxEvidenceBytes) {
    private static final int MAX_KINDS = 16;
    private static final int MAX_PROPERTY_PATHS = 16;
    private static final int MAX_TRANSITIONS = 4_096;
    private static final int MAX_BYTES = 1_048_576;

    /** Validates the query bounds. */
    public TransitionQuery {
        Objects.requireNonNull(traceId, "traceId");
        if (traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds"));
        if (kinds.size() > MAX_KINDS) {
            throw new IllegalArgumentException("too many transition kinds");
        }
        propertyPaths = Set.copyOf(Objects.requireNonNull(propertyPaths, "propertyPaths"));
        if (propertyPaths.size() > MAX_PROPERTY_PATHS) {
            throw new IllegalArgumentException("too many property paths");
        }
        if (frameFrom != null && frameFrom < 0) {
            throw new IllegalArgumentException("frameFrom must be non-negative");
        }
        if (frameTo != null && frameTo < 0) {
            throw new IllegalArgumentException("frameTo must be non-negative");
        }
        if (frameFrom != null && frameTo != null && frameFrom > frameTo) {
            throw new IllegalArgumentException("frameFrom must not exceed frameTo");
        }
        if (maxTransitions < 1 || maxTransitions > MAX_TRANSITIONS) {
            throw new IllegalArgumentException(
                    "maxTransitions must be between 1 and " + MAX_TRANSITIONS);
        }
        if (maxEvidenceBytes < 1 || maxEvidenceBytes > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "maxEvidenceBytes must be between 1 and " + MAX_BYTES);
        }
    }
}
