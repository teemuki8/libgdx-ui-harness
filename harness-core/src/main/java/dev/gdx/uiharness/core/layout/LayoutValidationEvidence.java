package dev.gdx.uiharness.core.layout;

import java.util.Map;
import java.util.Objects;

/** Optional backend evidence augmenting one atomic semantic layout observation. */
public record LayoutValidationEvidence(
        boolean textGeometryAvailable,
        Map<String, TextLayoutEvidence> textByNodeId) {
    private static final int MAX_TEXT_NODES = 10_000;

    public LayoutValidationEvidence {
        textByNodeId = Map.copyOf(Objects.requireNonNull(textByNodeId, "textByNodeId"));
        if (textByNodeId.size() > MAX_TEXT_NODES) {
            throw new IllegalArgumentException("text evidence exceeds " + MAX_TEXT_NODES);
        }
        if (!textGeometryAvailable && !textByNodeId.isEmpty()) {
            throw new IllegalArgumentException("unavailable text geometry must be empty");
        }
        textByNodeId.forEach((nodeId, value) -> {
            if (!nodeId.equals(value.nodeId())) {
                throw new IllegalArgumentException("text evidence key/node mismatch: " + nodeId);
            }
        });
    }

    public static LayoutValidationEvidence unavailable() {
        return new LayoutValidationEvidence(false, Map.of());
    }

    public static LayoutValidationEvidence available(
            Map<String, TextLayoutEvidence> textByNodeId) {
        return new LayoutValidationEvidence(true, textByNodeId);
    }
}
