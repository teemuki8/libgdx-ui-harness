package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.wait.FrameSignal;
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

/** Graphics-thread fence that dispatches work only after an explicitly completed rendered frame. */
public final class Lwjgl3FrameFence implements FrameSignal, AutoCloseable {
    private static final int DEFAULT_CAPACITY = 64;

    private final Thread ownerThread = Thread.currentThread();
    private final int capacity;
    private final DeadlineScheduler deadlines;
    private final Object lifecycle = new Object();
    private final ArrayDeque<Command<?>> queued = new ArrayDeque<>();
    private final CopyOnWriteArrayList<FrameListener> listeners =
            new CopyOnWriteArrayList<>();
    private boolean open = true;

    /** Creates a fence owned by the current graphics thread with the default bounded capacity. */
    public Lwjgl3FrameFence(DeadlineScheduler deadlines) {
        this(deadlines, DEFAULT_CAPACITY);
    }

    /** Creates a fence owned by the current graphics thread. */
    public Lwjgl3FrameFence(DeadlineScheduler deadlines, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
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
        synchronized (lifecycle) {
            if (!open) {
                throw new IllegalStateException("frame fence is closed");
            }
            batch = new ArrayList<>(queued);
            for (Command<?> command : batch) {
                command.markClaimed();
            }
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

    /** Fails queued work and closes all frame subscriptions without touching the window. */
    @Override public void close() {
        List<Command<?>> pending;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            pending = new ArrayList<>(queued);
            for (Command<?> command : pending) {
                command.markClaimed();
            }
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
         */
        void armDeadline() {
            DeadlineScheduler.Cancellation scheduled =
                    deadlines.schedule(deadline.remaining(), this::deadlineReached);
            synchronized (lifecycle) {
                if (state != CommandState.QUEUED) {
                    scheduled.cancel();
                } else {
                    deadlineCancellation = scheduled;
                }
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

        void markClaimed() {
            if (state != CommandState.QUEUED) {
                throw new IllegalStateException("only queued frame work can be claimed");
            }
            state = CommandState.CLAIMED;
            if (deadlineCancellation != null) {
                deadlineCancellation.cancel();
                deadlineCancellation = null;
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lifecycle) {
                if (state != CommandState.QUEUED || !queued.remove(this)) {
                    return false;
                }
                state = CommandState.TERMINAL;
                if (deadlineCancellation != null) {
                    deadlineCancellation.cancel();
                    deadlineCancellation = null;
                }
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
