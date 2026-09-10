package dev.gdx.uiharness.scene2d;

/** Application-owned cursor origin for production relative mouse input, render-thread confined. */
public interface RelativePointerAdapter {
    /** Returns the current application cursor position for button dispatch. */
    Position position();

    /**
     * Computes the next cursor position; the runner then calls production mouseMoved.
     * Do not overwrite the production processor's last-delivered coordinates here: that
     * processor must derive the same delta as physical captured-mouse callbacks. The application
     * resets its cursor origin and capture state together on focus, pause, and capture changes.
     */
    Position moveRelative(int deltaX, int deltaY);

    /** Integer screen coordinates, which may extend outside the viewport when captured. */
    record Position(int x, int y) {}
}
