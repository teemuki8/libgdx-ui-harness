package dev.gdx.uiharness.core.navigation;

import java.util.List;
import java.util.Objects;

/** Versioned immutable validation result with deterministic actor ordering. */
public record NavigationResult(
        int schemaVersion,
        NavigationPath path,
        List<String> knownFocusables,
        List<String> unreachableFocusables) {
    public static final int SCHEMA_VERSION = 1;

    public NavigationResult {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported navigation result schema version: " + schemaVersion);
        }
        Objects.requireNonNull(path, "path");
        knownFocusables = List.copyOf(Objects.requireNonNull(knownFocusables, "knownFocusables"));
        unreachableFocusables =
                List.copyOf(Objects.requireNonNull(unreachableFocusables, "unreachableFocusables"));
        if (knownFocusables.size() > NavigationRequest.MAX_ACTORS
                || unreachableFocusables.size() > NavigationRequest.MAX_ACTORS) {
            throw new IllegalArgumentException("navigation result exceeds hard actor bound");
        }
    }
}
