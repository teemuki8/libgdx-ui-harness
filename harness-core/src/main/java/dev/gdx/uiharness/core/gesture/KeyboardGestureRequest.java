package dev.gdx.uiharness.core.gesture;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, structurally balanced keyboard gesture request. */
public record KeyboardGestureRequest(int schemaVersion, List<Step> steps) {
    /** Only supported keyboard gesture schema version. */
    public static final int SCHEMA_VERSION = 1;
    /** Maximum ordered steps in one gesture. */
    public static final int MAX_STEPS = 64;
    /** Maximum libGDX keycode accepted by the V1 contract. */
    public static final int MAX_KEYCODE = 255;
    /** Maximum keys owned by one gesture at the same time. */
    public static final int MAX_HELD_KEYS = 16;
    /** Maximum individual and cumulative wait count of either kind. */
    public static final int MAX_WAIT = 10_000;

    /** Validates and defensively copies one complete gesture before execution dependencies exist. */
    public KeyboardGestureRequest {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion must be " + SCHEMA_VERSION);
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.size() < 2 || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "steps must contain between 2 and " + MAX_STEPS + " entries");
        }
        validateSequence(steps);
    }

    private static void validateSequence(List<Step> steps) {
        LinkedHashSet<Integer> held = new LinkedHashSet<>();
        int frameWaits = 0;
        int tickWaits = 0;
        boolean transition = false;
        for (Step step : steps) {
            Objects.requireNonNull(step, "step");
            switch (step) {
                case KeyDown down -> {
                    transition = true;
                    if (!held.add(down.keycode())) {
                        throw new IllegalArgumentException(
                                "key is already held by this gesture: " + down.keycode());
                    }
                    if (held.size() > MAX_HELD_KEYS) {
                        throw new IllegalArgumentException(
                                "gesture exceeds " + MAX_HELD_KEYS + " held keys");
                    }
                }
                case KeyUp up -> {
                    transition = true;
                    if (!held.remove(up.keycode())) {
                        throw new IllegalArgumentException(
                                "key is not held by this gesture: " + up.keycode());
                    }
                }
                case WaitFrames wait -> {
                    requireHeldKey(held);
                    frameWaits = boundedTotal(frameWaits, wait.count(), "frame waits");
                }
                case WaitTicks wait -> {
                    requireHeldKey(held);
                    tickWaits = boundedTotal(tickWaits, wait.count(), "tick waits");
                }
            }
        }
        if (!transition) {
            throw new IllegalArgumentException("gesture must contain a key transition");
        }
        if (!held.isEmpty()) {
            throw new IllegalArgumentException("gesture must release every owned key");
        }
    }

    private static void requireHeldKey(LinkedHashSet<Integer> held) {
        if (held.isEmpty()) {
            throw new IllegalArgumentException("wait requires at least one held key");
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
    public sealed interface Step permits KeyDown, WaitFrames, WaitTicks, KeyUp {}

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
