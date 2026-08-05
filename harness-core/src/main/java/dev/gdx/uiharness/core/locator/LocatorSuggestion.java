package dev.gdx.uiharness.core.locator;

import java.util.List;
import java.util.Objects;

/**
 * One diagnostic-only locator suggestion derived from the bounded evidence retained by a strict
 * lookup failure. A suggestion never triggers retry or fallback execution.
 *
 * @param locator closed locator that uniquely resolves the candidate against the same immutable
 *     observation
 * @param stability whether the locator relies on a stable automation contract
 * @param rationale fixed human-readable reason for choosing this variant
 * @param candidateIdentity stable identity of the candidate node the locator selects
 * @param distinctions bounded properties that separate this candidate from other candidates
 */
public record LocatorSuggestion(
        Locator locator,
        Stability stability,
        String rationale,
        String candidateIdentity,
        List<DistinguishingProperty> distinctions) {
    private static final int MAX_RATIONALE_LENGTH = 256;
    private static final int MAX_IDENTITY_LENGTH = 16_384;
    private static final int MAX_DISTINCTIONS = 4;

    /** Validates bounds and defensively copies the distinction list. */
    public LocatorSuggestion {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(stability, "stability");
        Objects.requireNonNull(rationale, "rationale");
        Objects.requireNonNull(candidateIdentity, "candidateIdentity");
        if (rationale.length() > MAX_RATIONALE_LENGTH) {
            throw new IllegalArgumentException("suggestion rationale is too long");
        }
        if (candidateIdentity.length() > MAX_IDENTITY_LENGTH) {
            throw new IllegalArgumentException("suggestion identity is too long");
        }
        if (distinctions.size() > MAX_DISTINCTIONS) {
            throw new IllegalArgumentException("too many distinguishing properties");
        }
        distinctions = List.copyOf(distinctions);
    }
}
