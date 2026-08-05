package dev.gdx.uiharness.core.matrix;

import java.util.Objects;

/** Compact immutable projection of a matrix case for reports; never carries assertions. */
public record MatrixCaseSummary(
        int index,
        MatrixWindow window,
        double uiScale,
        double devicePixelRatio,
        MatrixHiDpi hiDpiMode,
        String locale,
        String fontSetId,
        double aspectRatio) {
    /** Validates the projection. */
    public MatrixCaseSummary {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        Objects.requireNonNull(window, "window");
        if (!Double.isFinite(uiScale) || uiScale <= 0.0) {
            throw new IllegalArgumentException("uiScale must be finite and positive");
        }
        if (!Double.isFinite(devicePixelRatio) || devicePixelRatio <= 0.0) {
            throw new IllegalArgumentException("devicePixelRatio must be finite and positive");
        }
        Objects.requireNonNull(hiDpiMode, "hiDpiMode");
        Objects.requireNonNull(locale, "locale");
        if (!Double.isFinite(aspectRatio) || aspectRatio <= 0.0) {
            throw new IllegalArgumentException("aspectRatio must be finite and positive");
        }
    }

    /** Projects one expanded case. */
    public static MatrixCaseSummary of(MatrixCase matrixCase) {
        Objects.requireNonNull(matrixCase, "matrixCase");
        return new MatrixCaseSummary(
                matrixCase.index(),
                matrixCase.window(),
                matrixCase.uiScale(),
                matrixCase.devicePixelRatio(),
                matrixCase.hiDpiMode(),
                matrixCase.locale(),
                matrixCase.fontSetId(),
                matrixCase.aspectRatio());
    }
}
