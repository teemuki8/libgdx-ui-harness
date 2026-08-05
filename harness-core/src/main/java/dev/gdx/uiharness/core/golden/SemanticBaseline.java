package dev.gdx.uiharness.core.golden;

import java.util.Objects;

/**
 * Versioned golden semantic baseline. Unknown major versions fail closed; minor versions are
 * additive and retained.
 */
public record SemanticBaseline(
        int majorVersion,
        int minorVersion,
        String id,
        BaselineNode root,
        boolean strictNodes) {
    public static final int CURRENT_MAJOR_VERSION = 1;

    /** Validates the version, identifier, and root expectation. */
    public SemanticBaseline {
        if (majorVersion != CURRENT_MAJOR_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported baseline major version: " + majorVersion);
        }
        if (minorVersion < 0) {
            throw new IllegalArgumentException("minorVersion must be non-negative");
        }
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("baseline id must not be blank");
        }
        Objects.requireNonNull(root, "root");
    }
}
