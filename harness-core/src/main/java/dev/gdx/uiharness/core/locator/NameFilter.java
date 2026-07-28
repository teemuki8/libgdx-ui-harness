package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record NameFilter(TextMatch name) implements LocatorFilter {
    NameFilter {
        Objects.requireNonNull(name, "name");
    }
}
