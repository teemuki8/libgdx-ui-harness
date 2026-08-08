package dev.gdx.uiharness.core.golden;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory application-registered catalog of immutable semantic baselines. Identifiers are
 * bounded strings; no filesystem path or external source is accepted. Registration validates
 * the canonical digest over the complete versioned baseline and rejects conflicting
 * replacement under an existing identifier.
 */
public final class SemanticBaselineCatalog {
    private final Map<String, SemanticBaseline> byId = new LinkedHashMap<>();

    /**
     * Registers one immutable baseline, validating its digest and rejecting conflicts. The
     * check-then-insert is synchronized so concurrent registrations are deterministic:
     * conflicting registrations under one identifier succeed exactly once, identical
     * registrations are idempotent.
     */
    public synchronized void register(SemanticBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        if (!baseline.digest().equals(BaselineDigest.canonical(baseline))) {
            throw new IllegalArgumentException(
                    "semantic baseline digest mismatch for " + baseline.id());
        }
        SemanticBaseline existing = byId.get(baseline.id());
        if (existing != null && !existing.digest().equals(baseline.digest())) {
            throw new IllegalArgumentException(
                    "conflicting replacement for immutable semantic baseline " + baseline.id());
        }
        byId.putIfAbsent(baseline.id(), baseline);
    }

    /** Requires a registered baseline or throws with the missing identifier. */
    public synchronized SemanticBaseline require(String id) {
        Objects.requireNonNull(id, "id");
        SemanticBaseline baseline = byId.get(id);
        if (baseline == null) {
            throw new IllegalArgumentException("unknown semantic baseline: " + id);
        }
        return baseline;
    }

    /** Returns whether the named baseline is registered. */
    public synchronized boolean contains(String id) {
        return byId.containsKey(Objects.requireNonNull(id, "id"));
    }
}
