package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record ActorLocator(ActorField field, TextMatch text) implements Locator {
    ActorLocator {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(text, "text");
    }
}
