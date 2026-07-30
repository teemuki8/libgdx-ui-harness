package dev.gdx.uiharness.core.layout;

import java.util.Optional;

/** Allowlisted named layout reference lookup. */
@FunctionalInterface
public interface LayoutReferenceCatalog {
    /** Returns one registered immutable reference. */
    Optional<LayoutReference> find(String referenceId);
}
