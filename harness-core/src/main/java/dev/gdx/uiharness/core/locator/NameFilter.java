package dev.gdx.uiharness.core.locator;

import java.util.Objects;

public record NameFilter(TextMatch name) implements LocatorFilter {
    public NameFilter {
        Objects.requireNonNull(name, "name");
    }
}
