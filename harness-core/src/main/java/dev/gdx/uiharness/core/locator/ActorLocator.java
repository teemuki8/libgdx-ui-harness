package dev.gdx.uiharness.core.locator;

import java.util.Objects;

public record ActorLocator(ActorField field, TextMatch text) implements Locator {
    public ActorLocator {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(text, "text");
    }
}
