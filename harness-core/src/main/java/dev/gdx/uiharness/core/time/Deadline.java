package dev.gdx.uiharness.core.time;

import java.time.Duration;
import java.util.Objects;

/** Immutable deadline measured exclusively by one injected monotonic clock. */
public final class Deadline {
    private final MonotonicClock clock;
    private final long startedAtNanos;
    private final long timeoutNanos;
    private final Duration timeout;

    private Deadline(MonotonicClock clock, Duration timeout) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        timeoutNanos = timeout.toNanos();
        startedAtNanos = clock.nanoTime();
    }

    /** Creates a deadline beginning at the clock's current monotonic time. */
    public static Deadline after(MonotonicClock clock, Duration timeout) {
        return new Deadline(clock, timeout);
    }

    /** Returns the clock defining this deadline's time domain. */
    public MonotonicClock clock() {
        return clock;
    }

    /** Returns the configured timeout duration. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns monotonic elapsed time, clamped to zero if a broken clock moves backwards. */
    public Duration elapsed() {
        long elapsedNanos = clock.nanoTime() - startedAtNanos;
        return Duration.ofNanos(Math.max(0L, elapsedNanos));
    }

    /** Returns remaining monotonic time, never less than zero. */
    public Duration remaining() {
        long elapsedNanos = elapsed().toNanos();
        return Duration.ofNanos(Math.max(0L, timeoutNanos - elapsedNanos));
    }

    /** Returns whether elapsed monotonic time has reached this deadline. */
    public boolean isExpired() {
        return elapsed().toNanos() >= timeoutNanos;
    }
}
