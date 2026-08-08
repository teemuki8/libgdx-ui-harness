package dev.gdx.uiharness.mcp;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Bounds MCP request admission before protocol dispatch.
 *
 * <p>One monitor guards the global in-flight counter, each session's in-flight counter, and each
 * session's bounded FIFO mutation lane. Admission is decided synchronously: excess requests are
 * rejected immediately with {@link LimitExceededException} instead of blocking a virtual thread
 * waiting for a permit. Per-session mutations start strictly in submission order and never
 * overlap; read-only requests may overlap within the same bounds. Permits are released in a
 * single whenComplete path after the supplied work (including result translation and output
 * accounting) reaches a terminal state, and empty session lanes are removed eagerly.
 */
final class RequestAdmission implements AutoCloseable {
    static final int DEFAULT_GLOBAL_LIMIT = 8;
    static final int DEFAULT_PER_SESSION_LIMIT = 4;
    static final int DEFAULT_MAX_QUEUED_MUTATIONS = 16;

    private final int globalLimit;
    private final int perSessionLimit;
    private final int maxQueuedMutations;
    private final Object monitor = new Object();
    private final Map<SessionKey, SessionLane> lanes = new HashMap<>();
    private int globalInFlight;
    private boolean closed;

    /**
     * Admission scope key. A real client session identifier is wrapped so it can never collide
     * with the distinct sessionless scope, even when a client literally names its session
     * "catalog" or any other historical sentinel.
     */
    record SessionKey(String sessionId) {
        /** Distinct scope for requests that carry no session identifier. */
        static final SessionKey SESSIONLESS = new SessionKey(null);

        /** Wraps one client session identifier. */
        static SessionKey session(String sessionId) {
            return new SessionKey(Objects.requireNonNull(sessionId, "sessionId"));
        }

        /** Returns the scope for requests without a session identifier. */
        static SessionKey sessionless() {
            return SESSIONLESS;
        }
    }

    /** Returns the admission used by the stdio server. */
    static RequestAdmission serverDefaults() {
        return new RequestAdmission(
                DEFAULT_GLOBAL_LIMIT, DEFAULT_PER_SESSION_LIMIT, DEFAULT_MAX_QUEUED_MUTATIONS);
    }

    RequestAdmission(int globalLimit, int perSessionLimit, int maxQueuedMutations) {
        if (globalLimit <= 0) {
            throw new IllegalArgumentException("globalLimit must be positive");
        }
        if (perSessionLimit <= 0) {
            throw new IllegalArgumentException("perSessionLimit must be positive");
        }
        if (maxQueuedMutations < 0) {
            throw new IllegalArgumentException("maxQueuedMutations must not be negative");
        }
        this.globalLimit = globalLimit;
        this.perSessionLimit = perSessionLimit;
        this.maxQueuedMutations = maxQueuedMutations;
    }

