package dev.gdx.uiharness.core.visual;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.time.Instant;
import java.util.Objects;

/** Accepted current full-frame capture bound to its inspected completed-frame state. */
public record CurrentVisualEvidence(
        String sessionId,
        String applicationId,
        String viewportId,
        CapturedImage image,
        Instant capturedAt,
        SemanticSnapshot semanticSnapshot,
        StateActionContract stateActionContract) {
    /** Validates identity and freshness within the accepted evidence bundle. */
    public CurrentVisualEvidence {
        VisualSupport.identifier(sessionId, "sessionId");
        VisualSupport.identifier(applicationId, "applicationId");
        VisualSupport.identifier(viewportId, "viewportId");
        Objects.requireNonNull(image, "image");
        VisualSupport.verifiedBytes(
                image.pngBytes(), image.sha256(), "current capture");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(semanticSnapshot, "semanticSnapshot");
        if (image.revision() != semanticSnapshot.revision()
                || image.frame() != semanticSnapshot.frame()) {
            throw new IllegalArgumentException(
                    "current capture and semantic identities differ");
        }
        if (stateActionContract != null
                && (image.revision() != stateActionContract.revision()
                || image.frame() != stateActionContract.frame())) {
            throw new IllegalArgumentException(
                    "current capture and state/action identities differ");
        }
    }
}
