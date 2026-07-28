package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record FilteredLocator(Locator locator, LocatorFilter filter) implements Locator {
    FilteredLocator {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(filter, "filter");
    }
}
