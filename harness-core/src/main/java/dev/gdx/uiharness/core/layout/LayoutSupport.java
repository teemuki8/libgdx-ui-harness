package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import java.util.Objects;

final class LayoutSupport {
    private LayoutSupport() {}

    static void nonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
    }

    static void optionalId(String value, String name) {
        if (value != null) {
            nonBlank(value, name);
        }
    }

    static CoordinateBounds space(
            CoordinateBounds value, String name, String expectedSpace) {
        if (!value.space().name().equals(expectedSpace)) {
            throw new IllegalArgumentException(name + " must use " + expectedSpace);
        }
        return value;
    }
}
