package dev.gdx.uiharness.core.locator;

import java.util.Objects;

public record TextLocator(TextField field, TextMatch text) implements Locator {
    public TextLocator {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(text, "text");
    }
}
