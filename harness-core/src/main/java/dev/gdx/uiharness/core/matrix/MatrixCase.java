package dev.gdx.uiharness.core.matrix;

import dev.gdx.uiharness.core.assertion.AssertionRequest;
import java.util.List;
import java.util.Objects;

/** One expanded matrix case with derived aspect ratio and carried assertions. */
public record MatrixCase(
        int index,
        MatrixWindow window,
        double uiScale,
        double devicePixelRatio,
        MatrixHiDpi hiDpiMode,
        String locale,
        String fontSetId,
        double aspectRatio,
        List<AssertionRequest> assertions) {
    /** Validates the case and defensively copies the assertion list. */
    public MatrixCase {
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
        assertions = List.copyOf(Objects.requireNonNull(assertions, "assertions"));
    }
}
