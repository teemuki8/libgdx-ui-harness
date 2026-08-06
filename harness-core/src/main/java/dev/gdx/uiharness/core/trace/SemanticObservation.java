package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.Objects;

/** One bounded retained semantic observation correlated to a trace sequence. */
public record SemanticObservation(
        long sequence,
        long frame,
        long revision,
        SemanticSnapshot snapshot,
        Long causeSequence) {
    /** Validates the observation. */
    public SemanticObservation {
        if (sequence < 0 || frame < 0 || revision < 0) {
            throw new IllegalArgumentException("sequence, frame, and revision must be non-negative");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        if (causeSequence != null && causeSequence < 0) {
            throw new IllegalArgumentException("causeSequence must be non-negative");
        }
    }
}