    /**
     * Admits one request or rejects it immediately. A rejected request never invokes {@code work}
     * and completes exceptionally with {@link LimitExceededException}. Admitted read-only requests
     * start immediately and may overlap; admitted mutations start in submission order per session
     * and never overlap. The permit is released only after the stage returned by {@code work}
     * (which includes result translation and output accounting) reaches a terminal state.
     */
    <T> CompletionStage<T> submit(
            SessionKey sessionKey,
            HarnessToolCatalog.AccessMode mode,
            Supplier<CompletionStage<T>> work) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        QueuedWork item = new QueuedWork(result, work, mode);
        SessionLane lane;
        boolean startNow = false;
        synchronized (monitor) {
            if (closed) {
                result.completeExceptionally(new LimitExceededException("Admission closed"));
                return result;
            }
            if (globalInFlight >= globalLimit) {
                result.completeExceptionally(new LimitExceededException(
                        "Global admission limit exceeded (limit=" + globalLimit + ")"));
                return result;
            }
            lane = lanes.computeIfAbsent(sessionKey, ignored -> new SessionLane());
            if (lane.inFlight >= perSessionLimit) {
                result.completeExceptionally(new LimitExceededException(
                        "Session admission limit exceeded (limit=" + perSessionLimit + ")"));
                return result;
            }
            if (mode == HarnessToolCatalog.AccessMode.MUTATING
                    && (lane.runningMutation != null || !lane.queue.isEmpty())) {
                if (lane.queue.size() >= maxQueuedMutations) {
                    result.completeExceptionally(new LimitExceededException(
                            "Mutation queue limit exceeded (limit=" + maxQueuedMutations + ")"));
                    return result;
                }
                lane.queue.add(item);
                globalInFlight++;
                lane.inFlight++;
                // A queued mutation cancelled before it starts releases its permit and queue
                // slot immediately instead of leaking until the lane drains.
                result.whenComplete((value, failure) -> cancelQueued(sessionKey, lane, item));
                return result;
            }
            globalInFlight++;
            lane.inFlight++;
            if (mode == HarnessToolCatalog.AccessMode.MUTATING) {
                lane.runningMutation = result;
            }
            startNow = true;
        }
        if (startNow) {
            run(sessionKey, lane, item);
        }
        return result;
    }

    /** Releases one queued mutation whose result reached a terminal state before it started. */
    private void cancelQueued(SessionKey sessionKey, SessionLane lane, QueuedWork item) {
        synchronized (monitor) {
            if (lane.queue.remove(item)) {
                globalInFlight--;
                lane.inFlight--;
                if (lane.inFlight == 0
                        && lane.queue.isEmpty()
                        && lane.runningMutation == null) {
                    lanes.remove(sessionKey);
                }
            }
        }
    }

    /** Starts one admitted request. The supplier is invoked outside the monitor. */
    private void run(SessionKey sessionKey, SessionLane lane, QueuedWork item) {
        CompletableFuture<?> result = item.result;
        CompletionStage<?> stage;
        try {
            stage = Objects.requireNonNull(item.work.get(), "work stage");
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            finish(sessionKey, lane, item.mode == HarnessToolCatalog.AccessMode.MUTATING);
            return;
        }
        CompletableFuture<?> stageFuture = stage.toCompletableFuture();
        // Propagate cancellation of the admitted future to the in-flight work so the release
        // path observes the terminal state and never leaks a permit.
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                stageFuture.cancel(false);
            }
        });
        stageFuture.whenComplete((value, failure) -> {
            if (failure == null) {
                unchecked(result).complete(value);
            } else {
                result.completeExceptionally(failure);
            }
            finish(sessionKey, lane, item.mode == HarnessToolCatalog.AccessMode.MUTATING);
        });
    }

    /**
     * Releases the permits of one admitted request and starts the next live queued mutation for
     * its session. Runs exactly once per admitted request from the terminal-state path.
     */
    private void finish(SessionKey sessionKey, SessionLane lane, boolean wasRunningMutation) {
        QueuedWork next = null;
        synchronized (monitor) {
            globalInFlight--;
            lane.inFlight--;
            if (wasRunningMutation) {
                lane.runningMutation = null;
                while (!lane.queue.isEmpty()) {
                    QueuedWork candidate = lane.queue.poll();
                    if (candidate.result.isDone()) {
                        // Cancelled while queued: release its permits without starting it.
                        globalInFlight--;
                        lane.inFlight--;
                        continue;
                    }
                    next = candidate;
                    lane.runningMutation = next.result;
                    break;
                }
            }
            if (lane.inFlight == 0
                    && lane.queue.isEmpty()
                    && lane.runningMutation == null) {
                lanes.remove(sessionKey);
            }
        }
        if (next != null) {
            run(sessionKey, lane, next);
        }
    }

    /**
     * Rejects queued mutations and prevents new admission without interrupting already executing
     * render work; requests admitted before close complete or cancel exactly once.
     */
    @Override public void close() {
        java.util.ArrayList<QueuedWork> rejected = new java.util.ArrayList<>();
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            Iterator<SessionLane> iterator = lanes.values().iterator();
            while (iterator.hasNext()) {
                SessionLane lane = iterator.next();
                while (!lane.queue.isEmpty()) {
                    QueuedWork work = lane.queue.poll();
                    globalInFlight--;
                    lane.inFlight--;
                    rejected.add(work);
                }
                if (lane.inFlight == 0) {
                    iterator.remove();
                }
            }
        }
        LimitExceededException failure = new LimitExceededException("Admission closed");
        for (QueuedWork work : rejected) {
            work.result.completeExceptionally(failure);
        }
    }

    /** Thrown once per rejected or closed request; the protocol service is never invoked. */
    static final class LimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LimitExceededException(String message) {
            super(message);
        }
    }

    /** One admitted request: its public future, its work, and its access mode. */
    private static final class QueuedWork {
        final CompletableFuture<?> result;
        final Supplier<? extends CompletionStage<?>> work;
        final HarnessToolCatalog.AccessMode mode;

        QueuedWork(CompletableFuture<?> result, Supplier<? extends CompletionStage<?>> work,
                HarnessToolCatalog.AccessMode mode) {
            this.result = result;
            this.work = work;
            this.mode = mode;
        }
    }

    /** Per-session admission state: in-flight count plus the bounded mutation lane. */
    private static final class SessionLane {
        int inFlight;
        CompletableFuture<?> runningMutation;
        final ArrayDeque<QueuedWork> queue = new ArrayDeque<>();
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<T> unchecked(CompletableFuture<?> future) {
        return (CompletableFuture<T>) future;
    }
}
