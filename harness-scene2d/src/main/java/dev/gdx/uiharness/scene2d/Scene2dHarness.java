package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.SnapshotArray;
import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Actionability;
import dev.gdx.uiharness.core.action.ActionabilityCheck;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.QueryResult;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Scene2D action pipeline confined to one render-thread scheduler. */
public final class Scene2dHarness implements Harness, AutoCloseable {
    private final Stage stage;
    private final Scene2dSession session;
    private final RenderThreadScheduler scheduler;
    private final FrameSignal frames;
    private final LongSupplier revisions;
    private final LongSupplier frameNumbers;
    private final DeadlineScheduler deadlines;
    private final LocatorEngine locators = new StrictResolution();
    private final Scene2dActionability actionability = new Scene2dActionability();
    private final Scene2dInputDispatcher input;
    private final Object lifecycle = new Object();
    private final Set<ActionRequest> requests =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final OwnedDeadlineScheduler ownedScheduler;
    private boolean open = true;
    private Thread closingThread;
    private volatile Throwable closeFailure;

    /**
     * Attaches orchestration to explicit render-loop, frame, revision, and input dependencies.
     * The harness owns a daemon deadline scheduler so no-frame action deadlines still fire.
     */
    public Scene2dHarness(
            Stage stage,
            InputProcessor input,
            Scene2dSession session,
            RenderThreadScheduler scheduler,
            FrameSignal frames,
            LongSupplier revisions,
            LongSupplier frameNumbers) {
        this(stage, input, session, scheduler, frames, revisions, frameNumbers,
                new OwnedDeadlineScheduler());
    }

    private Scene2dHarness(
            Stage stage,
            InputProcessor input,
            Scene2dSession session,
            RenderThreadScheduler scheduler,
            FrameSignal frames,
            LongSupplier revisions,
            LongSupplier frameNumbers,
            OwnedDeadlineScheduler owned) {
        this(stage, input, session, scheduler, frames, revisions, frameNumbers, owned, owned);
    }

    /** Attaches orchestration to explicit render-loop, frame, revision, and input dependencies. */
    public Scene2dHarness(
            Stage stage,
            InputProcessor input,
            Scene2dSession session,
            RenderThreadScheduler scheduler,
            FrameSignal frames,
            LongSupplier revisions,
            LongSupplier frameNumbers,
            DeadlineScheduler deadlines) {
        this(stage, input, session, scheduler, frames, revisions, frameNumbers, deadlines, null);
    }

