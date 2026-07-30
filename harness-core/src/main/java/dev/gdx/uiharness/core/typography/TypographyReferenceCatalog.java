package dev.gdx.uiharness.core.typography;

import java.util.Optional;

/** Read-only lookup for immutable named typography references. */
@FunctionalInterface
public interface TypographyReferenceCatalog {
    /** Returns one named immutable reference when registered. */
    Optional<TypographyReference> find(String referenceId);
}
