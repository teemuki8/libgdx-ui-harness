package dev.gdx.uiharness.core.locator;

import dev.gdx.uiharness.core.model.SemanticNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded immutable locator result.
 *
 * @param matches matching nodes in deterministic document order
 * @param evidence bounded query diagnostics, including fragile index use
 */
public record QueryResult(List<SemanticNode> matches, List<Map<String, String>> evidence) {
    /** Defensively copies matches and recursively copies evidence maps. */
    public QueryResult {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        Objects.requireNonNull(evidence, "evidence");
        evidence = evidence.stream().map(Map::copyOf).toList();
    }

    /** Returns whether this result came from a structurally fragile index locator. */
    public boolean fragileIndex() {
        return evidence.stream().anyMatch(item -> "fragile-index".equals(item.get("kind")));
    }
}
