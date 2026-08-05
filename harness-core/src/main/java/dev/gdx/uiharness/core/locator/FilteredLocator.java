package dev.gdx.uiharness.core.locator;

import java.util.Objects;

public record FilteredLocator(Locator locator, LocatorFilter filter) implements Locator {
    public FilteredLocator {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(filter, "filter");
    }
}
