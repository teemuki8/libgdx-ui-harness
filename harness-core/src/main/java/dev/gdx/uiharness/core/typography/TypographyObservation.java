package dev.gdx.uiharness.core.typography;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable current typography evidence for one text run. */
public record TypographyObservation(
        String schemaVersion,
        String controlId,
        String actorId,
        String text,
        int textStart,
        int textEnd,
        List<GlyphRunObservation> glyphRuns,
        long revision,
        long frame,
        String currentArtifactId,
        String captureSha256,
        String transformSha256,
        FontObservation font,
        DisplayObservation display,
        TransformChain transforms,
        TypographyGeometry geometry,
        double rasterResidual,
        List<String> sourceMechanisms,
        List<String> unresolvedHypotheses) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** Validates identities, ranges, hashes, and bounded immutable collections. */
    public TypographyObservation {
        if (!"typography/v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be typography/v1");
        }
        TypographySupport.requireNonBlank(controlId, "controlId");
        TypographySupport.requireNonBlank(actorId, "actorId");
        Objects.requireNonNull(text, "text");
        if (textStart < 0 || textEnd < textStart || textEnd > text.length()) {
            throw new IllegalArgumentException("text range must be within text");
        }
        glyphRuns = List.copyOf(Objects.requireNonNull(glyphRuns, "glyphRuns"));
        if (glyphRuns.isEmpty()) {
            throw new IllegalArgumentException("glyphRuns must not be empty");
        }
        if (revision < 0 || frame < 0) {
            throw new IllegalArgumentException("revision and frame must be non-negative");
        }
        TypographySupport.requireNonBlank(currentArtifactId, "currentArtifactId");
        requireSha(captureSha256, "captureSha256");
        requireSha(transformSha256, "transformSha256");
        font = Objects.requireNonNull(font, "font");
        display = Objects.requireNonNull(display, "display");
        transforms = Objects.requireNonNull(transforms, "transforms");
        geometry = Objects.requireNonNull(geometry, "geometry");
        TypographySupport.requireNonNegativeFinite(rasterResidual, "rasterResidual");
        sourceMechanisms =
                List.copyOf(Objects.requireNonNull(sourceMechanisms, "sourceMechanisms"));
        unresolvedHypotheses =
                List.copyOf(Objects.requireNonNull(unresolvedHypotheses, "unresolvedHypotheses"));
    }

    /** Returns a copy with another font observation. */
    public TypographyObservation withFont(FontObservation value) {
        return new TypographyObservation(schemaVersion, controlId, actorId, text,
                textStart, textEnd, glyphRuns, revision, frame,
                currentArtifactId, captureSha256,
                transformSha256, value, display, transforms, geometry, rasterResidual,
                sourceMechanisms, unresolvedHypotheses);
    }

    /** Returns a copy bound to another claimed current capture digest. */
    public TypographyObservation withCaptureSha256(String value) {
        return new TypographyObservation(schemaVersion, controlId, actorId, text,
                textStart, textEnd, glyphRuns, revision, frame,
                currentArtifactId, value, transformSha256, font, display,
                transforms, geometry, rasterResidual,
                sourceMechanisms, unresolvedHypotheses);
    }

    /** Returns a copy with another display identity. */
    public TypographyObservation withDisplay(DisplayObservation value) {
        return copy(font, value, transforms, geometry, rasterResidual, transformSha256);
    }

    /** Returns a copy with another transform chain and digest. */
    public TypographyObservation withTransforms(TransformChain value, String digest) {
        return copy(font, display, value, geometry, rasterResidual, digest);
    }

    /** Returns a copy with another geometry observation. */
    public TypographyObservation withGeometry(TypographyGeometry value) {
        return copy(font, display, transforms, value, rasterResidual, transformSha256);
    }

    /** Returns a copy with another attributed raster residual. */
    public TypographyObservation withRasterResidual(double value) {
        return copy(font, display, transforms, geometry, value, transformSha256);
    }

    private TypographyObservation copy(
            FontObservation nextFont,
            DisplayObservation nextDisplay,
            TransformChain nextTransforms,
            TypographyGeometry nextGeometry,
            double nextRasterResidual,
            String nextTransformSha256) {
        return new TypographyObservation(schemaVersion, controlId, actorId, text,
                textStart, textEnd, glyphRuns, revision, frame,
                currentArtifactId, captureSha256, nextTransformSha256,
                nextFont, nextDisplay, nextTransforms, nextGeometry, nextRasterResidual,
                sourceMechanisms, unresolvedHypotheses);
    }

    private static void requireSha(String value, String name) {
        if (!SHA_256.matcher(Objects.requireNonNull(value, name)).matches()) {
            throw new IllegalArgumentException(name + " must contain 64 lowercase hex digits");
        }
    }
}
