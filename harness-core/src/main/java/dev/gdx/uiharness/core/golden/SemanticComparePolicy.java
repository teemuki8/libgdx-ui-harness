package dev.gdx.uiharness.core.golden;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded comparison policy: named positional tolerances, allowlisted volatile-property
 * exclusions, and result bounds. Exclusions can never remove identity fields.
 */
public record SemanticComparePolicy(
        List<PositionalTolerance> tolerances,
        Set<String> excludedProperties,
        int maxDifferences,
        int maxStringLength) {
    /** Identity fields that can never be excluded. */
    public static final Set<String> PROTECTED_FIELDS = Set.of(
            "role", "accessibleName", "testId", "label");
    private static final int MAX_TOLERANCES = 16;
    private static final int MAX_EXCLUSIONS = 256;
    private static final int MAX_DIFFERENCES = 4_096;
    private static final int MAX_STRING = 16_384;

    /** Validates bounds and rejects identity-field exclusions. */
    public SemanticComparePolicy {
        tolerances = List.copyOf(Objects.requireNonNull(tolerances, "tolerances"));
        if (tolerances.size() > MAX_TOLERANCES) {
            throw new IllegalArgumentException("too many positional tolerances");
        }
        excludedProperties = Set.copyOf(Objects.requireNonNull(
                excludedProperties, "excludedProperties"));
        if (excludedProperties.size() > MAX_EXCLUSIONS) {
            throw new IllegalArgumentException("too many excluded properties");
        }
        for (String excluded : excludedProperties) {
            if (PROTECTED_FIELDS.contains(excluded)) {
                throw new IllegalArgumentException(
                        "identity field cannot be excluded: " + excluded);
            }
        }
        if (maxDifferences < 1 || maxDifferences > MAX_DIFFERENCES) {
            throw new IllegalArgumentException(
                    "maxDifferences must be between 1 and " + MAX_DIFFERENCES);
        }
        if (maxStringLength < 1 || maxStringLength > MAX_STRING) {
            throw new IllegalArgumentException(
                    "maxStringLength must be between 1 and " + MAX_STRING);
        }
    }

    /** Returns the shared default policy. */
    public static SemanticComparePolicy defaults() {
        return new SemanticComparePolicy(
                List.of(), Set.of(), MAX_DIFFERENCES, MAX_STRING);
    }
}
