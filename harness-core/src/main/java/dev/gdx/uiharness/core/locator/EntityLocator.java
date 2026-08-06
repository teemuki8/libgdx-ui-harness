package dev.gdx.uiharness.core.locator;

import java.util.Objects;

/** Closed locator matching nodes bound to one runtime entity. */
public record EntityLocator(String entityId) implements Locator {
    /** Validates the bounded entity identifier. */
    public EntityLocator {
        Objects.requireNonNull(entityId, "entityId");
        if (entityId.isBlank() || entityId.length() > 256) {
            throw new IllegalArgumentException(
                    "entityId must be non-blank and at most 256 characters");
        }
    }
}
