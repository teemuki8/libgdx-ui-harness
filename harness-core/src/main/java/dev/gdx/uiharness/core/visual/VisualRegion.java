package dev.gdx.uiharness.core.visual;

/** One bounded spatial cluster of semantic or raster difference evidence. */
public record VisualRegion(
        DifferenceCategory category,
        String controlId,
        int x,
        int y,
        int width,
        int height,
        long differingPixels,
        double meanAbsoluteError) {
    /** Validates framebuffer-top-left pixel coordinates and bounded measurements. */
    public VisualRegion {
        java.util.Objects.requireNonNull(category, "category");
        if (controlId != null) {
            VisualSupport.identifier(controlId, "controlId");
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || (long) width * height > 33_554_432L
                || differingPixels < 0
                || differingPixels > (long) width * height
                || !Double.isFinite(meanAbsoluteError)
                || meanAbsoluteError < 0 || meanAbsoluteError > 255) {
            throw new IllegalArgumentException("invalid visual difference region");
        }
    }
}
