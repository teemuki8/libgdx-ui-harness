package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Bounded per-session command queue drained only by its owning render thread. */
public final class RenderThreadScheduler implements AutoCloseable {
    private final Thread ownerThread = Thread.currentThread();
    private final int capacity;
    private final Object lifecycle = new Object();
    private final ArrayDeque<ScheduledCommand<?>> queue = new ArrayDeque<>();
    private final ArrayDeque<ScheduledCommand<?>> activeBatch = new ArrayDeque<>();
    private boolean draining;
    private boolean open = true;

    /** Creates a scheduler owned by the current render thread. */
    public RenderThreadScheduler(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Returns whether the caller is this scheduler's immutable owning render thread. */
    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    /** Returns whether a drain is currently executing on the owning render thread. */
    public boolean isDraining() {
        synchronized (lifecycle) {
            return draining;
        }
    }

    /** Enqueues work for the owning render thread, including queue time in its deadline. */
    public <T> CompletionStage<T> submit(Callable<T> callable, Deadline deadline) {
        ScheduledCommand<T> command = new ScheduledCommand<>(
                Objects.requireNonNull(callable, "callable"),
                Objects.requireNonNull(deadline, "deadline"));
        HarnessException rejection = null;
        synchronized (lifecycle) {
            if (!open) {
                rejection = sessionClosed();
            } else if (deadline.isExpired()) {
                rejection = timeout(deadline);
            } else if (queue.size() + activeBatch.size() >= capacity) {
                rejection = queueFull(capacity);
            } else {
                queue.addLast(command);
            }
        }
        if (rejection != null) {
            command.failQueued(rejection);
        }
        return command;
    }

    /** Drains all currently queued commands from the owning render-loop hook. */
    public void drain() {
        requireOwnerThread();
        List<ScheduledCommand<?>> batch;
        synchronized (lifecycle) {
            if (draining) {
                throw new IllegalStateException("scheduler drain is not reentrant");
            }
            draining = true;
            batch = new ArrayList<>(queue);
            queue.clear();
            activeBatch.addAll(batch);
        }
        try {
            for (ScheduledCommand<?> command : batch) {
                Dispatch dispatch;
                synchronized (lifecycle) {
                    activeBatch.remove(command);
                    dispatch = command.tryDispatch();
                }
                if (dispatch == Dispatch.EXPIRED) {
                    command.completeExpiry();
                } else if (dispatch == Dispatch.RUN) {
                    command.execute();
                }
            }
        } finally {
            synchronized (lifecycle) {
                draining = false;
            }
        }
    }

    /** Fails all queued work and rejects future submissions without interrupting dispatched work. */
    @Override public void close() {
        List<ScheduledCommand<?>> queued = new ArrayList<>();
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            for (ScheduledCommand<?> command : activeBatch) {
                if (command.markFailedIfQueued()) {
                    queued.add(command);
                }
            }
            activeBatch.clear();
            ScheduledCommand<?> command;
            while ((command = queue.pollFirst()) != null) {
                if (command.markFailedIfQueued()) {
                    queued.add(command);
                }
            }
        }
        HarnessException failure = sessionClosed();
        for (ScheduledCommand<?> command : queued) {
            command.completeQueuedFailure(failure);
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("scheduler may only be drained by its owning thread");
        }
    }

    private static HarnessException timeout(Deadline deadline) {
        return new HarnessException(
                ErrorCode.TIMEOUT,
                "Render-thread work exceeded its deadline in the queue",
                new ErrorEvidence(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        deadline.elapsed(),
                        OptionalLong.empty(),
                        Optional.empty(),
                        List.of(),
                        Map.of("timeout", deadline.timeout().toString()),
                        List.of()));
    }

    private static HarnessException queueFull(int capacity) {
        return new HarnessException(
                ErrorCode.LIMIT_EXCEEDED,
                "Render-thread command queue is full",
                ErrorEvidence.ofDetails(Map.of(
                        "dimension", "queued-requests",
                        "limit", Integer.toString(capacity))));
    }

    private static HarnessException sessionClosed() {
        return new HarnessException(
                ErrorCode.SESSION_CLOSED,
                "Render-thread scheduler is closed",
                ErrorEvidence.empty());
    }

    private enum Dispatch {
        SKIP,
        EXPIRED,
        RUN
    }

    private enum CommandState {
        QUEUED,
        CANCELLED,
        DISPATCHED,
        FINISHED
    }

    private final class ScheduledCommand<T> extends CompletableFuture<T> {
        private final Callable<T> callable;
        private final Deadline deadline;
        private CommandState state = CommandState.QUEUED;

        ScheduledCommand(Callable<T> callable, Deadline deadline) {
            this.callable = callable;
            this.deadline = deadline;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (this) {
                if (state != CommandState.QUEUED) {
                    return false;
                }
                state = CommandState.CANCELLED;
            }
            synchronized (lifecycle) {
                queue.remove(this);
                activeBatch.remove(this);
            }
            return super.cancel(false);
        }

        synchronized Dispatch tryDispatch() {
            if (state != CommandState.QUEUED) {
                return Dispatch.SKIP;
            }
            if (deadline.isExpired()) {
                state = CommandState.FINISHED;
                return Dispatch.EXPIRED;
            }
            state = CommandState.DISPATCHED;
            return Dispatch.RUN;
        }

        void completeExpiry() {
            super.completeExceptionally(timeout(deadline));
        }

        void failQueued(HarnessException failure) {
            if (markFailedIfQueued()) {
                completeQueuedFailure(failure);
            }
        }

        synchronized boolean markFailedIfQueued() {
            if (state != CommandState.QUEUED) {
                return false;
            }
            state = CommandState.FINISHED;
            return true;
        }

        void completeQueuedFailure(HarnessException failure) {
            super.completeExceptionally(failure);
        }

        void execute() {
            try {
                T value = callable.call();
                finish(value, null);
            } catch (HarnessException error) {
                finish(null, error);
            } catch (Exception error) {
                HarnessException failure = new HarnessException(
                        ErrorCode.RENDER_THREAD_FAILURE,
                        "Render-thread command failed",
                        ErrorEvidence.empty(),
                        error);
                finish(null, failure);
            }
        }

        private void finish(T value, HarnessException failure) {
            synchronized (this) {
                state = CommandState.FINISHED;
            }
            if (failure == null) {
                super.complete(value);
            } else {
                super.completeExceptionally(failure);
            }
        }
    }
}
