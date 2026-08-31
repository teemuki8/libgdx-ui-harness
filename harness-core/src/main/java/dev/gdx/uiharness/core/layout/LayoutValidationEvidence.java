package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.model.Bounds;
import java.util.Map;
import java.util.Objects;

/**
 * Optional backend evidence augmenting one atomic semantic layout observation.
 *
 * @param textGeometryAvailable whether the complete bounded intrinsic observation is available
 * @param stageViewportBounds exact Stage viewport bounds, or {@code null} when unavailable
 * @param textByNodeId immutable text evidence keyed by snapshot node identity
 */
public record LayoutValidationEvidence(
        boolean textGeometryAvailable,
        Bounds stageViewportBounds,
        Map<String, TextLayoutEvidence> textByNodeId) {
    private static final int MAX_TEXT_NODES = 10_000;

    public LayoutValidationEvidence {
        textByNodeId = Map.copyOf(Objects.requireNonNull(textByNodeId, "textByNodeId"));
        if (textByNodeId.size() > MAX_TEXT_NODES) {
            throw new IllegalArgumentException("text evidence exceeds " + MAX_TEXT_NODES);
        }
        if (textGeometryAvailable) {
            stageViewportBounds =
                    Objects.requireNonNull(stageViewportBounds, "stageViewportBounds");
        } else if (stageViewportBounds != null || !textByNodeId.isEmpty()) {
            throw new IllegalArgumentException(
                    "unavailable text geometry must not carry viewport or node evidence");
        }
        textByNodeId.forEach((nodeId, value) -> {
            if (!nodeId.equals(value.nodeId())) {
                throw new IllegalArgumentException("text evidence key/node mismatch: " + nodeId);
            }
        });
    }

    public static LayoutValidationEvidence unavailable() {
        return new LayoutValidationEvidence(false, null, Map.of());
    }

    public static LayoutValidationEvidence available(
            Bounds stageViewportBounds,
            Map<String, TextLayoutEvidence> textByNodeId) {
        return new LayoutValidationEvidence(
                true, stageViewportBounds, textByNodeId);
    }
}
