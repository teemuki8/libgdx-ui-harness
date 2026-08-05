package dev.gdx.uiharness.core.matrix;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal result of one matrix case with exact requested and observed display parameters.
 *
 * @param caseDefinition the expanded case
 * @param status terminal classification
 * @param observedWindow observed window geometry, when available
 * @param observedUiScale observed UI scale, when available
 * @param observedDevicePixelRatio observed device pixel ratio, when available
 * @param observedHiDpiMode observed HiDPI mode, when available
 * @param passedAssertions zero-based indices of passed carried assertions
 * @param failedAssertions zero-based indices of failed carried assertions
 * @param artifactReferences opaque bounded artifact references bound to this case
 * @param evidence bounded human-readable failure evidence
 */
public record MatrixCaseResult(
        MatrixCase caseDefinition,
        MatrixCaseStatus status,
        Optional<MatrixWindow> observedWindow,
        Optional<Double> observedUiScale,
        Optional<Double> observedDevicePixelRatio,
        Optional<MatrixHiDpi> observedHiDpiMode,
        List<Integer> passedAssertions,
        List<Integer> failedAssertions,
        List<String> artifactReferences,
        String evidence) {
    private static final int MAX_ARTIFACTS = 64;
    private static final int MAX_EVIDENCE_LENGTH = 4_096;

    /** Validates and defensively copies the result. */
    public MatrixCaseResult {
        Objects.requireNonNull(caseDefinition, "caseDefinition");
        Objects.requireNonNull(status, "status");
        observedWindow = Objects.requireNonNull(observedWindow, "observedWindow");
        observedUiScale = Objects.requireNonNull(observedUiScale, "observedUiScale");
        observedDevicePixelRatio =
                Objects.requireNonNull(observedDevicePixelRatio, "observedDevicePixelRatio");
        observedHiDpiMode = Objects.requireNonNull(observedHiDpiMode, "observedHiDpiMode");
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
