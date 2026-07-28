package dev.gdx.uiharness.core.action;

import java.util.Objects;

/** Backend-neutral input action resolved lazily against a locator. */
public sealed interface Action permits Action.Click, Action.Hover, Action.Focus, Action.Fill,
        Action.Press, Action.Scroll, Action.Drag, Action.Pointer {
    /** Whether visibility, stability, viewport, and hit-target checks may be bypassed. */
    boolean force();

    static Click click() {
        return click(false);
    }

    static Click click(boolean force) {
        return new Click(0, 0, force);
    }

    static Hover hover() {
        return new Hover(false);
    }

    static Hover hover(boolean force) {
        return new Hover(force);
    }

    static Focus focus() {
        return new Focus(false);
    }

    static Focus focus(boolean force) {
        return new Focus(force);
    }

    static Fill fill(String value) {
        return new Fill(value, false);
    }

    static Fill fill(String value, boolean force) {
        return new Fill(value, force);
    }

    static Press press(int keycode) {
        return new Press(keycode, false);
    }

    static Press press(int keycode, boolean force) {
        return new Press(keycode, force);
    }

    static Scroll scroll(float amountX, float amountY) {
        return new Scroll(amountX, amountY, false);
    }

    static Scroll scroll(float amountX, float amountY, boolean force) {
        return new Scroll(amountX, amountY, force);
    }

    static Drag drag(float deltaX, float deltaY) {
        return new Drag(deltaX, deltaY, 0, 0, false);
    }

    static Drag drag(float deltaX, float deltaY, int pointer, int button, boolean force) {
        return new Drag(deltaX, deltaY, pointer, button, force);
    }

    static Pointer pointer(
            PointerPhase phase,
            float offsetX,
            float offsetY,
            int pointer,
            int button) {
        return new Pointer(phase, offsetX, offsetY, pointer, button, false);
    }

    static Pointer pointer(
            PointerPhase phase,
            float offsetX,
            float offsetY,
            int pointer,
            int button,
            boolean force) {
        return new Pointer(phase, offsetX, offsetY, pointer, button, force);
    }

    /** Primary-button click at the selected actor's actionable point. */
    record Click(int pointer, int button, boolean force) implements Action {
        public Click {
            requirePointer(pointer);
        }
    }

    /** Mouse movement to the selected actor's actionable point. */
    record Hover(boolean force) implements Action {}

    /** Keyboard focus assignment through the owning Stage. */
    record Focus(boolean force) implements Action {}

    /** Complete text replacement through keyboard input. */
    record Fill(String value, boolean force) implements Action {
        public Fill {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Key-down/key-up pair delivered to the configured input processor. */
    record Press(int keycode, boolean force) implements Action {
        public Press {
            if (keycode < 0) {
                throw new IllegalArgumentException("keycode must be non-negative");
            }
        }
    }

    /** Scroll input routed with the selected actor as the Stage scroll focus. */
    record Scroll(float amountX, float amountY, boolean force) implements Action {
        public Scroll {
            requireFinite(amountX, "amountX");
            requireFinite(amountY, "amountY");
        }
    }

    /** Pointer drag from the actionable point by a screen-space delta. */
    record Drag(
            float deltaX,
            float deltaY,
            int pointer,
            int button,
            boolean force) implements Action {
        public Drag {
            requireFinite(deltaX, "deltaX");
            requireFinite(deltaY, "deltaY");
            requirePointer(pointer);
        }
    }

    /** One explicit pointer transition at an offset from the actionable point. */
    record Pointer(
            PointerPhase phase,
            float offsetX,
            float offsetY,
            int pointer,
            int button,
            boolean force) implements Action {
        public Pointer {
            Objects.requireNonNull(phase, "phase");
            requireFinite(offsetX, "offsetX");
            requireFinite(offsetY, "offsetY");
            requirePointer(pointer);
        }
    }

    enum PointerPhase {
        DOWN,
        MOVE,
        UP
    }

    private static void requirePointer(int pointer) {
        if (pointer < 0) {
            throw new IllegalArgumentException("pointer must be non-negative");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
