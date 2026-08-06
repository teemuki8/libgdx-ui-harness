package dev.gdx.uiharness.core.locator;

import java.util.Objects;

/** Closed locator matching nodes bound to one runtime entity property. */
public record EntityPropertyLocator(String entityId, String propertyId) implements Locator {
    /** Validates the bounded identifiers. */
    public EntityPropertyLocator {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(propertyId, "propertyId");
        if (entityId.isBlank() || entityId.length() > 256) {
            throw new IllegalArgumentException(
                    "entityId must be non-blank and at most 256 characters");
        }
        if (propertyId.isBlank() || propertyId.length() > 256) {
            throw new IllegalArgumentException(
                    "propertyId must be non-blank and at most 256 characters");
        }
    }
}
