package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.model.Bounds;
import java.util.Objects;

/**
 * One bounded whole-stage layout finding.
 *
 * @param reason closed reason code
 * @param severity observed severity
 * @param nodeId stable identity of the affected node
 * @param relatedActorId related node identity, when the finding involves a pair
 * @param stageBounds stage-space bounds of the affected node
 * @param evidence bounded human-readable evidence
 */
public record LayoutFinding(
        LayoutValidationReason reason,
        LayoutValidationSeverity severity,
        String nodeId,
        String relatedActorId,
        Bounds stageBounds,
        String evidence) {
    private static final int MAX_EVIDENCE_LENGTH = 512;

    /** Validates and bounds the finding. */
    public LayoutFinding {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(stageBounds, "stageBounds");
        Objects.requireNonNull(evidence, "evidence");
        if (nodeId.length() > 16_384) {
            throw new IllegalArgumentException("finding nodeId exceeds 16384 characters");
        }
        if (relatedActorId != null && relatedActorId.length() > 16_384) {
            throw new IllegalArgumentException("finding related identity exceeds 16384 characters");
        }
        if (evidence.length() > MAX_EVIDENCE_LENGTH) {
            throw new IllegalArgumentException("finding evidence exceeds 512 characters");
        }
    }
}
