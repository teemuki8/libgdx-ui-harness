package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.visual.ComparisonDiagnostic;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Fresh capture, layout reports, settling proof, and elapsed time. */
public record LayoutDiagnosticResult(
        LayoutStatus status,
        LayoutReference reference,
        CapturedImage current,
        List<LayoutReport> reports,
        LayoutQuiescenceResult settling,
        LayoutQuiescenceResult captures,
        List<ComparisonDiagnostic> diagnostics,
        Duration elapsed) {
    /** Copies bounded reports. */
    public LayoutDiagnosticResult {
        Objects.requireNonNull(status, "status");
        reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (reports.size() > 256 || diagnostics.size() > 256) {
            throw new IllegalArgumentException("layout result exceeds 256 entries");
        }
        Objects.requireNonNull(elapsed, "elapsed");
    }
}
