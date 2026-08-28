package dev.gdx.uiharness.core.runtime;

import dev.gdx.uiharness.core.model.RuntimeBinding;
import java.util.Objects;

/** Bounded result of observing one explicitly registered runtime entity property. */
public record RuntimeObservationResult(
        Status status,
        String entityId,
        String propertyId,
        Long runtimeFrame,
        Long runtimeRevision,
        String value,
        String valueFormatId) {
    private static final int MAX_VALUE = 16_384;

    /** Validates the closed result and its availability-dependent fields. */
    public RuntimeObservationResult {
        Objects.requireNonNull(status, "status");
        new RuntimeBinding(entityId, propertyId, valueFormatId, null, null);
        if (value != null && value.length() > MAX_VALUE) {
            throw new IllegalArgumentException("runtime value exceeds 16384 characters");
        }
        if (runtimeFrame != null && runtimeFrame < 0) {
            throw new IllegalArgumentException("runtimeFrame must be non-negative");
        }
        if (runtimeRevision != null && runtimeRevision < 0) {
            throw new IllegalArgumentException("runtimeRevision must be non-negative");
        }
        if (status == Status.AVAILABLE) {
            Objects.requireNonNull(valueFormatId, "valueFormatId");
            if (runtimeFrame == null || runtimeRevision == null || value == null) {
                throw new IllegalArgumentException(
                        "available observations require frame, revision, value, and format");
            }
        }
        if (status == Status.UNAVAILABLE
                && (runtimeFrame != null || runtimeRevision != null
                        || value != null || valueFormatId != null)) {
            throw new IllegalArgumentException("unavailable observations cannot carry a value");
        }
    }

    /** Returns an unavailable result for one validated explicit entity property. */
    public static RuntimeObservationResult unavailable(String entityId, String propertyId) {
        return new RuntimeObservationResult(
                Status.UNAVAILABLE, entityId, propertyId, null, null, null, null);
    }

    /** Closed direct-observation statuses. */
    public enum Status {
        /** A typed value was observed on a proven correlated completed frame. */
        AVAILABLE,
        /** No correlated completed-frame value was available. */
        UNAVAILABLE
    }
}
