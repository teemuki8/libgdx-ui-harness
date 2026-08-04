package dev.gdx.uiharness.core.navigation;

/** Closed transport-neutral inputs that may be observed in a navigation traversal. */
public enum NavigationInput {
    TAB(false),
    SHIFT_TAB(false),
    UP(false),
    DOWN(false),
    LEFT(false),
    RIGHT(false),
    ESCAPE(false),
    BACK(false),
    CONTROLLER_UP(true),
    CONTROLLER_DOWN(true),
    CONTROLLER_LEFT(true),
    CONTROLLER_RIGHT(true),
    CONTROLLER_CONFIRM(true),
    CONTROLLER_BACK(true);

    private final boolean controller;

    NavigationInput(boolean controller) {
        this.controller = controller;
    }

    /** Returns whether this input requires controller integration. */
    public boolean isController() {
        return controller;
    }
}
