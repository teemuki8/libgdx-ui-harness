package dev.gdx.uiharness.core.golden;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One bounded semantic difference with a closed classification.
 *
 * @param kind added, removed, changed, or ambiguous
 * @param baselineKey stable matching key
 * @param propertyPaths bounded changed property paths for changed nodes
 * @param beforeValues baseline values keyed by property path
 * @param afterValues snapshot values keyed by property path
 * @param ambiguousIdentities bounded snapshot identities for ambiguous matches
 */
public record SemanticDifference(
        Kind kind,
        String baselineKey,
        List<String> propertyPaths,
        Map<String, String> beforeValues,
        Map<String, String> afterValues,
        List<String> ambiguousIdentities) {
    private static final int MAX_PATHS = 64;

    /** Validates and defensively copies bounded evidence. */
    public SemanticDifference {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(baselineKey, "baselineKey");
        propertyPaths = List.copyOf(Objects.requireNonNull(propertyPaths, "propertyPaths"));
        if (propertyPaths.size() > MAX_PATHS) {
            throw new IllegalArgumentException("too many changed property paths");
        }
        beforeValues = Map.copyOf(Objects.requireNonNull(beforeValues, "beforeValues"));
        afterValues = Map.copyOf(Objects.requireNonNull(afterValues, "afterValues"));
        ambiguousIdentities = List.copyOf(Objects.requireNonNull(
                ambiguousIdentities, "ambiguousIdentities"));
    }

    /** Closed difference classification. */
    public enum Kind {
        /** Present in the snapshot but not the baseline. */
        ADDED,
        /** Present in the baseline but not the snapshot. */
        REMOVED,
        /** Matched but with changed constrained properties. */
        CHANGED,
        /** The stable key matched more than one snapshot node. */
        AMBIGUOUS
    }
}
