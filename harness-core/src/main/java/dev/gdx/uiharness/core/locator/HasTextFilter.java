package dev.gdx.uiharness.core.locator;

import java.util.Objects;

public record HasTextFilter(TextMatch text) implements LocatorFilter {
    public HasTextFilter {
        Objects.requireNonNull(text, "text");
    }
}
