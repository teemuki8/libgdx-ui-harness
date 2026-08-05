package dev.gdx.uiharness.core.golden;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic bounded semantic comparison result. Ambiguous matches never produce a pass.
 */
public record SemanticCompareResult(
        boolean matched,
        List<SemanticDifference> differences,
        int comparedNodes,
        boolean truncated,
        Set<String> appliedExclusions) {
    /** Validates and defensively copies the result. */
    public SemanticCompareResult {
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        if (comparedNodes < 0) {
            throw new IllegalArgumentException("comparedNodes must be non-negative");
        }
        appliedExclusions = Set.copyOf(Objects.requireNonNull(
                appliedExclusions, "appliedExclusions"));
    }
}
