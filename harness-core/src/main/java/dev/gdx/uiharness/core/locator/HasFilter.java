package dev.gdx.uiharness.core.locator;

import java.util.Objects;

public record HasFilter(Locator descendant) implements LocatorFilter {
    public HasFilter {
        Objects.requireNonNull(descendant, "descendant");
    }
}
