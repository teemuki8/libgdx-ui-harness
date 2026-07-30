package dev.gdx.uiharness.core.layout;


/** Expected framebuffer-origin relationship to another selected control. */
public record LayoutRelationship(
        String relatedControlId,
        double expectedDeltaX,
        double expectedDeltaY,
        double tolerance) {
    /** Validates identity and finite relationship values. */
    public LayoutRelationship {
        LayoutSupport.nonBlank(relatedControlId, "relatedControlId");
        if (!Double.isFinite(expectedDeltaX)
                || !Double.isFinite(expectedDeltaY)
                || !Double.isFinite(tolerance)
                || tolerance < 0) {
            throw new IllegalArgumentException("relationship values must be finite");
        }
    }
}
