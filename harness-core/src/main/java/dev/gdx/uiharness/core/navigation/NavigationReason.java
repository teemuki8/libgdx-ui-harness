package dev.gdx.uiharness.core.navigation;

/** Closed terminal reasons for navigation validation. */
public enum NavigationReason {
    COMPLETE,
    CYCLE,
    DEAD_END,
    MODAL_ESCAPE,
    FOCUS_LOST,
    UNREACHABLE_CONTROL,
    UNSUPPORTED_CONTROLLER_PATH,
    DEADLINE,
    TRUNCATED
}
