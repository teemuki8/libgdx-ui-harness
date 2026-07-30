package dev.gdx.uiharness.core.typography;

/** One attributed expected-versus-observed typography difference. */
public record TypographyDiagnostic(
        String controlId,
        String path,
        String expected,
        String observed,
        String units,
        String coordinateSpace,
        String referenceArtifactId,
        String currentArtifactId) {

    /** Validates every diagnostic identity and value. */
    public TypographyDiagnostic {
        TypographySupport.requireNonBlank(controlId, "controlId");
        TypographySupport.requireNonBlank(path, "path");
        TypographySupport.requireNonBlank(expected, "expected");
        TypographySupport.requireNonBlank(observed, "observed");
        TypographySupport.requireNonBlank(units, "units");
        TypographySupport.requireNonBlank(referenceArtifactId, "referenceArtifactId");
        TypographySupport.requireNonBlank(currentArtifactId, "currentArtifactId");
    }
}
