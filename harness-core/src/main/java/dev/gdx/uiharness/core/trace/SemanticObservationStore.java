package dev.gdx.uiharness.core.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded in-memory retention of semantic observations per trace, populated alongside trace
 * recording. Retaining beyond the bound drops the oldest observations and records an explicit
 * gap; archives remain opaque and are never reopened for queries.
 */
public final class SemanticObservationStore {
    private static final int DEFAULT_MAX_OBSERVATIONS = 256;
    private final int maxObservations;
    private final Map<String, List<SemanticObservation>> byTrace = new LinkedHashMap<>();

    /** Creates a store with the default bound. */
    public SemanticObservationStore() {
        this(DEFAULT_MAX_OBSERVATIONS);
    }

    /** Creates a store with an explicit observation bound. */
    public SemanticObservationStore(int maxObservations) {
        if (maxObservations < 1 || maxObservations > 1_024) {
            throw new IllegalArgumentException(
                    "maxObservations must be between 1 and 1024");
        }
        this.maxObservations = maxObservations;
    }

    /** Retains one bounded observation for a trace, dropping the oldest on overflow. */
    public synchronized void retain(String traceId, SemanticObservation observation) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(observation, "observation");
        List<SemanticObservation> observations =
                byTrace.computeIfAbsent(traceId, ignored -> new ArrayList<>());
        observations.add(observation);
        while (observations.size() > maxObservations) {
            observations.remove(0);
        }
    }

    /** Returns a bounded immutable copy of the retained observations in sequence order. */
    public synchronized List<SemanticObservation> observations(String traceId) {
        Objects.requireNonNull(traceId, "traceId");
        List<SemanticObservation> retained = byTrace.get(traceId);
        return retained == null ? List.of() : List.copyOf(retained);
    }

    /** Drops all retained observations for one trace. */
    public synchronized void drop(String traceId) {
        byTrace.remove(Objects.requireNonNull(traceId, "traceId"));
    }
}
