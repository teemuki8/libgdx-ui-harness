package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/**
 * One two-dimensional affine mapping and its deterministic decomposition.
 *
 * <p>The matrix convention is {@code x' = m00*x + m01*y + translateX} and
 * {@code y' = m10*x + m11*y + translateY}.
 */
public record AffineTransformObservation(
        CoordinateSpace source,
        CoordinateSpace target,
        double m00,
        double m01,
        double translateX,
        double m10,
        double m11,
        double translateY,
        double effectiveScaleX,
        double effectiveScaleY,
        double rotationDegrees,
        double shear,
        double fractionalTranslationX,
        double fractionalTranslationY,
        boolean invertible) {
    private static final double INVERTIBILITY_EPSILON = 1e-12;

    /** Validates all published matrix and decomposition values. */
    public AffineTransformObservation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        TypographySupport.requireFinite(m00, "m00");
        TypographySupport.requireFinite(m01, "m01");
        TypographySupport.requireFinite(translateX, "translateX");
        TypographySupport.requireFinite(m10, "m10");
        TypographySupport.requireFinite(m11, "m11");
        TypographySupport.requireFinite(translateY, "translateY");
        TypographySupport.requireNonNegativeFinite(effectiveScaleX, "effectiveScaleX");
        TypographySupport.requireNonNegativeFinite(effectiveScaleY, "effectiveScaleY");
        TypographySupport.requireFinite(rotationDegrees, "rotationDegrees");
        TypographySupport.requireFinite(shear, "shear");
        TypographySupport.requireFinite(
                fractionalTranslationX, "fractionalTranslationX");
        TypographySupport.requireFinite(
                fractionalTranslationY, "fractionalTranslationY");
    }

    /** Decomposes one finite affine matrix into scale, rotation, and shear. */
    public static AffineTransformObservation fromMatrix(
            CoordinateSpace source,
            CoordinateSpace target,
            double m00,
            double m01,
            double translateX,
            double m10,
            double m11,
            double translateY) {
        double scaleX = Math.hypot(m00, m10);
        double determinant = m00 * m11 - m01 * m10;
        boolean invertible = Math.abs(determinant) > INVERTIBILITY_EPSILON;
        double scaleY = scaleX > INVERTIBILITY_EPSILON
                ? Math.abs(determinant) / scaleX
                : Math.hypot(m01, m11);
        double rotation = scaleX > INVERTIBILITY_EPSILON
                ? Math.toDegrees(Math.atan2(m10, m00))
                : 0;
        double shear = scaleX > INVERTIBILITY_EPSILON
                ? (m00 * m01 + m10 * m11) / (scaleX * scaleX)
                : 0;
        return new AffineTransformObservation(
                source,
                target,
                m00,
                m01,
                translateX,
                m10,
                m11,
                translateY,
                scaleX,
                scaleY,
                rotation,
                shear,
                fractionalPart(translateX),
                fractionalPart(translateY),
                invertible);
    }

    /** Returns an identity mapping between two named spaces. */
    public static AffineTransformObservation identity(
            CoordinateSpace source, CoordinateSpace target) {
        return fromMatrix(source, target, 1, 0, 0, 0, 1, 0);
    }

    private static double fractionalPart(double value) {
        return Math.abs(value - Math.rint(value));
    }
}
