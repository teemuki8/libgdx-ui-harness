package dev.gdx.uiharness.core.assertion;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Injected one-shot wake-up source for monotonic assertion deadlines. */
@FunctionalInterface
public interface DeadlineWakeup {
    /** Registers one wake-up after the bounded delay. */
    Registration schedule(Duration delay, Runnable wakeup);

    /** Creates wake-ups owned by the supplied lifecycle-managed executor. */
    static DeadlineWakeup scheduledBy(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return (delay, wakeup) -> {
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(wakeup, "wakeup");
            var future = executor.schedule(wakeup, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        };
    }

    /** A cancellable wake-up registration. */
    @FunctionalInterface
    interface Registration {
        void cancel();
    }
}
