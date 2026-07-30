package dev.gdx.uiharness.core.typography;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Glyph origins, baselines, bounds, and framebuffer alignment residuals. */
public record TypographyGeometry(
        List<CoordinatePoint> origins,
        List<CoordinatePoint> baselines,
        List<CoordinateBounds> layoutBounds,
        List<CoordinateBounds> inkBounds,
        double fractionalTranslationX,
        double fractionalTranslationY) {
    private static final EnumSet<CoordinateSpace> REQUIRED_SPACES =
            EnumSet.of(
                    CoordinateSpace.LOCAL,
                    CoordinateSpace.STAGE,
                    CoordinateSpace.SCREEN,
                    CoordinateSpace.FRAMEBUFFER);

    /** Defensively copies geometry and requires one value in every published space. */
    public TypographyGeometry {
        origins = List.copyOf(Objects.requireNonNull(origins, "origins"));
        baselines = List.copyOf(Objects.requireNonNull(baselines, "baselines"));
        layoutBounds = List.copyOf(Objects.requireNonNull(layoutBounds, "layoutBounds"));
        inkBounds = List.copyOf(Objects.requireNonNull(inkBounds, "inkBounds"));
        requireSpaces(origins.stream().map(CoordinatePoint::space).toList(), "origins");
        requireSpaces(baselines.stream().map(CoordinatePoint::space).toList(), "baselines");
        requireSpaces(
                layoutBounds.stream().map(CoordinateBounds::space).toList(),
                "layoutBounds");
        requireSpaces(
                inkBounds.stream().map(CoordinateBounds::space).toList(),
                "inkBounds");
        TypographySupport.requireFinite(fractionalTranslationX, "fractionalTranslationX");
        TypographySupport.requireFinite(fractionalTranslationY, "fractionalTranslationY");
    }

    /** Returns the glyph origin in one coordinate space. */
    public CoordinatePoint origin(CoordinateSpace space) {
        return point(origins, space, "origin");
    }

    /** Returns the baseline anchor in one coordinate space. */
    public CoordinatePoint baseline(CoordinateSpace space) {
        return point(baselines, space, "baseline");
    }

    /** Returns layout bounds in one coordinate space. */
    public CoordinateBounds layoutBounds(CoordinateSpace space) {
        return bounds(layoutBounds, space, "layoutBounds");
    }

    /** Returns glyph-ink bounds in one coordinate space. */
    public CoordinateBounds inkBounds(CoordinateSpace space) {
        return bounds(inkBounds, space, "inkBounds");
    }

    /** Largest absolute fractional framebuffer translation on either axis. */
    public double transformResidual() {
        return Math.max(
                Math.abs(fractionalTranslationX),
                Math.abs(fractionalTranslationY));
    }

    private static CoordinatePoint point(
            List<CoordinatePoint> values, CoordinateSpace space, String name) {
        return values.stream()
                .filter(value -> value.space() == space)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        name + " is unavailable in " + space));
    }

    private static CoordinateBounds bounds(
            List<CoordinateBounds> values, CoordinateSpace space, String name) {
        return values.stream()
                .filter(value -> value.space() == space)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        name + " is unavailable in " + space));
    }

    private static void requireSpaces(List<CoordinateSpace> spaces, String name) {
        if (spaces.size() != REQUIRED_SPACES.size()
                || !EnumSet.copyOf(spaces).equals(REQUIRED_SPACES)) {
            throw new IllegalArgumentException(
                    name + " must contain local, stage, screen, and framebuffer exactly once");
        }
    }
}
