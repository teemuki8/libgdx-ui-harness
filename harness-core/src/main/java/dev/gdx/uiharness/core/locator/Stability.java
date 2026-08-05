package dev.gdx.uiharness.core.locator;

/**
 * Whether a suggested locator relies on a stable automation contract or on a structurally
 * fragile fallback.
 */
public enum Stability {
    /** Relies on explicit or semantic identity that is robust to layout and structure changes. */
    STABLE,
    /** Relies on backend actor naming or positional order that can change with structure. */
    FRAGILE
}
