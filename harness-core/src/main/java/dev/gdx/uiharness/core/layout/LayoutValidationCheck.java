package dev.gdx.uiharness.core.layout;

/** Closed set of whole-stage layout invariant checks. */
public enum LayoutValidationCheck {
    /** Actor lies outside the viewport. */
    OUTSIDE_VIEWPORT,
    /** Visible text is clipped by a container. */
    CLIPPED_TEXT,
    /** Two interactive controls overlap. */
    INTERACTIVE_OVERLAP,
    /** Control has zero width or height. */
    ZERO_SIZE,
    /** Control is below a configured target size. */
    BELOW_TARGET_SIZE,
    /** Two controls share one test identifier. */
    DUPLICATE_TEST_ID,
    /** An interactive control has no accessible name. */
    MISSING_ACCESSIBLE_NAME,
    /** A focusable control is not reachable by keyboard navigation. */
    KEYBOARD_UNREACHABLE,
    /** A control is obscured by a higher-z actor. */
    OBSCURED,
    /** Clipping or scroll configuration is invalid. */
    INVALID_CLIP_SCROLL,
    /** Sibling controls deviate from a consistent alignment. */
    INCONSISTENT_ALIGNMENT,
    /** Sibling controls deviate from consistent spacing. */
    INCONSISTENT_SPACING
}
