package dev.gdx.uiharness.core.visual;

import java.util.Objects;

/** One attributed semantic difference or retained unattributed raster residual. */
public record VisualDifference(
        DifferenceCategory category,
        String controlId,
        String path,
        String expected,
        String observed,
        boolean blocking) {
    /** Validates bounded difference evidence. */
    public VisualDifference {
        Objects.requireNonNull(category, "category");
        if (controlId != null) {
            VisualSupport.identifier(controlId, "controlId");
        }
        VisualSupport.text(path, "path");
        VisualSupport.text(expected, "expected");
        VisualSupport.text(observed, "observed");
        if (category == DifferenceCategory.RASTER_RESIDUAL && controlId != null) {
            throw new IllegalArgumentException("raster residual must remain unattributed");
        }
    }
}