    /**
     * Ownership-aware constructor: {@code ownedScheduler} is non-null only when this harness
     * created the scheduler and must shut it down on close.
     */
    private Scene2dHarness(
            Stage stage,
            InputProcessor input,
            Scene2dSession session,
            RenderThreadScheduler scheduler,
            FrameSignal frames,
            LongSupplier revisions,
            LongSupplier frameNumbers,
            DeadlineScheduler deadlines,
            OwnedDeadlineScheduler ownedScheduler) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.session = Objects.requireNonNull(session, "session");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.frameNumbers = Objects.requireNonNull(frameNumbers, "frameNumbers");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.ownedScheduler = ownedScheduler;
        this.input = new Scene2dInputDispatcher(stage, Objects.requireNonNull(input, "input"));
    }

    @Override public CompletionStage<ActionResult> perform(
            Locator locator, Action action, Deadline deadline) {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(deadline, "deadline");
        ActionRequest request = new ActionRequest(locator, action, deadline);
        synchronized (lifecycle) {
            if (!open) {
                return CompletableFuture.failedFuture(sessionClosed());
            }
            requests.add(request);
        }
        try {
            request.attach(frames.subscribe(request));
            request.schedule();
        } catch (RuntimeException error) {
            request.fail(error);
        }
        return request;
    }

    @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        synchronized (lifecycle) {
            if (!open) {
                return CompletableFuture.failedFuture(sessionClosed());
            }
        }
        return scheduler.submit(this::freshSnapshot, deadline);
    }

    /**
     * Fails pending actions without closing application-owned Stage, session, or scheduler.
     *
     * <p>The first caller performs the cleanup; concurrent closers wait for it to finish and a
     * reentrant close from the closing thread's own callbacks returns immediately. Every pending
     * action is failed, every armed deadline signal is cancelled, and a legacy harness's owned
     * scheduler is fully shut down even when an individual cleanup step fails. All callbacks and
     * cancellations run outside the lifecycle monitor.
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
        ActionRequest[] pending;
        synchronized (lifecycle) {
            pending = requests.toArray(ActionRequest[]::new);
            requests.clear();
        }
        Throwable failure = null;
        try {
            HarnessException closed = sessionClosed();
            for (ActionRequest request : pending) {
                try {
                    request.failBeforeDispatch(closed);
                    request.cancelDeadline();
                } catch (Throwable throwable) {
                    failure = aggregate(failure, throwable);
                }
            }
            // The owned scheduler is shut down unconditionally so a legacy harness never leaks
            // its worker, even when an earlier cleanup step failed.
            if (ownedScheduler != null) {
                try {
                    ownedScheduler.shutdownNowAndAwait();
                } catch (Throwable throwable) {
                    failure = aggregate(failure, throwable);
                }
            }
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

    private SemanticSnapshot freshSnapshot() {
        return session.snapshot(revisions.getAsLong(), frameNumbers.getAsLong());
    }

    private void finished(ActionRequest request) {
        synchronized (lifecycle) {
            requests.remove(request);
        }
    }

    private final class ActionRequest extends CompletableFuture<ActionResult>
            implements FrameSignal.FrameListener {
        private final Locator locator;
        private final Action action;
        private final Deadline deadline;
        private FrameSignal.Subscription subscription;
        private DeadlineScheduler.Cancellation deadlineCancellation;
        private SemanticSnapshot lastSnapshot;
        private ActionabilityCheck lastCheck;
        private Bounds lastBounds;
        private long lastActorToken;
        private long lastStableFrame = Long.MIN_VALUE;
        private int stableSamples;
        private boolean scheduled;
        private RequestPhase phase = RequestPhase.PENDING;
        private long beforeRevision;
        private long beforeFrame;

        ActionRequest(Locator locator, Action action, Deadline deadline) {
            this.locator = locator;
            this.action = action;
            this.deadline = deadline;
        }

        void attach(FrameSignal.Subscription newSubscription) {
            boolean closeSubscription;
            synchronized (this) {
                closeSubscription = phase == RequestPhase.TERMINAL;
                if (!closeSubscription) {
                    subscription = newSubscription;
                }
            }
            if (closeSubscription) {
                newSubscription.close();
            }
        }

        @Override public void onFrame(FrameSignal.Frame frame) {
            HarnessException failure = null;
            boolean shouldSchedule = false;
            synchronized (this) {
                if (phase == RequestPhase.TERMINAL) {
                    return;
                }
                if (deadline.isExpired()) {
                    failure = timeoutLocked();
                } else if (phase == RequestPhase.PENDING
                        || (phase == RequestPhase.AWAITING_FRAME
                                && frame.frame() > beforeFrame)) {
                    shouldSchedule = true;
                }
            }
            if (failure != null) {
                fail(failure);
            } else if (shouldSchedule) {
                schedule();
            }
        }

        @Override public void onClosed() {
            fail(sessionClosed());
        }

        void schedule() {
            HarnessException failure = null;
            synchronized (this) {
                if (phase == RequestPhase.TERMINAL || scheduled) {
                    return;
                }
                if (deadline.isExpired()) {
                    failure = timeoutLocked();
                } else {
                    scheduled = true;
                }
            }
            if (failure != null) {
                fail(failure);
                return;
            }
            CompletionStage<Void> submitted = scheduler.submit(() -> {
                runOnRenderThread();
                return null;
            }, deadline);
            submitted.whenComplete((ignored, submitFailure) -> {
                synchronized (ActionRequest.this) {
                    scheduled = false;
                }
                if (submitFailure != null) {
                    Throwable cause = unwrap(submitFailure);
                    if (cause instanceof HarnessException harnessFailure
                            && harnessFailure.code() == ErrorCode.TIMEOUT) {
                        fail(timeout());
                    } else {
                        fail(cause);
                    }
                }
            });
        }

        private void runOnRenderThread() {
            RequestPhase current;
            HarnessException failure = null;
            synchronized (this) {
                current = phase;
                if (current == RequestPhase.TERMINAL
                        || current == RequestPhase.DISPATCHING) {
                    return;
                }
                if (deadline.isExpired()) {
                    failure = timeoutLocked();
                }
            }
            if (failure != null) {
                fail(failure);
            } else if (current == RequestPhase.AWAITING_FRAME) {
                completeAfterFrame();
            } else {
                attemptDispatch();
            }
        }

        private void attemptDispatch() {
            SemanticSnapshot snapshot = freshSnapshot();
            recordSnapshot(snapshot);
            SemanticNode node;
            try {
                node = locators.resolveStrict(snapshot, locator);
            } catch (HarnessException failure) {
                if (failure.code() == ErrorCode.NOT_FOUND) {
                    recordCheck(new Actionability(
                            false, false, false, false, false, false, false)
                            .check(action.force()));
                    resetStability();
                    return;
                }
                fail(failure);
                return;
            }

            Actor actor = actorFor(node.id());
            if (actor == null) {
                recordCheck(new Actionability(
                        false, false, false, false, false, false, false)
                        .check(action.force()));
                resetStability();
                return;
            }
            long actorToken = session.actorToken(actor);
            Scene2dActionability.Observation observation =
                    actionability.inspect(stage, actor, node, false, actorToken);
            boolean stable = observeStability(snapshot.frame(), observation);
            Actionability base = observation.actionability();
            Actionability current = new Actionability(
                    base.attached(),
                    base.visible(),
                    base.enabled(),
                    base.touchable(),
                    stable,
                    base.viewportIntersecting(),
                    base.hitTarget());
            ActionabilityCheck currentCheck = current.check(action.force());
            recordCheck(currentCheck);
            if (!currentCheck.actionable()) {
                return;
            }

            SemanticSnapshot fresh = freshSnapshot();
            SemanticNode freshNode;
            try {
                freshNode = locators.resolveStrict(fresh, locator);
            } catch (HarnessException failure) {
                if (failure.code() == ErrorCode.NOT_FOUND) {
                    resetStability();
                    return;
                }
                fail(failure);
                return;
            }
            Actor freshActor = actorFor(freshNode.id());
            if (freshActor == null) {
                resetStability();
                return;
            }
            long freshActorToken = session.actorToken(freshActor);
            Scene2dActionability.Observation freshObservation =
                    actionability.inspect(
                            stage,
                            freshActor,
                            freshNode,
                            stable && freshActorToken == observation.actorToken(),
                            freshActorToken);
            ActionabilityCheck freshCheck =
                    freshObservation.actionability().check(action.force());
            recordEvidence(fresh, freshCheck);
            if (!freshCheck.actionable()) {
                return;
            }
            if (!beginDispatch(fresh)) {
                return;
            }
            try {
                input.dispatchAt(
                        freshActor, action, freshObservation.stageX(), freshObservation.stageY());
            } catch (RuntimeException failure) {
                fail(failure);
                return;
            }
            synchronized (this) {
                if (phase == RequestPhase.DISPATCHING) {
                    phase = RequestPhase.AWAITING_FRAME;
                }
            }
            armDeadline();
        }

        /**
         * Arms one deadline signal for the dispatched action. Post-dispatch the action can only
         * complete through a rendered frame; the signal enforces the remaining deadline when no
         * frame arrives. The scheduling call itself never runs under the request monitor: a
         * zero-delay scheduler may invoke the signal inline, and the timeout claim completes the
         * future (running caller continuations) only after the monitor has been released. The
         * install-or-cancel reconcile under the monitor keeps a racing completion, cancellation,
         * or close consistent with a single armed registration.
         */
        private void armDeadline() {
            Duration delay;
            synchronized (this) {
                if (deadlineCancellation != null || phase != RequestPhase.AWAITING_FRAME) {
                    return;
                }
                delay = deadline.remaining();
            }
            DeadlineScheduler.Cancellation scheduled;
            try {
                scheduled = deadlines.schedule(delay, this::deadlineReached);
            } catch (RejectedExecutionException failure) {
                if (ownedScheduler == null) {
                    throw failure;
                }
                // The harness's own scheduler was shut down by a concurrent close after this
                // action was dispatched. Fail the action as closed instead of surfacing an
                // internal registration error from the render thread.
                fail(sessionClosed());
                return;
            }
            boolean cancelScheduled;
            synchronized (this) {
                if (phase != RequestPhase.TERMINAL && deadlineCancellation == null) {
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

        /** Claims the timeout under the request monitor; a late signal observes terminal state. */
        private void deadlineReached() {
            HarnessException failure;
            synchronized (this) {
                if (phase != RequestPhase.AWAITING_FRAME) {
                    return;
                }
                if (!deadline.isExpired()) {
                    return;
                }
                failure = timeoutLocked();
            }
            fail(failure);
        }

        private boolean beginDispatch(SemanticSnapshot snapshot) {
            HarnessException failure = null;
            synchronized (this) {
                if (phase != RequestPhase.PENDING) {
                    return false;
                }
                if (deadline.isExpired()) {
                    failure = timeoutLocked();
                } else {
                    beforeRevision = snapshot.revision();
                    beforeFrame = snapshot.frame();
                    phase = RequestPhase.DISPATCHING;
                }
            }
            if (failure != null) {
                fail(failure);
                return false;
            }
            return true;
        }

        private boolean observeStability(
                long frame, Scene2dActionability.Observation observation) {
            if (frame == lastStableFrame) {
                return stableSamples >= 2;
            }
            if (observation.actorToken() == lastActorToken
                    && observation.stageBounds().equals(lastBounds)) {
                stableSamples++;
            } else {
                lastActorToken = observation.actorToken();
                lastBounds = observation.stageBounds();
                stableSamples = 1;
            }
            lastStableFrame = frame;
            return stableSamples >= 2;
        }

        private void resetStability() {
            lastBounds = null;
            lastActorToken = 0;
            lastStableFrame = Long.MIN_VALUE;
            stableSamples = 0;
        }

        private void completeAfterFrame() {
            SemanticSnapshot after = freshSnapshot();
            recordSnapshot(after);
            long dispatchedRevision;
            long dispatchedFrame;
            synchronized (this) {
                if (phase != RequestPhase.AWAITING_FRAME) {
                    return;
                }
                dispatchedRevision = beforeRevision;
                dispatchedFrame = beforeFrame;
            }
            if (after.frame() <= dispatchedFrame
                    || after.revision() <= dispatchedRevision) {
                return;
            }
            QueryResult result = locators.query(after, locator);
            String observed = observedState(result);
            finish(new ActionResult(
                    dispatchedRevision,
                    after.revision(),
                    observed,
                    Map.of(
                            "action", action.getClass().getSimpleName(),
                            "beforeFrame", Long.toString(dispatchedFrame),
                            "afterFrame", Long.toString(after.frame()))));
        }

        private String observedState(QueryResult result) {
            if (result.matches().size() != 1) {
                return result.matches().isEmpty() ? "detached" : "ambiguous";
            }
            SemanticNode node = result.matches().getFirst();
            if (node.state().checked().isPresent()) {
                return Boolean.toString(node.state().checked().orElseThrow());
            }
            if (node.state().selected().isPresent()) {
                return Boolean.toString(node.state().selected().orElseThrow());
            }
            if (node.text() != null) {
                return node.text();
            }
            return Boolean.toString(node.state().focused());
        }

        private Actor actorFor(String nodeId) {
            if (nodeId.length() < 2 || nodeId.charAt(0) != 'n') {
                return null;
            }
            final int target;
            try {
                target = Integer.parseInt(nodeId.substring(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
            return findActor(stage.getRoot(), target, new int[] {0});
        }

        private Actor findActor(Actor actor, int target, int[] index) {
            if (index[0]++ == target) {
                return actor;
            }
            if (actor instanceof Group group) {
                SnapshotArray<Actor> children = group.getChildren();
                for (int childIndex = 0; childIndex < children.size; childIndex++) {
                    Actor found = findActor(children.get(childIndex), target, index);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private void recordSnapshot(SemanticSnapshot snapshot) {
            synchronized (this) {
                if (phase != RequestPhase.TERMINAL) {
                    lastSnapshot = snapshot;
                }
            }
        }

        private void recordCheck(ActionabilityCheck check) {
            synchronized (this) {
                if (phase != RequestPhase.TERMINAL) {
                    lastCheck = check;
                }
            }
        }

        private void recordEvidence(
                SemanticSnapshot snapshot, ActionabilityCheck check) {
            synchronized (this) {
                if (phase != RequestPhase.TERMINAL) {
                    lastSnapshot = snapshot;
                    lastCheck = check;
                }
            }
        }

        private HarnessException timeout() {
            synchronized (this) {
                return timeoutLocked();
            }
        }

        private HarnessException timeoutLocked() {
            OptionalLong revision = lastSnapshot == null
                    ? OptionalLong.empty()
                    : OptionalLong.of(lastSnapshot.revision());
            String unmet = lastCheck == null
                    ? "ATTACHED"
                    : lastCheck.unmet().stream()
                            .map(Enum::name)
                            .collect(Collectors.joining(","));
            return new HarnessException(
                    ErrorCode.TIMEOUT,
                    "Action exceeded its monotonic deadline",
                    new ErrorEvidence(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(locator.toString()),
                            deadline.elapsed(),
                            revision,
                            Optional.empty(),
                            List.of(),
                            Map.of(
                                    "action", action.getClass().getSimpleName(),
                                    "timeout", deadline.timeout().toString(),
                                    "unmet", unmet),
                            List.of()));
        }

        private void finish(ActionResult result) {
            synchronized (this) {
                if (phase != RequestPhase.AWAITING_FRAME) {
                    return;
                }
                phase = RequestPhase.TERMINAL;
            }
            super.complete(result);
            cleanup();
        }

        void failBeforeDispatch(Throwable failure) {
            boolean claimed;
            synchronized (this) {
                claimed = phase == RequestPhase.PENDING;
                if (claimed) {
                    phase = RequestPhase.TERMINAL;
                }
            }
            if (claimed) {
                super.completeExceptionally(normalize(failure));
                cleanup();
            }
        }

        void fail(Throwable failure) {
            boolean claimed;
            synchronized (this) {
                claimed = phase != RequestPhase.TERMINAL;
                if (claimed) {
                    phase = RequestPhase.TERMINAL;
                }
            }
            if (claimed) {
                super.completeExceptionally(normalize(failure));
                cleanup();
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean claimed;
            synchronized (this) {
                claimed = phase == RequestPhase.PENDING
                        || phase == RequestPhase.AWAITING_FRAME;
                if (claimed) {
                    phase = RequestPhase.TERMINAL;
                }
            }
            if (!claimed) {
                return false;
            }
            boolean cancelled = super.cancel(false);
            cleanup();
            return cancelled;
        }

        @Override public boolean complete(ActionResult value) {
            return false;
        }

        @Override public boolean completeExceptionally(Throwable failure) {
            return false;
        }

        private Throwable normalize(Throwable failure) {
            return failure instanceof CompletionException ? unwrap(failure) : failure;
        }

        /** Invalidates the armed deadline signal; a signal already dispatched stays a no-op. */
        void cancelDeadline() {
            DeadlineScheduler.Cancellation scheduled;
            synchronized (this) {
                scheduled = deadlineCancellation;
                deadlineCancellation = null;
            }
            if (scheduled != null) {
                scheduled.cancel();
            }
        }

        private void cleanup() {
            DeadlineScheduler.Cancellation scheduled;
            FrameSignal.Subscription attached;
            synchronized (this) {
                attached = subscription;
                subscription = null;
                scheduled = deadlineCancellation;
                deadlineCancellation = null;
            }
            if (scheduled != null) {
                scheduled.cancel();
            }
            if (attached != null) {
                attached.close();
            }
            finished(this);
        }
    }

    private enum RequestPhase {
        PENDING,
        DISPATCHING,
        AWAITING_FRAME,
        TERMINAL
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static HarnessException sessionClosed() {
        return new HarnessException(
                ErrorCode.SESSION_CLOSED,
                "Scene2D harness is closed",
                ErrorEvidence.empty());
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
     * Deadline scheduler owned by a harness created through the legacy {@code Scene2dHarness}
     * constructor. A daemon worker executes deadline signals; {@link #shutdownNowAndAwait()}
     * stops it when the harness closes so a legacy harness never leaks a scheduler thread.
     * Cancelled signals are removed from the work queue so they neither run nor retain the
     * harness after cancellation.
     */
    private static final class OwnedDeadlineScheduler implements DeadlineScheduler {
        private static final Duration SHUTDOWN_BOUND = Duration.ofSeconds(1);

        private final ScheduledThreadPoolExecutor executor;

        OwnedDeadlineScheduler() {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "scene2d-harness-deadlines");
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
         * wait only covers a signal already running when the harness closes.
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
}
