package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/** One bounded text range and its actor-local glyph geometry. */
public record GlyphRunObservation(
        int textStart,
        int textEnd,
        String text,
        CoordinatePoint origin,
        CoordinatePoint baseline,
        CoordinateBounds inkBounds) {

    /** Validates the range and requires actor-local geometry. */
    public GlyphRunObservation {
        Objects.requireNonNull(text, "text");
        if (textStart < 0 || textEnd < textStart || textEnd > text.length()) {
            throw new IllegalArgumentException("glyph-run range must be within text");
        }
        if (Objects.requireNonNull(origin, "origin").space() != CoordinateSpace.LOCAL
                || Objects.requireNonNull(baseline, "baseline").space()
                        != CoordinateSpace.LOCAL
                || Objects.requireNonNull(inkBounds, "inkBounds").space()
                        != CoordinateSpace.LOCAL) {
            throw new IllegalArgumentException("glyph-run geometry must use local space");
        }
    }
}
