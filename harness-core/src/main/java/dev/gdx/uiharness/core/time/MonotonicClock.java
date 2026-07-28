package dev.gdx.uiharness.core.time;

/** Monotonic nanosecond time source used for deadlines and controlled fixtures. */
@FunctionalInterface
public interface MonotonicClock {
    /** Returns the current monotonic time in nanoseconds from an arbitrary origin. */
    long nanoTime();

    /** Returns a clock backed by {@link System#nanoTime()}. */
    static MonotonicClock system() {
        return System::nanoTime;
    }
}
