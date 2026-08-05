package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.locator.Stability;
import java.util.List;
import java.util.Objects;

/**
 * Closed protocol representation of one diagnostic locator suggestion attached to a strict
 * lookup failure. The locator uses the existing recursive locator schema and never triggers
 * retry or fallback execution.
 *
 * @param locator closed locator that uniquely selects the candidate
 * @param stability whether the locator relies on a stable automation contract
 * @param rationale fixed human-readable reason for choosing this variant
 * @param candidateIdentity stable identity of the candidate the locator selects
 * @param distinctions bounded properties that separate this candidate from other candidates
 */
public record LocatorSuggestionSpec(
        Command.LocatorSpec locator,
        Stability stability,
        String rationale,
        String candidateIdentity,
        List<DistinguishingPropertySpec> distinctions) {
    private static final int MAX_DISTINCTIONS = 4;

    /** Validates bounds and defensively copies the distinction list. */
    public LocatorSuggestionSpec {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(stability, "stability");
        ProtocolJson.requireText(rationale, "rationale");
        ProtocolJson.requireText(candidateIdentity, "candidateIdentity");
        Objects.requireNonNull(distinctions, "distinctions");
        if (distinctions.size() > MAX_DISTINCTIONS) {
            throw new IllegalArgumentException("too many distinguishing properties");
        }
        distinctions = List.copyOf(distinctions);
    }
}
