package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RenderThreadSchedulerTest {
    @Test void queuedStageReadExecutesOnlyWhenRenderThreadDrains() {
        FakeClock clock = new FakeClock();
        Thread renderThread = Thread.currentThread();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(4);
        CompletionStage<Thread> submitted = scheduler.submit(
                Thread::currentThread, Deadline.after(clock, Duration.ofSeconds(1)));

        assertFalse(submitted.toCompletableFuture().isDone());
        scheduler.drain();

        assertEquals(renderThread, submitted.toCompletableFuture().join());
    }

    @Test void commandsSubmittedWhileDrainingWaitForTheNextHook() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(4);
        Deadline deadline = Deadline.after(clock, Duration.ofSeconds(1));
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Callable<Integer>> repeating = new AtomicReference<>();
        repeating.set(() -> {
            int execution = executions.incrementAndGet();
            if (execution < 3) {
                scheduler.submit(repeating.get(), deadline);
            }
            return execution;
        });
        scheduler.submit(repeating.get(), deadline);

        scheduler.drain();
        assertEquals(1, executions.get());
        scheduler.drain();
        assertEquals(2, executions.get());
        scheduler.drain();
        assertEquals(3, executions.get());
    }

    @Test void cancellingABatchedCommandCannotPullANewSubmissionIntoCurrentHook() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(3);
        Deadline deadline = Deadline.after(clock, Duration.ofSeconds(1));
        AtomicReference<CompletableFuture<String>> second = new AtomicReference<>();
        AtomicReference<CompletableFuture<String>> replacement = new AtomicReference<>();
        scheduler.submit(
                () -> {
                    second.get().cancel(false);
                    replacement.set(scheduler.submit(
                            () -> "replacement", deadline).toCompletableFuture());
                    return "first";
                },
                deadline);
        second.set(scheduler.submit(() -> "second", deadline).toCompletableFuture());

        scheduler.drain();

        assertTrue(second.get().isCancelled());
        assertFalse(replacement.get().isDone());
        scheduler.drain();
        assertEquals("replacement", replacement.get().join());
    }

    @Test void queueTimeCountsTowardDeadlineAndExpiredWorkNeverExecutes() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(4);
        AtomicBoolean executed = new AtomicBoolean();
        CompletionStage<String> submitted = scheduler.submit(
                () -> {
                    executed.set(true);
                    return "unexpected";
                },
                Deadline.after(clock, Duration.ofMillis(50)));

        clock.advance(Duration.ofMillis(50));
        scheduler.drain();

        CompletionException completion = assertThrows(
                CompletionException.class, () -> submitted.toCompletableFuture().join());
        HarnessException error = assertInstanceOf(HarnessException.class, completion.getCause());
        assertEquals(ErrorCode.TIMEOUT, error.code());
        assertEquals(Duration.ofMillis(50), error.evidence().elapsed());
        assertFalse(executed.get());
    }

    @Test void cancellationBeforeStartPreventsExecution() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(4);
        AtomicBoolean executed = new AtomicBoolean();
        CompletableFuture<String> submitted = scheduler.submit(
                () -> {
                    executed.set(true);
                    return "unexpected";
                },
                Deadline.after(clock, Duration.ofSeconds(1))).toCompletableFuture();

        assertTrue(submitted.cancel(false));
        scheduler.drain();

        assertTrue(submitted.isCancelled());
        assertFalse(executed.get());
    }

    @Test void cancellationImmediatelyReleasesBoundedQueueCapacity() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(1);
        Deadline deadline = Deadline.after(clock, Duration.ofSeconds(1));
        CompletableFuture<String> cancelled = scheduler.submit(
                () -> "cancelled", deadline).toCompletableFuture();
        assertTrue(cancelled.cancel(false));

        CompletableFuture<String> admitted = scheduler.submit(
                () -> "admitted", deadline).toCompletableFuture();
        scheduler.drain();

        assertEquals("admitted", admitted.join());
    }

    @Test void dispatchedWorkCompletesAtomicallyDespiteCancellationAttempt() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(4);
        AtomicReference<CompletableFuture<String>> submittedReference = new AtomicReference<>();
        AtomicBoolean cancellationAccepted = new AtomicBoolean(true);
        CompletableFuture<String> submitted = scheduler.submit(
                () -> {
                    cancellationAccepted.set(submittedReference.get().cancel(false));
                    return "completed";
                },
                Deadline.after(clock, Duration.ofSeconds(1))).toCompletableFuture();
        submittedReference.set(submitted);

        scheduler.drain();

        assertFalse(cancellationAccepted.get());
        assertEquals("completed", submitted.join());
    }

    @Test void disposalFailsQueuedAndFutureSubmissionsWithoutRunningThem() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(2);
        AtomicBoolean executed = new AtomicBoolean();
        CompletableFuture<String> queued = scheduler.submit(
                () -> {
                    executed.set(true);
                    return "unexpected";
                },
                Deadline.after(clock, Duration.ofSeconds(1))).toCompletableFuture();

        scheduler.close();
        scheduler.drain();

        assertSessionClosed(queued);
        assertFalse(executed.get());
        assertSessionClosed(scheduler.submit(
                () -> "unexpected", Deadline.after(clock, Duration.ofSeconds(1)))
                .toCompletableFuture());
    }

    @Test void queueCapacityIsBounded() {
        FakeClock clock = new FakeClock();
        RenderThreadScheduler scheduler = new RenderThreadScheduler(1);
        Deadline deadline = Deadline.after(clock, Duration.ofSeconds(1));
        scheduler.submit(() -> "first", deadline);

        CompletableFuture<String> rejected = scheduler.submit(
                () -> "second", deadline).toCompletableFuture();

        CompletionException completion = assertThrows(CompletionException.class, rejected::join);
        HarnessException error = assertInstanceOf(HarnessException.class, completion.getCause());
        assertEquals(ErrorCode.LIMIT_EXCEEDED, error.code());
    }

    @Test void onlyOwningRenderThreadMayDrain() {
        RenderThreadScheduler scheduler = new RenderThreadScheduler(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> attemptedDrain = CompletableFuture.runAsync(
                    () -> assertThrows(IllegalStateException.class, scheduler::drain), executor);
            attemptedDrain.join();
        }
    }

    private static void assertSessionClosed(CompletableFuture<?> future) {
        CompletionException completion = assertThrows(CompletionException.class, future::join);
        HarnessException error = assertInstanceOf(HarnessException.class, completion.getCause());
        assertEquals(ErrorCode.SESSION_CLOSED, error.code());
    }

    private static final class FakeClock implements MonotonicClock {
        private final AtomicLong nanos = new AtomicLong();

        @Override public long nanoTime() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
