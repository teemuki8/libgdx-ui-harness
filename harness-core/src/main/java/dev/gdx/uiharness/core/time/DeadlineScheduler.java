package dev.gdx.uiharness.core.time;

import java.time.Duration;

/**
 * Schedules deadline signals independently of render-frame progress.
 *
 * <p>The caller owns this scheduler and remains responsible for closing any resources behind it.
 * Signals may run on any thread; implementations must tolerate a {@link Cancellation} racing the
 * scheduled signal, in which case the signal may still be observed once and must be ignored by
 * the scheduling party's terminal-state guard.
 */
@FunctionalInterface
public interface DeadlineScheduler {
    /** Schedules one signal after the supplied monotonic delay. */
    Cancellation schedule(Duration delay, Runnable signal);

    /** Invalidates a scheduled signal without interrupting work already running. */
    @FunctionalInterface
    interface Cancellation {
        void cancel();
    }
}
