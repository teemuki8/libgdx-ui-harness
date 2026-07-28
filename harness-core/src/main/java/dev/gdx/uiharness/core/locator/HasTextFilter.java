package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record HasTextFilter(TextMatch text) implements LocatorFilter {
    HasTextFilter {
        Objects.requireNonNull(text, "text");
    }
}
