package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.typography.CoordinateBounds;

/** One observed ancestor clip owner and its explicitly mapped rectangle. */
public record LayoutClip(
        String ownerId,
        CoordinateBounds stageBounds,
        CoordinateBounds screenBounds,
        CoordinateBounds framebufferBounds) {
    /** Requires a stable owner and the three published clip spaces. */
    public LayoutClip {
        LayoutSupport.nonBlank(ownerId, "ownerId");
        stageBounds = LayoutSupport.space(stageBounds, "stageBounds", "STAGE");
        screenBounds = LayoutSupport.space(screenBounds, "screenBounds", "SCREEN");
        framebufferBounds =
                LayoutSupport.space(framebufferBounds, "framebufferBounds", "FRAMEBUFFER");
    }
}
