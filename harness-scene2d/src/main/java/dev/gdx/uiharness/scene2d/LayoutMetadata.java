package dev.gdx.uiharness.scene2d;

import java.util.Objects;

/** Explicit stable role for one actor selected by layout diagnostics. */
public record LayoutMetadata(String role) {
    /** Requires a bounded machine-readable role. */
    public LayoutMetadata {
        Objects.requireNonNull(role, "role");
        if (role.isBlank() || role.length() > 256) {
            throw new IllegalArgumentException("role must be non-blank and bounded");
        }
    }
}
