package dev.gdx.uiharness.core.matrix;

import java.util.List;
import java.util.Objects;

/**
 * Compact immutable report of one matrix run. Never embeds screenshots; large evidence stays
 * behind opaque artifact references.
 */
public record MatrixReport(
        String runId,
        String scenarioId,
        List<MatrixCaseResult> results,
        boolean truncated) {
    private static final int MAX_RESULTS = 10_000;

    /** Validates the run identity and bounds the result list. */
    public MatrixReport {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(scenarioId, "scenarioId");
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        if (results.size() > MAX_RESULTS) {
            throw new IllegalArgumentException("matrix report exceeds 10000 results");
        }
    }
}
