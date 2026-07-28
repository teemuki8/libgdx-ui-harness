package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record TextLocator(TextField field, TextMatch text) implements Locator {
    TextLocator {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(text, "text");
    }
}
