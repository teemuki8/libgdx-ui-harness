package dev.gdx.uiharness.core.action;

import java.util.Map;
import java.util.Objects;

/** Immutable evidence returned after a dispatched action and a later completed frame. */
public record ActionResult(
        long beforeRevision,
        long afterRevision,
        String observedState,
        Map<String, String> evidence) {
    public ActionResult {
        if (beforeRevision < 0) {
            throw new IllegalArgumentException("beforeRevision must be non-negative");
        }
        if (afterRevision <= beforeRevision) {
            throw new IllegalArgumentException("afterRevision must follow beforeRevision");
        }
        Objects.requireNonNull(observedState, "observedState");
        evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
