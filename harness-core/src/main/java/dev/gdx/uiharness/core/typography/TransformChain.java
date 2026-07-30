package dev.gdx.uiharness.core.typography;

import java.util.List;
import java.util.Objects;

/** Ordered local-to-framebuffer affine mappings for one text control. */
public record TransformChain(
        AffineTransformObservation localToParent,
        AffineTransformObservation parentToStage,
        AffineTransformObservation stageToScreen,
        AffineTransformObservation screenToFramebuffer) {

    /** Validates that every required mapping is present. */
    public TransformChain {
        Objects.requireNonNull(localToParent, "localToParent");
        Objects.requireNonNull(parentToStage, "parentToStage");
        Objects.requireNonNull(stageToScreen, "stageToScreen");
        Objects.requireNonNull(screenToFramebuffer, "screenToFramebuffer");
    }

    /** Returns mappings in rendering order. */
    public List<AffineTransformObservation> mappings() {
        return List.of(localToParent, parentToStage, stageToScreen, screenToFramebuffer);
    }

    /** Returns true only when every mapping is invertible. */
    public boolean invertible() {
        return mappings().stream().allMatch(AffineTransformObservation::invertible);
    }
}
