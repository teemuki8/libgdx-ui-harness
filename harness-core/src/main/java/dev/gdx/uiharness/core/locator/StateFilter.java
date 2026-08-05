package dev.gdx.uiharness.core.locator;

import java.util.Objects;

public record StateFilter(LocatorFilter.State state, boolean expected) implements LocatorFilter {
    public StateFilter {
        Objects.requireNonNull(state, "state");
    }
}
