package dev.gdx.uiharness.core.visual;

/** Actionable bounded failure detail for incomplete or stale comparison evidence. */
public record ComparisonDiagnostic(
        String code, String path, String expected, String observed) {
    /** Validates machine code, JSON path, and bounded values. */
    public ComparisonDiagnostic {
        VisualSupport.identifier(code, "diagnostic code");
        VisualSupport.text(path, "diagnostic path");
        VisualSupport.text(expected, "diagnostic expected");
        VisualSupport.text(observed, "diagnostic observed");
    }
}
