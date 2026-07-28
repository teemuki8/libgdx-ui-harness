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
            request.fail(failure);
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
        private int lastActorIdentity;
        private long lastStableFrame = Long.MIN_VALUE;
        private int stableSamples;
        private boolean scheduled;
        private boolean dispatched;
        private long beforeRevision;
        private long beforeFrame;

        ActionRequest(Locator locator, Action action, Deadline deadline) {
            this.locator = locator;
            this.action = action;
            this.deadline = deadline;
        }

        synchronized void attach(FrameSignal.Subscription subscription) {
            if (isDone()) {
                subscription.close();
            } else {
                this.subscription = subscription;
            }
        }

        @Override public void onFrame(FrameSignal.Frame frame) {
            synchronized (this) {
                if (isDone()) {
                    return;
                }
                if (deadline.isExpired()) {
                    fail(timeout());
                    return;
                }
                if (dispatched && frame.frame() <= beforeFrame) {
                    return;
                }
            }
            schedule();
        }

        @Override public void onClosed() {
            fail(sessionClosed());
        }

        void schedule() {
            synchronized (this) {
                if (isDone() || scheduled) {
                    return;
                }
                if (deadline.isExpired()) {
                    fail(timeout());
                    return;
                }
                scheduled = true;
            }
            CompletionStage<Void> submitted = scheduler.submit(() -> {
                runOnRenderThread();
                return null;
            }, deadline);
            submitted.whenComplete((ignored, failure) -> {
                synchronized (ActionRequest.this) {
                    scheduled = false;
                }
                if (failure != null) {
                    Throwable cause = unwrap(failure);
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
            synchronized (this) {
                if (isDone()) {
                    return;
                }
                if (deadline.isExpired()) {
                    fail(timeout());
                    return;
                }
                if (dispatched) {
                    completeAfterFrame();
                } else {
                    attemptDispatch();
                }
            }
        }

        private void attemptDispatch() {
            SemanticSnapshot snapshot = freshSnapshot();
            lastSnapshot = snapshot;
            SemanticNode node;
            try {
                node = locators.resolveStrict(snapshot, locator);
            } catch (HarnessException failure) {
                if (failure.code() == ErrorCode.NOT_FOUND) {
                    lastCheck = new Actionability(
                            false, false, false, false, false, false, false).check(action.force());
                    resetStability();
                    return;
                }
                fail(failure);
                return;
            }

            Actor actor = actorFor(node.id());
            if (actor == null) {
                lastCheck = new Actionability(
                        false, false, false, false, false, false, false).check(action.force());
                resetStability();
                return;
            }
            Scene2dActionability.Observation observation =
                    actionability.inspect(stage, actor, node, false);
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
            lastCheck = current.check(action.force());
            if (!lastCheck.actionable()) {
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
            Scene2dActionability.Observation freshObservation =
                    actionability.inspect(stage, freshActor, freshNode, stable
                            && freshActorIdentityMatches(freshActor, observation));
            lastCheck = freshObservation.actionability().check(action.force());
            if (!lastCheck.actionable()) {
                return;
            }
            input.dispatchAt(
                    freshActor, action, freshObservation.stageX(), freshObservation.stageY());
            lastSnapshot = fresh;
            beforeRevision = fresh.revision();
            beforeFrame = fresh.frame();
            dispatched = true;
        }

        private boolean freshActorIdentityMatches(
                Actor freshActor, Scene2dActionability.Observation prior) {
            return System.identityHashCode(freshActor) == prior.actorIdentity();
        }

        private boolean observeStability(
                long frame, Scene2dActionability.Observation observation) {
            if (frame == lastStableFrame) {
                return stableSamples >= 2;
            }
            if (observation.actorIdentity() == lastActorIdentity
                    && observation.stageBounds().equals(lastBounds)) {
                stableSamples++;
            } else {
                lastActorIdentity = observation.actorIdentity();
                lastBounds = observation.stageBounds();
                stableSamples = 1;
            }
            lastStableFrame = frame;
            return stableSamples >= 2;
        }

        private void resetStability() {
            lastBounds = null;
            lastActorIdentity = 0;
            lastStableFrame = Long.MIN_VALUE;
            stableSamples = 0;
        }

        private void completeAfterFrame() {
            SemanticSnapshot after = freshSnapshot();
            lastSnapshot = after;
            if (after.frame() <= beforeFrame || after.revision() <= beforeRevision) {
                return;
            }
            QueryResult result = locators.query(after, locator);
            String observed = observedState(result);
            complete(new ActionResult(
                    beforeRevision,
                    after.revision(),
                    observed,
                    Map.of(
                            "action", action.getClass().getSimpleName(),
                            "beforeFrame", Long.toString(beforeFrame),
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

        private HarnessException timeout() {
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
                                    "unmet", unmet)));
        }

        @Override public boolean complete(ActionResult value) {
            boolean completed = super.complete(value);
            if (completed) {
                cleanup();
            }
            return completed;
        }

        @Override public boolean completeExceptionally(Throwable failure) {
            boolean completed = super.completeExceptionally(failure);
            if (completed) {
                cleanup();
            }
            return completed;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(false);
            if (cancelled) {
                cleanup();
            }
            return cancelled;
        }

        void fail(Throwable failure) {
            completeExceptionally(failure instanceof CompletionException
                    ? unwrap(failure)
                    : failure);
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
