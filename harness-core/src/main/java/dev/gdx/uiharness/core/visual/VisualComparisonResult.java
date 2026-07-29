package dev.gdx.uiharness.core.visual;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Complete bounded result of one inspect-capture-compare invocation. */
public record VisualComparisonResult(
        ComparisonStatus status,
        VisualPolicy policy,
        VisualReference reference,
        CurrentVisualEvidence current,
        VisualMetrics metrics,
        List<VisualDifference> differences,
        List<ComparisonDiagnostic> diagnostics,
        int iterations,
        Duration elapsed) {
    /** Validates status-specific evidence and defensively copies ordered diagnostics. */
    public VisualComparisonResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(policy, "policy");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(elapsed, "elapsed");
        if (iterations < 0 || iterations > 64 || elapsed.isNegative()) {
            throw new IllegalArgumentException("invalid comparison execution bounds");
        }
        if (status == ComparisonStatus.CONVERGED) {
            if (reference == null || current == null || metrics == null
                    || !diagnostics.isEmpty()
                    || differences.stream().anyMatch(VisualDifference::blocking)
                    || metrics.differingPixels() > policy.maxDifferingPixels()
                    || metrics.meanAbsoluteError() > policy.maxMeanAbsoluteError()
                    || !compatible(reference, current, policy)) {
                throw new IllegalArgumentException(
                        "converged status requires fresh compatible passing evidence");
            }
        }
        if ((status == ComparisonStatus.INCOMPLETE || status == ComparisonStatus.STALE)
                && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "incomplete or stale status requires a diagnostic");
        }
    }

    /** Creates an incomplete result that cannot contain accepted current evidence. */
    public static VisualComparisonResult incomplete(
            VisualPolicy policy,
            VisualReference reference,
            List<ComparisonDiagnostic> diagnostics,
            Duration elapsed) {
        return new VisualComparisonResult(
                ComparisonStatus.INCOMPLETE, policy, reference, null, null,
                List.of(), diagnostics, 0, elapsed);
    }

    /** Returns whether application, viewport, dimensions, and required scale are compatible. */
    public static boolean compatible(
            VisualReference reference,
            CurrentVisualEvidence current,
            VisualPolicy policy) {
        if (!reference.applicationId().equals(current.applicationId())
                || !reference.viewportId().equals(current.viewportId())) {
            return false;
        }
        if (policy.requireExactViewport()
                && (reference.width() != current.image().width()
                || reference.height() != current.image().height())) {
            return false;
        }
        return !policy.requireExactScale()
                || reference.scale().equals(current.image().scale());
    }
}
