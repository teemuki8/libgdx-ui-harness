package dev.gdx.uiharness.core.layout;

/** Closed reason codes for whole-stage layout findings. */
public enum LayoutValidationReason {
    OUTSIDE_VIEWPORT(LayoutValidationCheck.OUTSIDE_VIEWPORT, LayoutValidationSeverity.ERROR),
    CLIPPED_TEXT(LayoutValidationCheck.CLIPPED_TEXT, LayoutValidationSeverity.ERROR),
    TEXT_COLLISION(LayoutValidationCheck.TEXT_COLLISION, LayoutValidationSeverity.ERROR),
    INTERACTIVE_OVERLAP(
            LayoutValidationCheck.INTERACTIVE_OVERLAP, LayoutValidationSeverity.ERROR),
    ZERO_SIZE(LayoutValidationCheck.ZERO_SIZE, LayoutValidationSeverity.ERROR),
    BELOW_TARGET_SIZE(
            LayoutValidationCheck.BELOW_TARGET_SIZE, LayoutValidationSeverity.WARNING),
    DUPLICATE_TEST_ID(
            LayoutValidationCheck.DUPLICATE_TEST_ID, LayoutValidationSeverity.ERROR),
    MISSING_ACCESSIBLE_NAME(
            LayoutValidationCheck.MISSING_ACCESSIBLE_NAME, LayoutValidationSeverity.WARNING),
    KEYBOARD_UNREACHABLE(
            LayoutValidationCheck.KEYBOARD_UNREACHABLE, LayoutValidationSeverity.WARNING),
    OBSCURED(LayoutValidationCheck.OBSCURED, LayoutValidationSeverity.WARNING),
    INVALID_CLIP_SCROLL(
            LayoutValidationCheck.INVALID_CLIP_SCROLL, LayoutValidationSeverity.WARNING),
    INCONSISTENT_ALIGNMENT(
            LayoutValidationCheck.INCONSISTENT_ALIGNMENT, LayoutValidationSeverity.WARNING),
    INCONSISTENT_SPACING(
            LayoutValidationCheck.INCONSISTENT_SPACING, LayoutValidationSeverity.WARNING),
    CHECK_UNAVAILABLE(null, LayoutValidationSeverity.ERROR);

    private final LayoutValidationCheck check;
    private final LayoutValidationSeverity defaultSeverity;

    LayoutValidationReason(LayoutValidationCheck check,
            LayoutValidationSeverity defaultSeverity) {
        this.check = check;
        this.defaultSeverity = defaultSeverity;
    }

    /** Returns the owning check, or {@code null} for the availability reason. */
    public LayoutValidationCheck check() {
        return check;
    }

    /** Returns the default severity. */
    public LayoutValidationSeverity defaultSeverity() {
        return defaultSeverity;
    }
}
