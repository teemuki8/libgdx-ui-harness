package dev.gdx.uiharness.scene2d;

import java.time.Duration;

/**
 * Schedules scenario deadline signals independently of render-frame progress.
 *
 * <p>The caller owns this scheduler and remains responsible for closing any resources behind it.
 */
@FunctionalInterface
public interface Scene2dScenarioDeadlineScheduler {
    /** Schedules one signal after the supplied monotonic delay. */
    Cancellation schedule(Duration delay, Runnable signal);

    /** Invalidates a scheduled signal without interrupting work already running. */
    @FunctionalInterface
    interface Cancellation {
        void cancel();
    }
}
