package dev.gdx.uiharness.core.visual;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.time.Instant;
import java.util.Objects;

/** Immutable allowlisted reference image and its semantic and viewport provenance. */
public record VisualReference(
        String referenceId,
        String applicationId,
        String sourceSessionId,
        String viewportId,
        byte[] pngBytes,
        String sha256,
        int width,
        int height,
        CapturedImage.Scale scale,
        Instant capturedAt,
        SemanticSnapshot semanticSnapshot,
        StateActionContract stateActionContract) {
    /** Validates identities, provenance, and the reference content hash. */
    public VisualReference {
        VisualSupport.identifier(referenceId, "referenceId");
        VisualSupport.identifier(applicationId, "applicationId");
        VisualSupport.identifier(sourceSessionId, "sourceSessionId");
        VisualSupport.identifier(viewportId, "viewportId");
        pngBytes = VisualSupport.verifiedBytes(pngBytes, sha256, "reference");
        if (width <= 0 || height <= 0
                || (long) width * height > 33_554_432L) {
            throw new IllegalArgumentException("reference dimensions must be positive");
        }
        Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (stateActionContract != null && semanticSnapshot == null) {
            throw new IllegalArgumentException(
                    "reference state/action contract requires semantic evidence");
        }
        if (stateActionContract != null
                && (stateActionContract.revision() != semanticSnapshot.revision()
                || stateActionContract.frame() != semanticSnapshot.frame())) {
            throw new IllegalArgumentException(
                    "reference semantic and state/action identities differ");
        }
    }

    /** Returns a defensive copy of the encoded reference PNG. */
    @Override public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
