package dev.gdx.uiharness.core.layout;

import java.util.List;
import java.util.Objects;

/** Immutable actor-attributed layout evaluation. */
public record LayoutReport(
        String schemaVersion,
        LayoutStatus status,
        LayoutObservation observation,
        List<LayoutDiagnostic> diagnostics) {
    /** Validates the V1 shape and bounds diagnostics. */
    public LayoutReport {
        if (!"layout/v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be layout/v1");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observation, "observation");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (diagnostics.size() > 256) {
            throw new IllegalArgumentException("diagnostics exceeds 256 entries");
        }
    }
}
