package dev.gdx.uiharness.core.error;

/** Semantic evidence fields whose values may carry sensitive user-visible text. */
public enum RedactionField {
    /** Explicit automation test identifier. */
    TEST_ID,
    /** Accessible name of a control. */
    ACCESSIBLE_NAME,
    /** Associated label of a control. */
    LABEL,
    /** Visible text of a control. */
    TEXT,
    /** Backend actor name used for diagnostics. */
    ACTOR_NAME,
    /** Backend actor type used for diagnostics. */
    ACTOR_TYPE
}
