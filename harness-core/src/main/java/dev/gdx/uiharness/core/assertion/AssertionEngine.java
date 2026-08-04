package dev.gdx.uiharness.core.assertion;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Event-driven evaluation of assertions over fresh completed semantic frames. */
public final class AssertionEngine {
    static final int MAX_PENDING_FRAMES = 64;
    private final AssertionEvaluator evaluator;
    private final LocatorEngine locators;

    public AssertionEngine() {
        this(new StrictResolution());
    }

    public AssertionEngine(LocatorEngine locators) {
        this.locators = Objects.requireNonNull(locators, "locators");
        evaluator = new AssertionEvaluator(locators);
    }


    /** Evaluates with a cancellable deadline wake-up independent of frame delivery. */
    public CompletionStage<AssertionResult> assertThat(
            AssertionSnapshotSource snapshots,
            AssertionRequest request,
            FrameSignal frames,
            MonotonicClock clock,
            DeadlineWakeup deadlineWakeup) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(deadlineWakeup, "deadlineWakeup");
        if (request.deadline().clock() != clock) {
            throw new IllegalArgumentException("deadline uses a different monotonic clock");
        }

        State state = new State(snapshots, request, deadlineWakeup);
        state.result.whenComplete((ignored, failure) -> state.closeResources());
        try {
            FrameSignal.Subscription subscription = frames.subscribe(state);
            state.registered(subscription);
            state.scheduleDeadline();
        } catch (Throwable failure) {
            state.rejectRegistration(failure);
        }
        return state.result;
    }

    private final class State implements FrameSignal.FrameListener {
        private final AssertionSnapshotSource snapshots;
        private final AssertionRequest request;
        private final CompletableFuture<AssertionResult> result = new CompletableFuture<>();
        private final DeadlineWakeup deadlineWakeup;
        private final ArrayDeque<FrameSignal.Frame> pending = new ArrayDeque<>();
        private FrameSignal.Subscription subscription;
        private DeadlineWakeup.Registration deadlineRegistration;
        private boolean deadlineScheduling;
        private boolean deadlineSignalPending;
        private boolean registered = true;
        private boolean draining;
        private boolean registrationRejected;
        private boolean initialEvaluated;
        private boolean closed;
        private AssertionResult lastFailure;
        private Throwable lastResolutionFailure;
        private FrameSignal.Frame lastFrame;
        private boolean hasStableSnapshot;
        private long lastStableRevision;
        private long lastStableFrame;
        private Map<UiAssertion.StableProperty, Object> lastProperties;
        private int stableCount;

        State(
                AssertionSnapshotSource snapshots,
                AssertionRequest request,
                DeadlineWakeup deadlineWakeup) {
            this.snapshots = snapshots;
            this.request = request;
            this.deadlineWakeup = deadlineWakeup;
        }

        void registered(FrameSignal.Subscription registeredSubscription) {
            Objects.requireNonNull(registeredSubscription, "frame subscription");
            synchronized (this) {
                if (registrationRejected) {
                    registeredSubscription.close();
                    return;
                }
                subscription = registeredSubscription;
                registered = true;
            }
            ensureInitial();
            drain();
            closeResources();
        }

        void rejectRegistration(Throwable failure) {
            synchronized (this) {
                registrationRejected = true;
            }
            result.completeExceptionally(failure);
        }

        void scheduleDeadline() {
            synchronized (this) {
                if (result.isDone() || deadlineScheduling) {
                    return;
                }
                deadlineScheduling = true;
            }
            scheduleDeadlineLoop(false);
        }

        private void onDeadlineWakeup() {
            synchronized (this) {
                if (result.isDone()) {
                    return;
                }
                deadlineSignalPending = true;
                if (deadlineScheduling) {
                    return;
                }
                deadlineScheduling = true;
            }
            scheduleDeadlineLoop(true);
        }

        private void scheduleDeadlineLoop(boolean consumeSignal) {
            while (true) {
                if (consumeSignal) {
                    DeadlineWakeup.Registration expiredRegistration;
                    synchronized (this) {
                        deadlineSignalPending = false;
                        expiredRegistration = deadlineRegistration;
                        deadlineRegistration = null;
                    }
                    if (expiredRegistration != null) {
                        expiredRegistration.cancel();
                    }
                    if (request.deadline().isExpired()) {
                        synchronized (this) {
                            deadlineScheduling = false;
                        }
                        completeAtDeadline();
                        return;
                    }
                }

                DeadlineWakeup.Registration registration;
                try {
                    registration = Objects.requireNonNull(
                            deadlineWakeup.schedule(
                                    request.deadline().remaining(), this::onDeadlineWakeup),
                            "deadline wake-up registration");
                } catch (Throwable failure) {
                    synchronized (this) {
                        deadlineScheduling = false;
                    }
                    result.completeExceptionally(failure);
                    return;
                }

                boolean cancel;
                synchronized (this) {
                    cancel = result.isDone();
                    if (!cancel) {
                        deadlineRegistration = registration;
                    }
                    consumeSignal = deadlineSignalPending;
                    if (!consumeSignal) {
                        deadlineScheduling = false;
                    }
                }
                if (cancel) {
                    registration.cancel();
                    return;
                }
                if (!consumeSignal) {
                    return;
                }
            }
        }

        @Override public void onFrame(FrameSignal.Frame frame) {
            Objects.requireNonNull(frame, "frame");
            boolean start = false;
            HarnessException overflow = null;
            synchronized (this) {
                if (result.isDone()) {
                    return;
                }
                if (pending.size() >= MAX_PENDING_FRAMES) {
                    overflow = new HarnessException(
                            ErrorCode.LIMIT_EXCEEDED,
                            "Assertion pending completed-frame limit exceeded; "
                                    + "evaluation could not keep pace with rendering",
                            ErrorEvidence.ofDetails(Map.of(
                                    "dimension", "pendingFrames",
                                    "actual", Integer.toString(pending.size() + 1),
                                    "limit", Integer.toString(MAX_PENDING_FRAMES))));
                } else {
                    pending.addLast(frame);
                    start = registered && !draining;
                    if (start) {
                        draining = true;
                    }
                }
            }
            if (overflow != null) {
                result.completeExceptionally(overflow);
            } else if (start) {
                drainOwned();
            }
        }

        @Override public void onClosed() {
            boolean start;
            synchronized (this) {
                if (result.isDone()) {
                    return;
                }
                closed = true;
                start = registered && !draining;
                if (start) {
                    draining = true;
                }
            }
            if (start) {
                drainOwned();
            }
        }

        private void ensureInitial() {
            synchronized (this) {
                if (initialEvaluated) {
                    return;
                }
                initialEvaluated = true;
            }
            evaluateInitial();
        }

        private void evaluateInitial() {
            if (result.isDone()) {
                return;
            }
            if (request.deadline().isExpired()) {
                completeAtDeadline();
                return;
            }
            try {
                SemanticSnapshot snapshot = currentSnapshot();
                if (request.assertion() instanceof UiAssertion.StableForFrames stable) {
                    SemanticNode node = locators.resolveStrict(snapshot, request.locator());
                    lastFailure = new AssertionResult(AssertionResult.Status.FAILED,
                            new AssertionEvidence(node.id(), stable.frames() + " completed frames",
                                    "0/" + stable.frames(), snapshot.revision(), snapshot.frame()),
                            request.deadline().elapsed().toNanos());
                } else {
                    accept(evaluator.evaluate(snapshot, request));
                }
                lastResolutionFailure = null;
            } catch (Throwable failure) {
                lastResolutionFailure = failure;
            }
            if (request.deadline().isExpired()) {
                completeAtDeadline();
            }
        }

        private void drain() {
            synchronized (this) {
                if (draining || result.isDone()) {
                    return;
                }
                draining = true;
            }
            drainOwned();
        }

        private void drainOwned() {
            ensureInitial();
            while (true) {
                FrameSignal.Frame frame;
                synchronized (this) {
                    if (result.isDone()) {
                        pending.clear();
                        draining = false;
                        break;
                    }
                    frame = pending.pollFirst();
                    if (frame == null) {
                        if (closed) {
                            result.completeExceptionally(new IllegalStateException(
                                    "frame signal closed before assertion completed"));
                        }
                        draining = false;
                        break;
                    }
                }
                if (lastFrame != null && lastFrame.equals(frame)) {
                    continue;
                }
                lastFrame = frame;
                if (request.deadline().isExpired()) {
                    completeAtDeadline();
                    continue;
                }
                evaluateFrame(frame);
            }
            closeResources();
        }

        private void evaluateFrame(FrameSignal.Frame frame) {
            SemanticSnapshot snapshot;
            try {
                snapshot = snapshotFor(frame);
            } catch (FrameSnapshotMismatch mismatch) {
                result.completeExceptionally(mismatch);
                return;
            } catch (Throwable failure) {
                lastResolutionFailure = failure;
                stableCount = 0;
                lastProperties = null;
                return;
            }
            try {
                if (request.assertion() instanceof UiAssertion.StableForFrames stable) {
                    evaluateStable(snapshot, stable);
                } else {
                    accept(evaluator.evaluate(snapshot, request));
                }
                lastResolutionFailure = null;
            } catch (Throwable failure) {
                lastResolutionFailure = failure;
                stableCount = 0;
                lastProperties = null;
            }
            if (request.deadline().isExpired()) {
                completeAtDeadline();
            }
        }

        private void evaluateStable(SemanticSnapshot snapshot, UiAssertion.StableForFrames stable) {
            if (hasStableSnapshot
                    && lastStableRevision == snapshot.revision()
                    && lastStableFrame == snapshot.frame()) {
                return;
            }
            hasStableSnapshot = true;
            lastStableRevision = snapshot.revision();
            lastStableFrame = snapshot.frame();
            SemanticNode node = locators.resolveStrict(snapshot, request.locator());
            Map<UiAssertion.StableProperty, Object> properties = selectedProperties(node, stable);
            stableCount = properties.equals(lastProperties) ? stableCount + 1 : 1;
            lastProperties = properties;
            AssertionEvidence evidence = new AssertionEvidence(node.id(),
                    stable.frames() + " completed frames", stableCount + "/" + stable.frames(),
                    snapshot.revision(), snapshot.frame());
            lastFailure = new AssertionResult(AssertionResult.Status.FAILED, evidence,
                    request.deadline().elapsed().toNanos());
            if (request.deadline().isExpired()) {
                completeAtDeadline();
            } else if (stableCount >= stable.frames()) {
                result.complete(new AssertionResult(AssertionResult.Status.PASSED, evidence,
                        request.deadline().elapsed().toNanos()));
            }
        }

        private Map<UiAssertion.StableProperty, Object> selectedProperties(
                SemanticNode node, UiAssertion.StableForFrames stable) {
            EnumMap<UiAssertion.StableProperty, Object> values =
                    new EnumMap<>(UiAssertion.StableProperty.class);
            for (UiAssertion.StableProperty property : stable.properties()) {
                values.put(property, switch (property) {
                    case BOUNDS -> node.screenBounds();
                    case TEXT -> node.text();
                    case ACCESSIBLE_NAME -> node.accessibleName();
                    case VISIBLE -> node.state().visible();
                    case ENABLED -> node.state().enabled();
                    case CHECKED -> node.state().checked();
                    case FOCUSED -> node.state().focused();
                });
            }
            return Map.copyOf(values);
        }

        private SemanticSnapshot currentSnapshot() {
            return Objects.requireNonNull(
                    snapshots.currentSnapshot(), "current snapshot source returned null");
        }

        private SemanticSnapshot snapshotFor(FrameSignal.Frame frame) {
            SemanticSnapshot snapshot = Objects.requireNonNull(
                    snapshots.snapshotFor(frame), "frame snapshot source returned null");
            if (snapshot.revision() != frame.revision() || snapshot.frame() != frame.frame()) {
                throw new FrameSnapshotMismatch(
                        "Snapshot identity revision=" + snapshot.revision()
                                + ", frame=" + snapshot.frame()
                                + " does not match delivered frame revision=" + frame.revision()
                                + ", frame=" + frame.frame());
            }
            return snapshot;
        }

        private void accept(AssertionResult attempt) {
            lastResolutionFailure = null;
            if (attempt.status() == AssertionResult.Status.FAILED) {
                lastFailure = attempt;
            }
            if (request.deadline().isExpired()) {
                completeAtDeadline();
            } else if (attempt.status() == AssertionResult.Status.PASSED) {
                result.complete(attempt);
            }
        }

        private void completeAtDeadline() {
            if (lastResolutionFailure != null) {
                result.completeExceptionally(lastResolutionFailure);
            } else if (lastFailure != null) {
                result.complete(new AssertionResult(AssertionResult.Status.FAILED,
                        lastFailure.evidence(), request.deadline().elapsed().toNanos()));
            } else {
                result.completeExceptionally(new IllegalStateException(
                        "assertion deadline expired before an evaluation"));
            }
        }

        private void closeResources() {
            FrameSignal.Subscription framesToClose;
            DeadlineWakeup.Registration deadlineToCancel;
            synchronized (this) {
                if (!result.isDone()) {
                    return;
                }
                framesToClose = subscription;
                subscription = null;
                deadlineToCancel = deadlineRegistration;
                deadlineRegistration = null;
            }
            if (framesToClose != null) {
                framesToClose.close();
            }
            if (deadlineToCancel != null) {
                deadlineToCancel.cancel();
            }
        }
        private final class FrameSnapshotMismatch extends IllegalStateException {
            private static final long serialVersionUID = 1L;

            FrameSnapshotMismatch(String message) {
                super(message);
            }
        }
    }
}
