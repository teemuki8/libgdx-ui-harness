package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** Graphics-thread fence that dispatches work only after an explicitly completed rendered frame. */
public final class Lwjgl3FrameFence implements FrameSignal, AutoCloseable {
    private static final int DEFAULT_CAPACITY = 64;

    private final Thread ownerThread = Thread.currentThread();
    private final int capacity;
    private final Object lifecycle = new Object();
    private final ArrayDeque<Command<?>> queued = new ArrayDeque<>();
    private final CopyOnWriteArrayList<FrameListener> listeners =
            new CopyOnWriteArrayList<>();
    private boolean open = true;

    /** Creates a fence with the default bounded pending-work capacity. */
    public Lwjgl3FrameFence() {
        this(DEFAULT_CAPACITY);
    }

    /** Creates a fence owned by the current graphics thread. */
    public Lwjgl3FrameFence(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
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
                queued.addLast(command);
            }
        }
        if (rejection != null) {
            command.completeExceptionally(rejection);
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
        synchronized (lifecycle) {
            if (!open) {
                throw new IllegalStateException("frame fence is closed");
            }
            batch = new ArrayList<>(queued);
            queued.clear();
        }
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
        synchronized (lifecycle) {
            if (!open) {
                listener.onClosed();
                return () -> {};
            }
            listeners.add(listener);
        }
        return () -> listeners.remove(listener);
    }

    /** Fails queued work and closes all frame subscriptions without touching the window. */
    @Override public void close() {
        List<Command<?>> pending;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            pending = new ArrayList<>(queued);
            queued.clear();
        }
        HarnessException failure = closedFailure();
        for (Command<?> command : pending) {
            command.completeExceptionally(failure);
        }
        for (FrameListener listener : listeners) {
            listener.onClosed();
        }
        listeners.clear();
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
                ErrorEvidence.ofDetails(Map.of(
                        "elapsed", deadline.elapsed().toString(),
                        "timeout", deadline.timeout().toString())));
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

    private static final class Command<T> extends CompletableFuture<T> {
        private final FrameTask<T> task;
        private final Deadline deadline;

        Command(FrameTask<T> task, Deadline deadline) {
            this.task = task;
            this.deadline = deadline;
        }

        void execute(Frame frame) {
            if (isDone()) {
                return;
            }
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
}
