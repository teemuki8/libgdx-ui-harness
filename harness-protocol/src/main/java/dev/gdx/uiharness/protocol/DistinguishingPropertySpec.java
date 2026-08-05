package dev.gdx.uiharness.protocol;

import java.util.Objects;

/**
 * Closed protocol representation of one bounded property separating a multiple-match candidate
 * from every other candidate.
 *
 * @param field stable property name, such as {@code testId} or {@code ancestor}
 * @param value bounded observed value
 */
public record DistinguishingPropertySpec(String field, String value) {
    private static final int MAX_FIELD_LENGTH = 64;

    /** Validates and bounds the property. */
    public DistinguishingPropertySpec {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        if (field.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("distinguishing property field is too long");
        }
        ProtocolJson.requireText(value, "value");
    }
}
