package dev.gdx.uiharness.core.typography;

import java.util.List;
import java.util.Objects;

/** Immutable typography diagnosis with separated evidence categories. */
public record TypographyReport(
        String schemaVersion,
        TypographyStatus status,
        TypographyObservation observation,
        List<TypographyDiagnostic> diagnostics,
        List<String> sourceMechanisms,
        List<String> controlledResults,
        List<String> unresolvedHypotheses) {

    /** Defensively copies all report collections. */
    public TypographyReport {
        if (!"typography/v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be typography/v1");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observation, "observation");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        sourceMechanisms =
                List.copyOf(Objects.requireNonNull(sourceMechanisms, "sourceMechanisms"));
        controlledResults =
                List.copyOf(Objects.requireNonNull(controlledResults, "controlledResults"));
        unresolvedHypotheses =
                List.copyOf(Objects.requireNonNull(unresolvedHypotheses, "unresolvedHypotheses"));
    }
}
