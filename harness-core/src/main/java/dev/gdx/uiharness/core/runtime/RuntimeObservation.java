package dev.gdx.uiharness.core.runtime;

import java.util.Objects;

/** One bounded typed runtime value observed by an optional application SPI. */
public record RuntimeObservation(
        String entityId,
        String propertyId,
        long frame,
        long revision,
        String value,
        String valueFormatId) {
    private static final int MAX_VALUE = 16_384;

    /** Validates the bounded observation. */
    public RuntimeObservation {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(propertyId, "propertyId");
        if (frame < 0 || revision < 0) {
            throw new IllegalArgumentException("frame and revision must be non-negative");
        }
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_VALUE) {
            throw new IllegalArgumentException("runtime value exceeds 16384 characters");
        }
    }
}
