package dev.gdx.uiharness.core.gesture;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, structurally balanced combined input gesture request. */
public record InputGestureRequest(int schemaVersion, List<Step> steps) {
    /** Original combined input gesture schema version. */
    public static final int SCHEMA_VERSION = 1;
    /** Maximum ordered steps in one request. */
    public static final int MAX_STEPS = 256;
    /** Maximum absolute delta on each mouse movement axis. */
    public static final int MAX_DELTA = 4096;
    /** Maximum supported libGDX mouse button. */
    public static final int MAX_BUTTON = 4;
    /** Maximum libGDX keycode accepted by the V1 contract. */
    public static final int MAX_KEYCODE = 255;
    /** Maximum keys owned by one gesture at the same time. */
    public static final int MAX_HELD_KEYS = 16;
    /** Maximum individual and cumulative wait count of either kind. */
    public static final int MAX_WAIT = 10_000;

    /** Validates and defensively copies one complete gesture before execution dependencies exist. */
    public InputGestureRequest {
        int maximumSteps = maximumSteps(schemaVersion);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.size() > maximumSteps) {
            throw new IllegalArgumentException(
                    "steps must contain between 1 and " + maximumSteps + " entries");
        }
        validateSequence(steps);
    }

    /** Returns the ordered-step bound for one supported schema version. */
    public static int maximumSteps(int schemaVersion) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported input gesture schemaVersion");
        }
        return MAX_STEPS;
    }

    private static void validateSequence(List<Step> steps) {
        LinkedHashSet<Control> held = new LinkedHashSet<>();
        int frameWaits = 0;
        int tickWaits = 0;
        boolean transition = false;
        for (Step step : steps) {
            Objects.requireNonNull(step, "step");
            switch (step) {
                case KeyDown down -> {
                    transition = true;
                    if (!held.add(new Control(Device.KEY, down.keycode()))) {
                        throw new IllegalArgumentException(
                                "key is already held by this gesture: " + down.keycode());
                    }
                    if (held.stream().filter(control -> control.device() == Device.KEY)
                            .count() > MAX_HELD_KEYS) {
                        throw new IllegalArgumentException(
                                "gesture exceeds " + MAX_HELD_KEYS + " held keys");
                    }
                }
                case KeyUp up -> {
                    transition = true;
                    if (!held.remove(new Control(Device.KEY, up.keycode()))) {
                        throw new IllegalArgumentException(
                                "key is not held by this gesture: " + up.keycode());
                    }
                }
                case MouseDown down -> {
                    transition = true;
                    if (!held.add(new Control(Device.MOUSE, down.button()))) {
                        throw new IllegalArgumentException("mouse button already held");
                    }
                }
                case MouseUp up -> {
                    transition = true;
                    if (!held.remove(new Control(Device.MOUSE, up.button()))) {
                        throw new IllegalArgumentException("mouse button is not held");
                    }
                }
                case MouseMove move -> transition = true;
                case WaitFrames wait -> {
                    frameWaits = boundedTotal(frameWaits, wait.count(), "frame waits");
                }
                case WaitTicks wait -> {
                    tickWaits = boundedTotal(tickWaits, wait.count(), "tick waits");
                }
            }
        }
        if (!transition) {
            throw new IllegalArgumentException("gesture must contain an input step");
        }
        if (!held.isEmpty()) {
            throw new IllegalArgumentException("gesture must release every owned key and button");
        }
    }

    private static int boundedTotal(int current, int increment, String dimension) {
        final int total;
        try {
            total = Math.addExact(current, increment);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(dimension + " overflow", overflow);
        }
        if (total > MAX_WAIT) {
            throw new IllegalArgumentException(dimension + " exceed " + MAX_WAIT);
        }
        return total;
    }

    /** Closed ordered gesture step union. */
    public sealed interface Step permits KeyDown, WaitFrames, WaitTicks, KeyUp, MouseMove, MouseDown, MouseUp {}

    /** Sends one key-down callback through the configured input processor. */
    public record KeyDown(int keycode) implements Step {
        /** Validates the bounded libGDX keycode. */
        public KeyDown {
            requireKeycode(keycode);
        }
    }

    /** Observes an exact positive number of later completed UI frames. */
    public record WaitFrames(int count) implements Step {
        /** Validates the bounded frame count. */
        public WaitFrames {
            requireCount(count, "frame count");
        }
    }

    /** Advances an exact positive number of controlled simulation ticks. */
    public record WaitTicks(int count) implements Step {
        /** Validates the bounded tick count. */
        public WaitTicks {
            requireCount(count, "tick count");
        }
    }

    /** Sends one key-up callback through the configured input processor. */
    public record KeyUp(int keycode) implements Step {
        /** Validates the bounded libGDX keycode. */
        public KeyUp {
            requireKeycode(keycode);
        }
    }

    /** Moves the application-owned relative pointer through production mouseMoved. */
    public record MouseMove(int deltaX, int deltaY) implements Step {
        /** Bounds each axis without overflowing on Integer.MIN_VALUE. */
        public MouseMove {
            if (deltaX < -MAX_DELTA || deltaX > MAX_DELTA
                    || deltaY < -MAX_DELTA || deltaY > MAX_DELTA) {
                throw new IllegalArgumentException("relative delta exceeds bound");
            }
        }
    }

    /** Presses one primary-pointer button. */
    public record MouseDown(int button) implements Step {
        /** Validates the closed button range. */
        public MouseDown { requireButton(button); }
    }

    /** Releases one primary-pointer button. */
    public record MouseUp(int button) implements Step {
        /** Validates the closed button range. */
        public MouseUp { requireButton(button); }
    }

    /** Input device identity for owned controls and cleanup. */
    public enum Device {
        /** Keyboard key. */
        KEY,
        /** Primary-pointer mouse button. */
        MOUSE
    }

    /** Device-qualified control identity; key and button numbers never alias. */
    public record Control(Device device, int code) {
        /** Validates the device-specific code. */
        public Control {
            Objects.requireNonNull(device, "device");
            if (device == Device.KEY) { requireKeycode(code); }
            else { requireButton(code); }
        }
    }

    private static void requireButton(int button) {
        if (button < 0 || button > MAX_BUTTON) {
            throw new IllegalArgumentException("button must be between 0 and 4");
        }
    }

    private static void requireKeycode(int keycode) {
        if (keycode < 0 || keycode > MAX_KEYCODE) {
            throw new IllegalArgumentException(
                    "keycode must be between 0 and " + MAX_KEYCODE);
        }
    }

    private static void requireCount(int count, String name) {
        if (count < 1 || count > MAX_WAIT) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + MAX_WAIT);
        }
    }
}
