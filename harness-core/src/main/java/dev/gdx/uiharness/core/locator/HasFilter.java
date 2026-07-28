package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record HasFilter(Locator descendant) implements LocatorFilter {
    HasFilter {
        Objects.requireNonNull(descendant, "descendant");
    }
}
