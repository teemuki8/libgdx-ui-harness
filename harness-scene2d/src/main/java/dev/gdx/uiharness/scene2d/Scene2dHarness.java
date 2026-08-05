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
import dev.gdx.uiharness.core.wait.FrameSignal;
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
    private final LocatorEngine locators = new StrictResolution();
    private final Scene2dActionability actionability = new Scene2dActionability();
    private final Scene2dInputDispatcher input;
    private final Object lifecycle = new Object();
    private final Set<ActionRequest> requests =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean open = true;

    /** Attaches orchestration to explicit render-loop, frame, revision, and input dependencies. */
    public Scene2dHarness(
            Stage stage,
            InputProcessor input,
            Scene2dSession session,
            RenderThreadScheduler scheduler,
            FrameSignal frames,
            LongSupplier revisions,
            LongSupplier frameNumbers) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.session = Objects.requireNonNull(session, "session");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.frameNumbers = Objects.requireNonNull(frameNumbers, "frameNumbers");
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

    /** Fails pending actions without closing application-owned Stage, session, or scheduler. */
    @Override public void close() {
        ActionRequest[] pending;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            pending = requests.toArray(ActionRequest[]::new);
            requests.clear();
        }
        HarnessException failure = sessionClosed();
        for (ActionRequest request : pending) {
            request.failBeforeDispatch(failure);
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
                claimed = phase == RequestPhase.PENDING;
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

        private void cleanup() {
            FrameSignal.Subscription attached;
            synchronized (this) {
                attached = subscription;
                subscription = null;
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
}
