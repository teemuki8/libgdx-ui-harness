package dev.gdx.uiharness.core.wait;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.QueryResult;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Event-driven waits over fresh immutable semantic snapshots. */
public final class WaitEngine implements AutoCloseable {
    private final Supplier<SemanticSnapshot> snapshots;
    private final LocatorEngine locators;
    private final MonotonicClock clock;
    private final FrameSignal frames;
    private final Object lifecycle = new Object();
    private final Set<WaitState> activeWaits =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean open = true;

    /** Creates a wait engine in one monotonic time domain. */
    public WaitEngine(
            Supplier<SemanticSnapshot> snapshots,
            LocatorEngine locators,
            MonotonicClock clock,
            FrameSignal frames) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.locators = Objects.requireNonNull(locators, "locators");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    /**
     * Resolves the locator against a fresh snapshot initially and after changed frame signals.
     * The calling thread blocks on events, never polling or sleeping.
     */
    public WaitResult await(Locator locator, WaitCondition condition, Deadline deadline) {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.clock() != clock) {
            throw new IllegalArgumentException("deadline uses a different monotonic clock");
        }

        WaitState state = registerWait();
        FrameSignal.Subscription subscription = null;
        try {
            subscription = frames.subscribe(new FrameSignal.FrameListener() {
                @Override public void onFrame(FrameSignal.Frame frame) {
                    state.signal(frame);
                }

                @Override public void onClosed() {
                    state.close();
                }
            });
            long observedSequence = state.sequence();
            requireOpen(state);
            requireUnexpired(deadline, locator, null);

            SemanticSnapshot snapshot = Objects.requireNonNull(
                    snapshots.get(), "snapshot supplier returned null");
            QueryResult result = locators.query(snapshot, locator);
            if (condition.isSatisfied(snapshot, result)) {
                requireOpen(state);
                return new WaitResult(snapshot, result);
            }
            requireUnexpired(deadline, locator, snapshot);

            long lastRevision = snapshot.revision();
            long lastFrame = snapshot.frame();
            while (true) {
                SignaledFrame signaled = state.awaitNext(observedSequence, deadline);
                requireOpen(state);
                requireUnexpired(deadline, locator, snapshot);
                observedSequence = signaled.sequence();
                FrameSignal.Frame event = signaled.frame();
                if (event.revision() == lastRevision && event.frame() == lastFrame) {
                    continue;
                }

                snapshot = Objects.requireNonNull(
                        snapshots.get(), "snapshot supplier returned null");
                lastRevision = event.revision();
                lastFrame = event.frame();
                result = locators.query(snapshot, locator);
                if (condition.isSatisfied(snapshot, result)) {
                    requireOpen(state);
                    return new WaitResult(snapshot, result);
                }
                requireUnexpired(deadline, locator, snapshot);
            }
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            unregisterWait(state);
        }
    }

    /** Closes the engine and releases every blocked wait with a session-closed failure. */
    @Override public void close() {
        WaitState[] waits;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            waits = activeWaits.toArray(WaitState[]::new);
            activeWaits.clear();
        }
        for (WaitState wait : waits) {
            wait.close();
        }
    }

    private WaitState registerWait() {
        synchronized (lifecycle) {
            if (!open) {
                throw sessionClosed();
            }
            WaitState state = new WaitState();
            activeWaits.add(state);
            return state;
        }
    }

    private void unregisterWait(WaitState state) {
        synchronized (lifecycle) {
            activeWaits.remove(state);
        }
    }

    private void requireOpen(WaitState state) {
        synchronized (lifecycle) {
            if (!open || state.isClosed()) {
                throw sessionClosed();
            }
        }
    }

    private static void requireUnexpired(
            Deadline deadline, Locator locator, SemanticSnapshot lastSnapshot) {
        if (!deadline.isExpired()) {
            return;
        }
        OptionalLong revision = lastSnapshot == null
                ? OptionalLong.empty()
                : OptionalLong.of(lastSnapshot.revision());
        ErrorEvidence evidence = new ErrorEvidence(
                Optional.empty(),
                Optional.empty(),
                Optional.of(locator.toString()),
                deadline.elapsed(),
                revision,
                Optional.empty(),
                List.of(),
                Map.of("timeout", deadline.timeout().toString()));
        throw new HarnessException(
                ErrorCode.TIMEOUT,
                "Wait exceeded its monotonic deadline",
                evidence);
    }

    private static HarnessException sessionClosed() {
        return new HarnessException(
                ErrorCode.SESSION_CLOSED,
                "Wait engine is closed",
                ErrorEvidence.empty());
    }

    private static HarnessException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        return new HarnessException(
                ErrorCode.INTERNAL_ERROR,
                "Wait interrupted before a frame signal",
                ErrorEvidence.ofDetails(Map.of("interrupted", Boolean.TRUE.toString())),
                cause);
    }

    private static final class WaitState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private long sequence;
        private FrameSignal.Frame latest;
        private boolean open = true;

        void signal(FrameSignal.Frame frame) {
            lock.lock();
            try {
                if (!open) {
                    return;
                }
                latest = Objects.requireNonNull(frame, "frame");
                sequence++;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        long sequence() {
            lock.lock();
            try {
                return sequence;
            } finally {
                lock.unlock();
            }
        }

        SignaledFrame awaitNext(long observedSequence, Deadline deadline) {
            lock.lock();
            try {
                while (open && sequence == observedSequence && !deadline.isExpired()) {
                    long remainingNanos = deadline.remaining().toNanos();
                    if (remainingNanos == 0) {
                        break;
                    }
                    try {
                        changed.awaitNanos(remainingNanos);
                    } catch (InterruptedException error) {
                        throw interrupted(error);
                    }
                }
                if (!open) {
                    throw sessionClosed();
                }
                if (sequence == observedSequence) {
                    return null;
                }
                return new SignaledFrame(sequence, latest);
            } finally {
                lock.unlock();
            }
        }

        boolean isClosed() {
            lock.lock();
            try {
                return !open;
            } finally {
                lock.unlock();
            }
        }

        void close() {
            lock.lock();
            try {
                if (open) {
                    open = false;
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private record SignaledFrame(long sequence, FrameSignal.Frame frame) {}
}
