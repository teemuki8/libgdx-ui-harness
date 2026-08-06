package dev.gdx.uiharness.core.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Bounded typed displayed/runtime comparison with distinct correlation states.
 *
 * @param status closed comparison status
 * @param entityId bound entity identifier
 * @param propertyId bound property identifier
 * @param displayedValue bounded displayed value
 * @param runtimeValue bounded runtime value, when observed
 * @param comparatorId comparator identity, when declared
 * @param correlationId shared-frame correlation identity, when declared
 * @param displayedFrame frame of the displayed observation
 * @param runtimeFrame frame of the runtime observation, when observed
 * @param redacted whether values were redacted
 * @param details bounded diagnostic details
 */
public record DisplayedRuntimeComparison(
        Status status,
        String entityId,
        String propertyId,
        String displayedValue,
        String runtimeValue,
        String comparatorId,
        String correlationId,
        long displayedFrame,
        Long runtimeFrame,
        boolean redacted,
        Map<String, String> details) {
    private static final int MAX_VALUE = 16_384;
    private static final int MAX_DETAILS = 32;

    /** Validates the comparison. */
    public DisplayedRuntimeComparison {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(propertyId, "propertyId");
        if (displayedValue != null && displayedValue.length() > MAX_VALUE) {
            throw new IllegalArgumentException("displayedValue exceeds 16384 characters");
        }
        if (runtimeValue != null && runtimeValue.length() > MAX_VALUE) {
            throw new IllegalArgumentException("runtimeValue exceeds 16384 characters");
        }
        if (displayedFrame < 0) {
            throw new IllegalArgumentException("displayedFrame must be non-negative");
        }
        if (runtimeFrame != null && runtimeFrame < 0) {
            throw new IllegalArgumentException("runtimeFrame must be non-negative");
        }
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
        if (details.size() > MAX_DETAILS) {
            throw new IllegalArgumentException("too many comparison details");
        }
    }

    /** Closed comparison statuses. */
    public enum Status {
        /** Displayed and runtime values are typed-equal on correlated frames. */
        EQUAL,
        /** Displayed and runtime values differ. */
        MISMATCH,
        /** The runtime observation is older than the displayed frame. */
        STALE,
        /** Atomic frame correlation could not be proven. */
        UNCORRELATED,
        /** The bound node is missing. */
        MISSING,
        /** No runtime source is available. */
        UNAVAILABLE,
        /** The binding is ambiguous or the value type is unsupported. */
        AMBIGUOUS
    }
}
