package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/** Logical-window, viewport, framebuffer, and device-scale identity. */
public record DisplayObservation(
        String applicationId,
        String viewportId,
        int windowWidth,
        int windowHeight,
        int logicalViewportWidth,
        int logicalViewportHeight,
        int framebufferWidth,
        int framebufferHeight,
        double deviceScaleX,
        double deviceScaleY) {

    /** Validates stable identities, positive dimensions, and positive finite scales. */
    public DisplayObservation {
        TypographySupport.requireNonBlank(applicationId, "applicationId");
        TypographySupport.requireNonBlank(viewportId, "viewportId");
        TypographySupport.requirePositive(windowWidth, "windowWidth");
        TypographySupport.requirePositive(windowHeight, "windowHeight");
        TypographySupport.requirePositive(logicalViewportWidth, "logicalViewportWidth");
        TypographySupport.requirePositive(logicalViewportHeight, "logicalViewportHeight");
        TypographySupport.requirePositive(framebufferWidth, "framebufferWidth");
        TypographySupport.requirePositive(framebufferHeight, "framebufferHeight");
        TypographySupport.requirePositiveFinite(deviceScaleX, "deviceScaleX");
        TypographySupport.requirePositiveFinite(deviceScaleY, "deviceScaleY");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(viewportId, "viewportId");
    }
}
