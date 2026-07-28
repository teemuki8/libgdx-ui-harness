package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record StateFilter(LocatorFilter.State state, boolean expected) implements LocatorFilter {
    StateFilter {
        Objects.requireNonNull(state, "state");
    }
}
