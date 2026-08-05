package dev.gdx.uiharness.core.locator;

import java.util.Objects;

/**
 * One bounded semantic property that separates a multiple-match candidate from every other
 * candidate at failure time.
 *
 * @param field stable property name, such as {@code testId} or {@code ancestor}
 * @param value bounded observed value
 */
public record DistinguishingProperty(String field, String value) {
    private static final int MAX_FIELD_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 16_384;

    /** Validates and bounds the property. */
    public DistinguishingProperty {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        if (field.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("distinguishing property field is too long");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("distinguishing property value is too long");
        }
    }
}
