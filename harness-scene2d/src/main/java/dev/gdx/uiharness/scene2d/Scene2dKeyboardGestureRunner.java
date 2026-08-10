package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.InputProcessor;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator;
import dev.gdx.uiharness.core.gesture.KeyboardGestureRequest;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepEvidence;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepKind;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.TerminalOutcome;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Executes one session-scoped keyboard gesture through real render-thread input dispatch. */
public final class Scene2dKeyboardGestureRunner implements AutoCloseable {
    private final String sessionId;
    private final InputProcessor input;
    private final RenderThreadScheduler scheduler;
    private final FrameSignal frames;
    private final LongSupplier revisions;
    private final LongSupplier frameNumbers;
    private final DeadlineScheduler deadlines;
    private final Optional<ExactTickCoordinator> ticks;
    private final Consumer<TraceEvent> traceSink;
    private final Object lifecycle = new Object();
    private Operation active;
    private boolean open = true;

    /** Attaches gesture execution to explicit input, frame, deadline, and optional tick sources. */
    public Scene2dKeyboardGestureRunner(
            String sessionId,
            InputProcessor input,
            RenderThreadScheduler scheduler,
            FrameSignal frames,
            LongSupplier revisions,
            LongSupplier frameNumbers,
            DeadlineScheduler deadlines,
            Optional<ExactTickCoordinator> ticks,
            Consumer<TraceEvent> traceSink) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.input = Objects.requireNonNull(input, "input");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.frameNumbers = Objects.requireNonNull(frameNumbers, "frameNumbers");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
    }

    /** Starts one validated gesture or rejects overlapping direct execution. */
    public CompletionStage<KeyboardGestureResult> execute(
            String requestId, KeyboardGestureRequest request, Deadline deadline) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        Operation operation;
        synchronized (lifecycle) {
            if (!open) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("keyboard gesture runner is closed"));
            }
            if (active != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("keyboard gesture is already active"));
            }
            operation = new Operation(requestId, request, deadline);
            active = operation;
        }
        operation.start();
        return operation;
    }

    private void finished(Operation operation) {
        synchronized (lifecycle) {
            if (active == operation) {
                active = null;
            }
        }
    }

    /** Stops admission. Active-operation cleanup is added by the lifecycle implementation. */
    public CompletionStage<Void> stop() {
        synchronized (lifecycle) {
            open = false;
            if (active == null) {
                return CompletableFuture.completedFuture(null);
            }
            return active.thenApply(ignored -> null);
        }
    }

    /** Closes admission after active work reaches a terminal state. */
    @Override public void close() {
        stop();
    }

    private final class Operation extends CompletableFuture<KeyboardGestureResult> {
        private final String requestId;
        private final KeyboardGestureRequest request;
        private final Deadline deadline;
        private final long startRevision;
        private final long startFrame;
        private final long startedAtNanos;
        private final LinkedHashSet<Integer> held = new LinkedHashSet<>();
        private final ArrayList<StepEvidence> evidence = new ArrayList<>();
        private int stepIndex;
        private int completedSteps;
        private FrameSignal.Subscription frameSubscription;

        Operation(String requestId, KeyboardGestureRequest request, Deadline deadline) {
            this.requestId = requestId;
            this.request = request;
            this.deadline = deadline;
            startRevision = revisions.getAsLong();
            startFrame = frameNumbers.getAsLong();
            startedAtNanos = deadline.clock().nanoTime();
        }

        void start() {
            scheduleNext();
        }

        private void scheduleNext() {
            if (stepIndex == request.steps().size()) {
                completeSuccess();
                return;
            }
            KeyboardGestureRequest.Step step = request.steps().get(stepIndex);
            switch (step) {
                case KeyboardGestureRequest.KeyDown down ->
                        scheduleKey(down.keycode(), true);
                case KeyboardGestureRequest.KeyUp up ->
                        scheduleKey(up.keycode(), false);
                case KeyboardGestureRequest.WaitFrames wait -> waitForFrames(wait.count());
                case KeyboardGestureRequest.WaitTicks ignored ->
                        completeExceptionally(new UnsupportedOperationException(
                                "tick waits are not wired yet"));
            }
        }

        private void scheduleKey(int keycode, boolean down) {
            CompletionStage<Transition> submitted = scheduler.submit(() -> {
                long beforeRevision = revisions.getAsLong();
                long beforeFrame = frameNumbers.getAsLong();
                synchronized (this) {
                    if (down) {
                        held.add(keycode);
                    }
                }
                if (down) {
                    Scene2dInputDispatcher.keyDown(input, keycode);
                } else {
                    Scene2dInputDispatcher.keyUp(input, keycode);
                    synchronized (this) {
                        held.remove(keycode);
                    }
                }
                return new Transition(
                        beforeRevision, beforeFrame,
                        revisions.getAsLong(), frameNumbers.getAsLong(), heldSnapshot());
            }, deadline);
            submitted.whenComplete((transition, failure) -> {
                if (failure != null) {
                    finished(this);
                    super.completeExceptionally(failure);
                    return;
                }
                synchronized (this) {
                    evidence.add(new StepEvidence(
                            stepIndex, down ? StepKind.KEY_DOWN : StepKind.KEY_UP,
                            StepStatus.COMPLETED, OptionalInt.of(keycode), OptionalInt.empty(),
                            transition.beforeRevision(), transition.beforeFrame(),
                            transition.afterRevision(), transition.afterFrame(),
                            transition.heldKeys(), Optional.empty()));
                    stepIndex++;
                    completedSteps++;
                }
                scheduleNext();
            });
        }

        private void waitForFrames(int count) {
            long beforeRevision = revisions.getAsLong();
            long beforeFrame = frameNumbers.getAsLong();
            frameSubscription = frames.subscribe(new FrameSignal.FrameListener() {
                private long lastFrame = beforeFrame;
                private int observed;

                @Override public void onFrame(FrameSignal.Frame frame) {
                    if (frame.frame() <= lastFrame) {
                        return;
                    }
                    lastFrame = frame.frame();
                    observed++;
                    if (observed != count) {
                        return;
                    }
                    FrameSignal.Subscription subscription;
                    synchronized (Operation.this) {
                        subscription = frameSubscription;
                        frameSubscription = null;
                        evidence.add(new StepEvidence(
                                stepIndex, StepKind.WAIT_FRAMES, StepStatus.COMPLETED,
                                OptionalInt.empty(), OptionalInt.of(count),
                                beforeRevision, beforeFrame, frame.revision(), frame.frame(),
                                heldSnapshot(), Optional.empty()));
                        stepIndex++;
                        completedSteps++;
                    }
                    subscription.close();
                    scheduleNext();
                }
            });
        }

        private List<Integer> heldSnapshot() {
            synchronized (this) {
                return List.copyOf(held);
            }
        }

        private void completeSuccess() {
            KeyboardGestureResult result = new KeyboardGestureResult(
                    KeyboardGestureRequest.SCHEMA_VERSION,
                    TerminalOutcome.COMPLETED,
                    request.steps().size(), request.steps().size(), completedSteps,
                    startRevision, startFrame,
                    revisions.getAsLong(), frameNumbers.getAsLong(),
                    Math.max(0L, deadline.clock().nanoTime() - startedAtNanos),
                    evidence, OptionalInt.empty(), Optional.empty(), List.of(),
                    CleanupStatus.NOT_REQUIRED, List.of(), Optional.empty());
            finished(this);
            super.complete(result);
        }
    }

    private record Transition(
            long beforeRevision,
            long beforeFrame,
            long afterRevision,
            long afterFrame,
            List<Integer> heldKeys) {}
}
