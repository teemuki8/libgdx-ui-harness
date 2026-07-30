package dev.gdx.uiharness.core.layout;

/** One attributed expected-versus-observed layout difference. */
public record LayoutDiagnostic(
        String controlId,
        String path,
        String expected,
        String observed,
        String units,
        String coordinateSpace,
        String referenceArtifactId,
        String currentArtifactId) {
    /** Validates bounded required text fields. */
    public LayoutDiagnostic {
        LayoutSupport.nonBlank(controlId, "controlId");
        LayoutSupport.nonBlank(path, "path");
        LayoutSupport.nonBlank(expected, "expected");
        LayoutSupport.nonBlank(observed, "observed");
        LayoutSupport.nonBlank(units, "units");
        LayoutSupport.optionalId(coordinateSpace, "coordinateSpace");
        LayoutSupport.nonBlank(referenceArtifactId, "referenceArtifactId");
        LayoutSupport.nonBlank(currentArtifactId, "currentArtifactId");
    }
}
