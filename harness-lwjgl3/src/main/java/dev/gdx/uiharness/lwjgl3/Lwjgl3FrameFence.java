package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Graphics-thread fence that dispatches work only after an explicitly completed rendered frame. */
public final class Lwjgl3FrameFence implements FrameSignal, AutoCloseable {
    private static final int DEFAULT_CAPACITY = 64;

    private final Thread ownerThread = Thread.currentThread();
    private final int capacity;
    private final DeadlineScheduler deadlines;
    private final OwnedDeadlineScheduler ownedScheduler;
    private final Object lifecycle = new Object();
    private final ArrayDeque<Command<?>> queued = new ArrayDeque<>();
    private final CopyOnWriteArrayList<FrameListener> listeners =
            new CopyOnWriteArrayList<>();
    private boolean open = true;
    private Thread closingThread;
    private volatile Throwable closeFailure;

    /** Creates a fence owned by the current graphics thread with the default bounded capacity. */
    public Lwjgl3FrameFence(DeadlineScheduler deadlines) {
        this(deadlines, DEFAULT_CAPACITY);
    }

    /** Creates a fence owned by the current graphics thread. */
    public Lwjgl3FrameFence(DeadlineScheduler deadlines, int capacity) {
        this(deadlines, capacity, null);
    }

    /** Creates a fence with the default bounded pending-work capacity. */
    public Lwjgl3FrameFence() {
        this(DEFAULT_CAPACITY);
    }

    /** Creates a fence owned by the current graphics thread with an owned deadline scheduler. */
    public Lwjgl3FrameFence(int capacity) {
        this(new OwnedDeadlineScheduler(), capacity);
    }

    private Lwjgl3FrameFence(OwnedDeadlineScheduler owned, int capacity) {
        this(owned, capacity, owned);
    }

