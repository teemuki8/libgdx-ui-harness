package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record IndexedLocator(Locator locator, int index) implements Locator {
    IndexedLocator {
        Objects.requireNonNull(locator, "locator");
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
    }
}
