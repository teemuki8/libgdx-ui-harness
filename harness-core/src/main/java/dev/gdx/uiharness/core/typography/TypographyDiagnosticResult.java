package dev.gdx.uiharness.core.typography;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.visual.ComparisonDiagnostic;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Aggregate capture-backed typography result for one named reference. */
public record TypographyDiagnosticResult(
        TypographyStatus status,
        TypographyReference reference,
        CapturedImage current,
        List<TypographyReport> reports,
        List<ComparisonDiagnostic> diagnostics,
        Duration elapsed) {

    /** Defensively copies reports and service-level diagnostics. */
    public TypographyDiagnosticResult {
        Objects.requireNonNull(status, "status");
        reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(elapsed, "elapsed");
    }
}
