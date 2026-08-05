package dev.gdx.uiharness.core.matrix;

import java.util.List;
import java.util.Objects;

/**
 * Terminal result of one matrix case with exact requested and observed display parameters.
 *
 * @param caseSummary compact case projection without carried assertions
 * @param status terminal classification
 * @param observedWindow observed window geometry, or {@code null} when not observed
 * @param observedUiScale observed UI scale, or {@code null} when not observed
 * @param observedDevicePixelRatio observed device pixel ratio, or {@code null} when not observed
 * @param observedHiDpiMode observed HiDPI mode, or {@code null} when not observed
 * @param passedAssertions zero-based indices of passed carried assertions
 * @param failedAssertions zero-based indices of failed carried assertions
 * @param artifactReferences opaque bounded artifact references bound to this case
 * @param evidence bounded human-readable failure evidence
 */
public record MatrixCaseResult(
        MatrixCaseSummary caseSummary,
        MatrixCaseStatus status,
        MatrixWindow observedWindow,
        Double observedUiScale,
        Double observedDevicePixelRatio,
        MatrixHiDpi observedHiDpiMode,
        List<Integer> passedAssertions,
        List<Integer> failedAssertions,
        List<String> artifactReferences,
        String evidence) {
    private static final int MAX_ARTIFACTS = 64;
    private static final int MAX_EVIDENCE_LENGTH = 4_096;

    /** Validates and defensively copies the result. */
    public MatrixCaseResult {
        Objects.requireNonNull(caseSummary, "caseSummary");
        Objects.requireNonNull(status, "status");
        if (observedUiScale != null
                && (!Double.isFinite(observedUiScale) || observedUiScale <= 0.0)) {
            throw new IllegalArgumentException("observed uiScale must be finite and positive");
        }
        if (observedDevicePixelRatio != null
                && (!Double.isFinite(observedDevicePixelRatio)
                        || observedDevicePixelRatio <= 0.0)) {
            throw new IllegalArgumentException(
                    "observed devicePixelRatio must be finite and positive");
        }
        passedAssertions = List.copyOf(Objects.requireNonNull(
                passedAssertions, "passedAssertions"));
        failedAssertions = List.copyOf(Objects.requireNonNull(
                failedAssertions, "failedAssertions"));
        artifactReferences = List.copyOf(Objects.requireNonNull(
                artifactReferences, "artifactReferences"));
        if (artifactReferences.size() > MAX_ARTIFACTS) {
            throw new IllegalArgumentException("too many case artifact references");
        }
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.length() > MAX_EVIDENCE_LENGTH) {
            throw new IllegalArgumentException("case evidence exceeds 4096 characters");
        }
    }
}
