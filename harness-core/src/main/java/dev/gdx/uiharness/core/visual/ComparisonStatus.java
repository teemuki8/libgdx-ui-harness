package dev.gdx.uiharness.core.visual;

/** Fail-closed completion states for one inspect-capture-compare invocation. */
public enum ComparisonStatus {
    INCOMPLETE,
    STALE,
    NOT_CONVERGED,
    CONVERGED
}
