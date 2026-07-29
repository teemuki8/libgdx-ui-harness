package dev.gdx.uiharness.core.visual;

import java.util.Optional;

/** Session-owner allowlist of immutable visual references. */
@FunctionalInterface
public interface VisualReferenceCatalog {
    /** Resolves one stable reference ID without accepting paths or caller-supplied bytes. */
    Optional<VisualReference> find(String referenceId);
}
