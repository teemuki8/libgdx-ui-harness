package dev.gdx.uiharness.core.golden;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory application-registered catalog of semantic baselines. Identifiers are bounded
 * strings; no filesystem path or external source is accepted.
 */
public final class SemanticBaselineCatalog {
    private final Map<String, SemanticBaseline> byId = new LinkedHashMap<>();

    /** Registers or replaces one immutable baseline by its bounded identifier. */
    public void register(SemanticBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        byId.put(baseline.id(), baseline);
    }

    /** Requires a registered baseline or throws with the missing identifier. */
    public SemanticBaseline require(String id) {
        Objects.requireNonNull(id, "id");
        SemanticBaseline baseline = byId.get(id);
        if (baseline == null) {
            throw new IllegalArgumentException("unknown semantic baseline: " + id);
        }
        return baseline;
    }

    /** Returns whether the named baseline is registered. */
    public boolean contains(String id) {
        return byId.containsKey(Objects.requireNonNull(id, "id"));
    }
}