    /**
     * Ownership-aware constructor: {@code ownedScheduler} is non-null only when this fence created
     * the scheduler and must shut it down on close.
     */
    private Lwjgl3FrameFence(
            DeadlineScheduler deadlines, int capacity, OwnedDeadlineScheduler ownedScheduler) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.capacity = capacity;
        this.ownedScheduler = ownedScheduler;
    }

    /** Queues work for the next frame completed by {@link #completedFrame(long, long)}. */
    public <T> CompletionStage<T> afterNextFrame(
            FrameTask<T> task, Deadline deadline) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(deadline, "deadline");
        Command<T> command = new Command<>(task, deadline);
        HarnessException rejection = null;
        synchronized (lifecycle) {
            if (!open) {
                rejection = closedFailure();
            } else if (deadline.isExpired()) {
                rejection = timeoutFailure(deadline);
            } else if (queued.size() >= capacity) {
                rejection = limitFailure(capacity);
            } else {
                command.markQueued();
                queued.addLast(command);
            }
        }
        if (rejection != null) {
            command.completeExceptionally(rejection);
        } else {
            command.armDeadline();
        }
        return command;
    }

    /**
     * Claims and executes work already queued for this rendered frame, then publishes completion.
     * Work submitted by a completion listener waits for the following frame.
     */
    public void completedFrame(long revision, long frame) {
        requireOwnerThread();
        Frame completed = new Frame(revision, frame);
        List<Command<?>> batch;
        List<DeadlineScheduler.Cancellation> cancellations;
        synchronized (lifecycle) {
            if (!open) {
                throw new IllegalStateException("frame fence is closed");
            }
            batch = new ArrayList<>(queued);
            cancellations = new ArrayList<>(batch.size());
            for (Command<?> command : batch) {
                DeadlineScheduler.Cancellation cancellation = command.markClaimed();
                if (cancellation != null) {
                    cancellations.add(cancellation);
                }
            }
            queued.clear();
        }
        cancelAll(cancellations);
        for (Command<?> command : batch) {
            command.execute(completed);
        }
        for (FrameListener listener : listeners) {
            listener.onFrame(completed);
        }
    }

    /** Registers a completed-frame listener until the returned subscription is closed. */
    @Override public Subscription subscribe(FrameListener listener) {
        Objects.requireNonNull(listener, "listener");
        boolean closed;
        synchronized (lifecycle) {
            closed = !open;
            if (!closed) {
                listeners.add(listener);
            }
        }
        if (closed) {
            listener.onClosed();
            return () -> {};
        }
        return () -> listeners.remove(listener);
    }

    /**
     * Fails queued work and closes all frame subscriptions without touching the window.
     *
     * <p>The first caller performs the cleanup; concurrent closers wait for it to finish and a
     * reentrant close from the closing thread's own callbacks returns immediately. All callbacks
     * and cancellations run outside the lifecycle monitor.
     */
    @Override public void close() {
        boolean cleanup;
        boolean waited = false;
        boolean interrupted = false;
        synchronized (lifecycle) {
            while (closingThread != null && closingThread != Thread.currentThread()) {
                waited = true;
                try {
                    lifecycle.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (closingThread == Thread.currentThread() || !open) {
                cleanup = false;
            } else {
                open = false;
                closingThread = Thread.currentThread();
                cleanup = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!cleanup) {
            if (waited && closeFailure != null) {
                rethrowUnchecked(closeFailure);
            }
            return;
        }
        List<Command<?>> pending;
        List<DeadlineScheduler.Cancellation> cancellations;
        synchronized (lifecycle) {
            pending = new ArrayList<>(queued);
            cancellations = new ArrayList<>(pending.size());
            for (Command<?> command : pending) {
                DeadlineScheduler.Cancellation cancellation = command.markClaimed();
                if (cancellation != null) {
                    cancellations.add(cancellation);
                }
            }
            queued.clear();
        }
        Throwable failure = null;
        try {
            for (DeadlineScheduler.Cancellation cancellation : cancellations) {
                try {
                    cancellation.cancel();
                } catch (Throwable throwable) {
                    failure = aggregate(failure, throwable);
                }
            }
            // The owned scheduler is shut down unconditionally so a legacy fence never leaks
            // its worker, even when an earlier cleanup step failed.
            if (ownedScheduler != null) {
                try {
                    ownedScheduler.shutdownNowAndAwait();
                } catch (Throwable throwable) {
                    failure = aggregate(failure, throwable);
                }
            }
            HarnessException closed = closedFailure();
            for (Command<?> command : pending) {
                command.completeExceptionally(closed);
            }
            // Every listener is notified even when one throws; retention is cleared afterwards.
            for (FrameListener listener : listeners) {
                try {
                    listener.onClosed();
                } catch (Throwable throwable) {
                    failure = aggregate(failure, throwable);
                }
            }
            listeners.clear();
        } finally {
            synchronized (lifecycle) {
                closingThread = null;
                closeFailure = failure;
                lifecycle.notifyAll();
            }
        }
        if (failure != null) {
            rethrowUnchecked(failure);
        }
    }

    private static void cancelAll(List<DeadlineScheduler.Cancellation> cancellations) {
        for (DeadlineScheduler.Cancellation cancellation : cancellations) {
            cancellation.cancel();
        }
    }

    /** Rethrows a close cleanup failure without requiring a checked-exception declaration. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrowUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * Combines per-step cleanup failures, keeping the first as the primary failure and attaching
     * later failures as suppressed so the caller observes one consistent close outcome. The same
     * instance thrown again is retained once: {@link Throwable#addSuppressed} forbids
     * self-suppression and would otherwise abort the remaining cleanup.
     */
    private static Throwable aggregate(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    /**
     * Deadline scheduler owned by a fence created through the legacy {@code Lwjgl3FrameFence()} and
     * {@code Lwjgl3FrameFence(int)} constructors. A daemon worker executes deadline signals;
     * {@link #shutdownNowAndAwait()} stops it when the fence closes so a legacy fence never leaks
     * a scheduler thread. Cancelled signals are removed from the work queue so they neither run
     * nor retain the fence after cancellation.
     */
    private static final class OwnedDeadlineScheduler implements DeadlineScheduler {
        private static final Duration SHUTDOWN_BOUND = Duration.ofSeconds(1);

        private final ScheduledThreadPoolExecutor executor;

        OwnedDeadlineScheduler() {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "lwjgl3-frame-fence-deadlines");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
        }

        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            ScheduledFuture<?> scheduled =
                    executor.schedule(signal, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> scheduled.cancel(false);
        }

        /**
         * Stops the worker promptly. Deadline signals are short monitor checks, so the bounded
         * wait only covers a signal already running when the fence closes.
         */
        void shutdownNowAndAwait() {
            executor.shutdownNow();
            try {
                executor.awaitTermination(SHUTDOWN_BOUND.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("completedFrame must run on the owning graphics thread");
        }
    }

    private static HarnessException timeoutFailure(Deadline deadline) {
        return new HarnessException(
                ErrorCode.TIMEOUT,
                "completed frame was not available before the deadline",
                new ErrorEvidence(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        deadline.elapsed(),
                        OptionalLong.empty(),
                        Optional.empty(),
                        List.of(),
                        Map.of(
                                "elapsed", deadline.elapsed().toString(),
                                "timeout", deadline.timeout().toString()),
                        List.of()));
    }

    private static HarnessException closedFailure() {
        return new HarnessException(
                ErrorCode.SESSION_CLOSED,
                "completed-frame fence is closed",
                ErrorEvidence.empty());
    }

    private static HarnessException limitFailure(int capacity) {
        return new HarnessException(
                ErrorCode.LIMIT_EXCEEDED,
                "completed-frame queue exceeds its configured capacity",
                ErrorEvidence.ofDetails(Map.of(
                        "dimension", "queuedCaptures",
                        "limit", Integer.toString(capacity))));
    }

    /** Work that receives the identity of the completed frame whose framebuffer it observes. */
    @FunctionalInterface
    public interface FrameTask<T> {
        /** Executes on the owning graphics thread after rendering is complete. */
        T execute(Frame frame) throws Exception;
    }

    private final class Command<T> extends CompletableFuture<T> {
        private final FrameTask<T> task;
        private final Deadline deadline;
        private DeadlineScheduler.Cancellation deadlineCancellation;
        private CommandState state = CommandState.NEW;

        Command(FrameTask<T> task, Deadline deadline) {
            this.task = task;
            this.deadline = deadline;
        }

        void markQueued() {
            state = CommandState.QUEUED;
        }

        /**
         * Arms one deadline signal for the queued command. A frame may claim the command before
         * the registration lands; the claim under {@link #lifecycle} then cancels it immediately.
         * The token's {@link Cancellation} is invoked only after leaving the monitor so a
         * synchronous cancellation never runs under the lifecycle lock. When an external
         * scheduler rejects the registration, the admission is rolled back terminally before the
         * original failure is rethrown, so the queued slot is released at once and the command
         * can never run on a completed frame.
         */
        void armDeadline() {
            DeadlineScheduler.Cancellation scheduled;
            try {
                scheduled = deadlines.schedule(deadline.remaining(), this::deadlineReached);
            } catch (RejectedExecutionException failure) {
                if (ownedScheduler == null) {
                    // An external scheduler rejected the registration after this command was
                    // admitted. Roll the admission back so its capacity slot is released
                    // immediately and no completed frame can claim it, then surface the original
                    // synchronous failure.
                    rollbackAdmission(failure);
                    throw failure;
                }
                // The fence's own scheduler was shut down after this command was queued, either
                // by a concurrent close or before the fence was used. Fail the command exactly
                // as close() would instead of surfacing an internal registration error.
                completeAsClosed();
                return;
            }
            boolean cancelScheduled;
            synchronized (lifecycle) {
                if (state == CommandState.QUEUED && deadlineCancellation == null) {
                    deadlineCancellation = scheduled;
                    cancelScheduled = false;
                } else {
                    cancelScheduled = true;
                }
            }
            if (cancelScheduled) {
                scheduled.cancel();
            }
        }

        /**
         * Rolls back an admitted command whose deadline registration was rejected, so the queued
         * slot and its capacity are released immediately and no completed frame can ever claim
         * the work. Claims terminal state under {@link #lifecycle} and completes the future
         * outside it; a concurrent frame or close that already claimed the command wins and its
         * terminal outcome stays exactly-once.
         */
        void rollbackAdmission(RejectedExecutionException failure) {
            boolean claimed;
            synchronized (lifecycle) {
                claimed = state == CommandState.QUEUED && queued.remove(this);
                if (claimed) {
                    state = CommandState.TERMINAL;
                    deadlineCancellation = null;
                }
            }
            if (claimed) {
                completeExceptionally(failure);
            }
        }

        /**
         * Fails a queued command with {@link #closedFailure()} when the fence's own scheduler can
         * no longer arm its deadline. Claims under {@link #lifecycle} and completes outside it;
         * a concurrent close that already claimed the command wins and completes it itself.
         */
        void completeAsClosed() {
            boolean claimed;
            synchronized (lifecycle) {
                claimed = state == CommandState.QUEUED && queued.remove(this);
                if (claimed) {
                    state = CommandState.TERMINAL;
                    deadlineCancellation = null;
                }
            }
            if (claimed) {
                completeExceptionally(closedFailure());
            }
        }

        /** Claims the timeout under {@link #lifecycle}; a late signal observes the claimed state. */
        void deadlineReached() {
            boolean claimed;
            synchronized (lifecycle) {
                claimed = state == CommandState.QUEUED && queued.remove(this);
                if (claimed) {
                    state = CommandState.TERMINAL;
                    deadlineCancellation = null;
                }
            }
            if (claimed) {
                completeExceptionally(timeoutFailure(deadline));
            }
        }

        /**
         * Claims the timeout under {@link #lifecycle} and detaches its token. The caller must
         * invoke the returned {@link Cancellation} only after leaving the monitor so a synchronous
         * cancellation never runs under the lifecycle lock. Returns {@code null} when no signal
         * was armed.
         */
        DeadlineScheduler.Cancellation markClaimed() {
            if (state != CommandState.QUEUED) {
                throw new IllegalStateException("only queued frame work can be claimed");
            }
            state = CommandState.CLAIMED;
            DeadlineScheduler.Cancellation cancellation = deadlineCancellation;
            deadlineCancellation = null;
            return cancellation;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            DeadlineScheduler.Cancellation cancellation;
            synchronized (lifecycle) {
                if (state != CommandState.QUEUED || !queued.remove(this)) {
                    return false;
                }
                state = CommandState.TERMINAL;
                cancellation = deadlineCancellation;
                deadlineCancellation = null;
            }
            if (cancellation != null) {
                cancellation.cancel();
            }
            return super.cancel(mayInterruptIfRunning);
        }

        void execute(Frame frame) {
            if (deadline.isExpired()) {
                completeExceptionally(timeoutFailure(deadline));
                return;
            }
            try {
                complete(task.execute(frame));
            } catch (HarnessException exception) {
                completeExceptionally(exception);
            } catch (Throwable failure) {
                completeExceptionally(new HarnessException(
                        ErrorCode.RENDER_THREAD_FAILURE,
                        "completed-frame graphics work failed",
                        ErrorEvidence.empty(),
                        failure));
            }
        }
    }

    private enum CommandState {
        NEW,
        QUEUED,
        CLAIMED,
        TERMINAL
    }
}
