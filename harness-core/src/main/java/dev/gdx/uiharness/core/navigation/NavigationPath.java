package dev.gdx.uiharness.core.navigation;

import java.util.List;
import java.util.Objects;

/** Versioned immutable navigation path and its terminal classification. */
public record NavigationPath(
        int schemaVersion,
        String defaultFocusIdentity,
        List<NavigationStep> steps,
        NavigationReason reason) {
    public static final int SCHEMA_VERSION = 1;

    public NavigationPath {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported navigation path schema version: " + schemaVersion);
        }
        if (defaultFocusIdentity != null) {
            NavigationStep.requireIdentity(defaultFocusIdentity, "defaultFocusIdentity");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.size() > NavigationRequest.MAX_STEPS) {
            throw new IllegalArgumentException("navigation path exceeds hard step bound");
        }
        Objects.requireNonNull(reason, "reason");
    }
}
