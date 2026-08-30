package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.model.Bounds;
import java.util.List;
import java.util.Objects;

/** Immutable stage-space text layout and effective clip evidence for one semantic node. */
public record TextLayoutEvidence(
        String nodeId,
        Bounds layoutStageBounds,
        Bounds inkStageBounds,
        List<Bounds> clipChainStageBounds) {
    public TextLayoutEvidence {
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        layoutStageBounds = Objects.requireNonNull(layoutStageBounds, "layoutStageBounds");
        inkStageBounds = Objects.requireNonNull(inkStageBounds, "inkStageBounds");
        clipChainStageBounds = List.copyOf(
                Objects.requireNonNull(clipChainStageBounds, "clipChainStageBounds"));
        if (clipChainStageBounds.size() > 128) {
            throw new IllegalArgumentException("clip chain exceeds 128 bounds");
        }
    }
}
