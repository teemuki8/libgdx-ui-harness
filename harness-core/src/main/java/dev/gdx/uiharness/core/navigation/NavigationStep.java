package dev.gdx.uiharness.core.navigation;

import java.util.Objects;

/** One completed-frame navigation transition supplied by an execution adapter. */
public record NavigationStep(
        NavigationInput input,
        long beforeFrame,
        long beforeRevision,
        long afterFrame,
        long afterRevision,
        String beforeIdentity,
        String afterIdentity,
        String modalBoundaryId) {
    public NavigationStep {
        Objects.requireNonNull(input, "input");
        requireIdentity(beforeIdentity, "beforeIdentity");
        if (afterIdentity != null) {
            requireIdentity(afterIdentity, "afterIdentity");
        }
        if (modalBoundaryId != null) {
            requireIdentity(modalBoundaryId, "modalBoundaryId");
        }
        if (beforeFrame < 0 || afterFrame <= beforeFrame) {
            throw new IllegalArgumentException("frames must be non-negative and advance");
        }
        if (beforeRevision < 0 || afterRevision < beforeRevision) {
            throw new IllegalArgumentException("revisions must be non-negative and not regress");
        }
    }

    static void requireIdentity(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank without surrounding whitespace");
        }
        if (value.length() > 16_384) {
            throw new IllegalArgumentException(name + " exceeds 16384 characters");
        }
    }
}
