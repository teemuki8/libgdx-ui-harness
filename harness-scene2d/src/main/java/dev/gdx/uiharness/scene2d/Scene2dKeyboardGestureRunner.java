package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.InputProcessor;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickAdvanceResult;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickFailure;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickFailureCategory;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickPreflight;
import dev.gdx.uiharness.core.gesture.KeyboardGestureRequest;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupAttempt;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupAttemptStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.FailureCategory;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepEvidence;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepKind;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.TerminalOutcome;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Executes one session-scoped keyboard gesture through real render-thread input dispatch. */
public final class Scene2dKeyboardGestureRunner implements AutoCloseable {
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(1);

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
        this.sessionId = requireText(sessionId, "sessionId");
        this.input = Objects.requireNonNull(input, "input");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.frameNumbers = Objects.requireNonNull(frameNumbers, "frameNumbers");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
    }

    /** Starts one validated gesture or returns a structured lease/session rejection. */
    public CompletionStage<KeyboardGestureResult> execute(
            String requestId, KeyboardGestureRequest request, Deadline deadline) {
        requestId = requireText(requestId, "requestId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        Operation operation;
        FailureCategory rejection;
        synchronized (lifecycle) {
            if (!open) {
                rejection = FailureCategory.SESSION_CLOSED;
                operation = null;
            } else if (active != null) {
                rejection = FailureCategory.SESSION_BUSY;
                operation = null;
            } else {
                rejection = null;
                operation = new Operation(requestId, request, deadline);
                active = operation;
            }
        }
        if (rejection != null) {
            return CompletableFuture.completedFuture(rejection(request, deadline, rejection));
        }
        operation.start();
        return operation;
    }

    private KeyboardGestureResult rejection(
            KeyboardGestureRequest request, Deadline deadline, FailureCategory failure) {
        long revision = revisions.getAsLong();
        long frame = frameNumbers.getAsLong();
        TerminalOutcome outcome = failure == FailureCategory.SESSION_CLOSED
                ? TerminalOutcome.SESSION_CLOSED : TerminalOutcome.REJECTED;
        return new KeyboardGestureResult(
                KeyboardGestureRequest.SCHEMA_VERSION, outcome,
                request.steps().size(), 0, 0,
                revision, frame, revision, frame, deadline.elapsed().toNanos(),
                List.of(), OptionalInt.empty(), Optional.of(failure), List.of(),
                CleanupStatus.NOT_REQUIRED, List.of(), Optional.empty());
    }

    private void finished(Operation operation) {
        synchronized (lifecycle) {
            if (active == operation) {
                active = null;
            }
        }
    }

    /** Stops admission and completes after active render-thread cleanup reaches terminal state. */
    public CompletionStage<Void> stop() {
        Operation operation;
        synchronized (lifecycle) {
            open = false;
            operation = active;
        }
        if (operation == null) {
            return CompletableFuture.completedFuture(null);
        }
        operation.requestTermination(
                TerminalOutcome.SESSION_CLOSED, FailureCategory.SESSION_CLOSED,
                OptionalInt.empty());
        return operation.thenApply(ignored -> null);
    }

    /** Stops admission; integration must pump the scheduler until the returned stop stage ends. */
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
        private final ArrayList<CleanupAttempt> cleanup = new ArrayList<>();
        private Phase phase = Phase.NORMAL;
        private int stepIndex;
        private int completedSteps;
        private ActiveFrameWait activeFrameWait;
        private CompletableFuture<TickAdvanceResult> activeTick;
        private DeadlineScheduler.Cancellation requestDeadline;
        private DeadlineScheduler.Cancellation cleanupDeadline;
        private Terminal terminal;
        private List<Integer> cleanupOrder = List.of();
        private int cleanupIndex;
        private CompletableFuture<CleanupAttemptStatus> cleanupFuture;
        private boolean cleanupDeadlineReached;

        Operation(String requestId, KeyboardGestureRequest request, Deadline deadline) {
            this.requestId = requestId;
            this.request = request;
            this.deadline = deadline;
            startRevision = revisions.getAsLong();
            startFrame = frameNumbers.getAsLong();
            startedAtNanos = deadline.clock().nanoTime();
        }

        void start() {
            if (!preflightTicks()) {
                return;
            }
            armRequestDeadline();
            safeTrace("gesture-accepted", null, null);
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
            }
            scheduleNext();
        }

        private boolean preflightTicks() {
            for (int index = 0; index < request.steps().size(); index++) {
                KeyboardGestureRequest.Step step = request.steps().get(index);
                if (!(step instanceof KeyboardGestureRequest.WaitTicks wait)) {
                    continue;
                }
                if (ticks.isEmpty()) {
                    requestTermination(
                            TerminalOutcome.REJECTED,
                            FailureCategory.UNSUPPORTED_TICK_CAPABILITY,
                            OptionalInt.of(index));
                    return false;
                }
                TickPreflight result;
                try {
                    result = ticks.orElseThrow().preflight(wait.count(), deadline);
                } catch (RuntimeException | Error failure) {
                    requestTermination(
                            TerminalOutcome.REJECTED,
                            FailureCategory.TICK_ADVANCE_FAILURE,
                            OptionalInt.of(index));
                    return false;
                }
                if (result instanceof TickPreflight.Rejected rejected) {
                    TickTermination terminal = mapTickFailure(rejected.failure(), true);
                    requestTermination(
                            terminal.outcome(), terminal.failure(), OptionalInt.of(index));
                    return false;
                }
            }
            return true;
        }

        private void armRequestDeadline() {
            DeadlineScheduler.Cancellation armed = deadlines.schedule(
                    deadline.remaining(), () -> requestTermination(
                            TerminalOutcome.TIMED_OUT, FailureCategory.TIMEOUT,
                            OptionalInt.of(currentStepIndex())));
            boolean cancel;
            synchronized (this) {
                cancel = phase != Phase.NORMAL;
                if (!cancel) {
                    requestDeadline = armed;
                }
            }
            if (cancel) {
                armed.cancel();
            }
        }

        private synchronized int currentStepIndex() {
            return Math.min(stepIndex, request.steps().size() - 1);
        }

        private void scheduleNext() {
            KeyboardGestureRequest.Step step;
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
                if (stepIndex == request.steps().size()) {
                    completeSuccess();
                    return;
                }
                step = request.steps().get(stepIndex);
            }
            switch (step) {
                case KeyboardGestureRequest.KeyDown down ->
                        scheduleKey(down.keycode(), true);
                case KeyboardGestureRequest.KeyUp up ->
                        scheduleKey(up.keycode(), false);
                case KeyboardGestureRequest.WaitFrames wait -> waitForFrames(wait.count());
                case KeyboardGestureRequest.WaitTicks wait -> waitForTicks(wait.count());
            }
        }

        private void waitForTicks(int count) {
            int tickStep = currentStepIndex();
            long beforeRevision = revisions.getAsLong();
            long beforeFrame = frameNumbers.getAsLong();
            CompletableFuture<TickAdvanceResult> submitted;
            try {
                submitted = ticks.orElseThrow().advance(count, deadline).toCompletableFuture();
            } catch (RuntimeException | Error failure) {
                addFailedTickEvidence(
                        count, beforeRevision, beforeFrame,
                        revisions.getAsLong(), frameNumbers.getAsLong());
                requestTermination(
                        TerminalOutcome.FAILED, FailureCategory.TICK_ADVANCE_FAILURE,
                        OptionalInt.of(tickStep));
                return;
            }
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    submitted.cancel(false);
                    return;
                }
                activeTick = submitted;
            }
            submitted.whenComplete((advance, submissionFailure) -> {
                synchronized (this) {
                    if (phase != Phase.NORMAL || activeTick != submitted) {
                        return;
                    }
                    activeTick = null;
                }
                if (submissionFailure != null || advance == null) {
                    addFailedTickEvidence(
                            count, beforeRevision, beforeFrame,
                            revisions.getAsLong(), frameNumbers.getAsLong());
                    requestTermination(
                            deadline.isExpired() ? TerminalOutcome.TIMED_OUT
                                    : TerminalOutcome.FAILED,
                            deadline.isExpired() ? FailureCategory.TIMEOUT
                                    : FailureCategory.TICK_ADVANCE_FAILURE,
                            OptionalInt.of(tickStep));
                    return;
                }
                if (advance instanceof TickAdvanceResult.Failed failed) {
                    addFailedTickEvidence(
                            count, beforeRevision, beforeFrame,
                            revisions.getAsLong(), frameNumbers.getAsLong());
                    TickTermination terminal = mapTickFailure(failed.failure(), false);
                    requestTermination(
                            terminal.outcome(), terminal.failure(), OptionalInt.of(tickStep));
                    return;
                }
                TickAdvanceResult.Completed completed =
                        (TickAdvanceResult.Completed) advance;
                synchronized (this) {
                    if (phase != Phase.NORMAL || stepIndex != tickStep) {
                        return;
                    }
                    evidence.add(new StepEvidence(
                            stepIndex, StepKind.WAIT_TICKS, StepStatus.COMPLETED,
                            OptionalInt.empty(), OptionalInt.of(count),
                            beforeRevision, beforeFrame,
                            revisions.getAsLong(), frameNumbers.getAsLong(),
                            List.copyOf(held), Optional.of(completed.evidence())));
                    stepIndex++;
                    completedSteps++;
                    safeTrace("gesture-step", tickStep, "wait-ticks");
                }
                scheduleNext();
            });
        }

        private void addFailedTickEvidence(
                int count,
                long beforeRevision,
                long beforeFrame,
                long afterRevision,
                long afterFrame) {
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
                evidence.add(new StepEvidence(
                        stepIndex, StepKind.WAIT_TICKS, StepStatus.FAILED,
                        OptionalInt.empty(), OptionalInt.of(count),
                        beforeRevision, beforeFrame, afterRevision, afterFrame,
                        List.copyOf(held), Optional.empty()));
                stepIndex++;
            }
        }

        private TickTermination mapTickFailure(TickFailure tickFailure, boolean preflight) {
            TickFailureCategory category = tickFailure.category();
            return switch (category) {
                case UNSUPPORTED_CAPABILITY -> new TickTermination(
                        preflight ? TerminalOutcome.REJECTED : TerminalOutcome.FAILED,
                        FailureCategory.UNSUPPORTED_TICK_CAPABILITY);
                case INVALID_STATE, LIMIT_EXCEEDED -> new TickTermination(
                        preflight ? TerminalOutcome.REJECTED : TerminalOutcome.FAILED,
                        FailureCategory.INVALID_RUNTIME_STATE);
                case TIMED_OUT -> new TickTermination(
                        TerminalOutcome.TIMED_OUT, FailureCategory.TIMEOUT);
                case EPOCH_CHANGED -> new TickTermination(
                        TerminalOutcome.FAILED, FailureCategory.EPOCH_CHANGED);
                case CANCELLED -> new TickTermination(
                        TerminalOutcome.CANCELLED, FailureCategory.CANCELLED);
                case CALLBACK_FAILED, INTERNAL_FAILURE -> new TickTermination(
                        TerminalOutcome.FAILED, FailureCategory.TICK_ADVANCE_FAILURE);
            };
        }

        private void scheduleKey(int keycode, boolean down) {
            long queuedRevision = revisions.getAsLong();
            long queuedFrame = frameNumbers.getAsLong();
            CompletionStage<KeyDispatch> submitted = scheduler.submit(() -> {
                long beforeRevision = revisions.getAsLong();
                long beforeFrame = frameNumbers.getAsLong();
                synchronized (this) {
                    if (phase != Phase.NORMAL) {
                        return KeyDispatch.skipped(
                                beforeRevision, beforeFrame, List.copyOf(held));
                    }
                    if (down) {
                        held.add(keycode);
                    }
                }
                try {
                    if (down) {
                        Scene2dInputDispatcher.keyDown(input, keycode);
                    } else {
                        Scene2dInputDispatcher.keyUp(input, keycode);
                        synchronized (this) {
                            held.remove(keycode);
                        }
                    }
                    return KeyDispatch.completed(
                            beforeRevision, beforeFrame,
                            revisions.getAsLong(), frameNumbers.getAsLong(), heldSnapshot());
                } catch (RuntimeException | Error callbackFailure) {
                    return KeyDispatch.failed(
                            beforeRevision, beforeFrame,
                            revisions.getAsLong(), frameNumbers.getAsLong(), heldSnapshot());
                }
            }, deadline);
            submitted.whenComplete((dispatch, submissionFailure) -> {
                if (submissionFailure != null) {
                    int failedIndex = currentStepIndex();
                    addFailedKeyEvidence(
                            keycode, down, queuedRevision, queuedFrame,
                            revisions.getAsLong(), frameNumbers.getAsLong());
                    requestTermination(
                            deadline.isExpired() ? TerminalOutcome.TIMED_OUT
                                    : TerminalOutcome.FAILED,
                            deadline.isExpired() ? FailureCategory.TIMEOUT
                                    : FailureCategory.KEY_DISPATCH_FAILURE,
                            OptionalInt.of(failedIndex));
                    return;
                }
                if (dispatch.skipped()) {
                    return;
                }
                if (!dispatch.success()) {
                    int failedIndex = currentStepIndex();
                    addKeyEvidence(keycode, down, dispatch, StepStatus.FAILED);
                    requestTermination(
                            TerminalOutcome.FAILED, FailureCategory.KEY_DISPATCH_FAILURE,
                            OptionalInt.of(failedIndex));
                    return;
                }
                synchronized (this) {
                    if (phase != Phase.NORMAL) {
                        return;
                    }
                    int completedIndex = stepIndex;
                    addKeyEvidence(keycode, down, dispatch, StepStatus.COMPLETED);
                    safeTrace("gesture-step", completedIndex,
                            down ? "key-down" : "key-up");
                }
                scheduleNext();
            });
        }

        private void addKeyEvidence(
                int keycode, boolean down, KeyDispatch dispatch, StepStatus status) {
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
                evidence.add(new StepEvidence(
                        stepIndex, down ? StepKind.KEY_DOWN : StepKind.KEY_UP, status,
                        OptionalInt.of(keycode), OptionalInt.empty(),
                        dispatch.beforeRevision(), dispatch.beforeFrame(),
                        dispatch.afterRevision(), dispatch.afterFrame(),
                        dispatch.heldKeys(), Optional.empty()));
                stepIndex++;
                if (status == StepStatus.COMPLETED) {
                    completedSteps++;
                }
            }
        }

        private void addFailedKeyEvidence(
                int keycode,
                boolean down,
                long beforeRevision,
                long beforeFrame,
                long afterRevision,
                long afterFrame) {
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
                evidence.add(new StepEvidence(
                        stepIndex, down ? StepKind.KEY_DOWN : StepKind.KEY_UP,
                        StepStatus.FAILED, OptionalInt.of(keycode), OptionalInt.empty(),
                        beforeRevision, beforeFrame, afterRevision, afterFrame,
                        List.copyOf(held), Optional.empty()));
                stepIndex++;
            }
        }

        private void waitForFrames(int count) {
            ActiveFrameWait wait = new ActiveFrameWait(
                    count, revisions.getAsLong(), frameNumbers.getAsLong());
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
                activeFrameWait = wait;
            }
            FrameSignal.Subscription subscription;
            try {
                subscription = frames.subscribe(wait);
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    if (activeFrameWait == wait) {
                        activeFrameWait = null;
                    }
                }
                requestTermination(
                        TerminalOutcome.FAILED, FailureCategory.FRAME_SOURCE_CLOSED,
                        OptionalInt.of(currentStepIndex()));
                return;
            }
            wait.attach(subscription);
        }

        private final class ActiveFrameWait implements FrameSignal.FrameListener {
            private final int count;
            private final long beforeRevision;
            private final long beforeFrame;
            private long lastFrame;
            private int observed;
            private FrameSignal.Subscription subscription;
            private boolean closeWhenAttached;
            private boolean terminal;

            ActiveFrameWait(int count, long beforeRevision, long beforeFrame) {
                this.count = count;
                this.beforeRevision = beforeRevision;
                this.beforeFrame = beforeFrame;
                lastFrame = beforeFrame;
            }

            synchronized void attach(FrameSignal.Subscription supplied) {
                if (closeWhenAttached) {
                    supplied.close();
                } else {
                    subscription = supplied;
                }
            }

            @Override public void onFrame(FrameSignal.Frame frame) {
                FrameSignal.Subscription toClose;
                synchronized (this) {
                    if (terminal || frame.frame() <= lastFrame) {
                        return;
                    }
                    lastFrame = frame.frame();
                    observed++;
                    if (observed != count) {
                        return;
                    }
                    terminal = true;
                    toClose = detach();
                }
                synchronized (Operation.this) {
                    if (phase != Phase.NORMAL || activeFrameWait != this) {
                        close(toClose);
                        return;
                    }
                    activeFrameWait = null;
                    evidence.add(new StepEvidence(
                            stepIndex, StepKind.WAIT_FRAMES, StepStatus.COMPLETED,
                            OptionalInt.empty(), OptionalInt.of(count),
                            beforeRevision, beforeFrame, frame.revision(), frame.frame(),
                            List.copyOf(held), Optional.empty()));
                    stepIndex++;
                    completedSteps++;
                    safeTrace("gesture-step", stepIndex - 1, "wait-frames");
                }
                close(toClose);
                scheduleNext();
            }

            @Override public void onClosed() {
                FrameSignal.Subscription toClose;
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                    toClose = detach();
                }
                close(toClose);
                requestTermination(
                        TerminalOutcome.FAILED, FailureCategory.FRAME_SOURCE_CLOSED,
                        OptionalInt.of(currentStepIndex()));
            }

            void cancel() {
                FrameSignal.Subscription toClose;
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                    toClose = detach();
                }
                close(toClose);
            }

            private FrameSignal.Subscription detach() {
                FrameSignal.Subscription detached = subscription;
                subscription = null;
                if (detached == null) {
                    closeWhenAttached = true;
                }
                return detached;
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) {
                return false;
            }
            requestTermination(
                    TerminalOutcome.CANCELLED, FailureCategory.CANCELLED,
                    OptionalInt.of(currentStepIndex()));
            return false;
        }

        void requestTermination(
                TerminalOutcome outcome,
                FailureCategory failure,
                OptionalInt failureStep) {
            ActiveFrameWait frameWait;
            CompletableFuture<TickAdvanceResult> tickAdvance;
            DeadlineScheduler.Cancellation requestCancellation;
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
                phase = Phase.CLEANING;
                terminal = new Terminal(outcome, failure, failureStep);
                frameWait = activeFrameWait;
                activeFrameWait = null;
                tickAdvance = activeTick;
                activeTick = null;
                requestCancellation = requestDeadline;
                requestDeadline = null;
                cleanupOrder = reverse(held);
            }
            close(requestCancellation);
            if (frameWait != null) {
                frameWait.cancel();
            }
            if (tickAdvance != null) {
                tickAdvance.cancel(false);
            }
            safeTrace("gesture-failed", failureStep.isPresent()
                    ? failureStep.getAsInt() : null, failure.name().toLowerCase());
            beginCleanup();
        }

        private void beginCleanup() {
            synchronized (this) {
                if (cleanupOrder.isEmpty()) {
                    finishTerminal(CleanupStatus.NOT_REQUIRED);
                    return;
                }
            }
            Deadline cleanupBound = Deadline.after(deadline.clock(), CLEANUP_TIMEOUT);
            DeadlineScheduler.Cancellation armed = deadlines.schedule(
                    CLEANUP_TIMEOUT, this::cleanupTimedOut);
            boolean cancel;
            synchronized (this) {
                cancel = phase != Phase.CLEANING;
                if (!cancel) {
                    cleanupDeadline = armed;
                }
            }
            if (cancel) {
                armed.cancel();
                return;
            }
            scheduleCleanup(cleanupBound);
        }

        private void scheduleCleanup(Deadline cleanupBound) {
            int keycode;
            synchronized (this) {
                if (phase != Phase.CLEANING) {
                    return;
                }
                if (cleanupIndex == cleanupOrder.size()) {
                    finishTerminal(held.isEmpty()
                            ? CleanupStatus.COMPLETED : CleanupStatus.FAILED);
                    return;
                }
                keycode = cleanupOrder.get(cleanupIndex);
            }
            CompletableFuture<CleanupAttemptStatus> submitted = scheduler.submit(() -> {
                try {
                    Scene2dInputDispatcher.keyUp(input, keycode);
                    synchronized (this) {
                        held.remove(keycode);
                    }
                    return CleanupAttemptStatus.RELEASED;
                } catch (RuntimeException | Error failure) {
                    return CleanupAttemptStatus.DISPATCH_FAILED;
                }
            }, cleanupBound).toCompletableFuture();
            synchronized (this) {
                if (phase != Phase.CLEANING) {
                    submitted.cancel(false);
                    return;
                }
                cleanupFuture = submitted;
            }
            submitted.whenComplete((status, submissionFailure) -> {
                CleanupAttemptStatus observed = status;
                if (submissionFailure != null) {
                    observed = cleanupDeadlineReached
                            || submissionFailure instanceof CancellationException
                            ? CleanupAttemptStatus.DEADLINE_EXCEEDED
                            : CleanupAttemptStatus.SCHEDULER_REJECTED;
                }
                boolean timedOut;
                synchronized (this) {
                    if (phase != Phase.CLEANING) {
                        return;
                    }
                    cleanupFuture = null;
                    cleanup.add(new CleanupAttempt(keycode, observed));
                    cleanupIndex++;
                    timedOut = cleanupDeadlineReached;
                    if (timedOut) {
                        addRemainingDeadlineAttempts();
                    }
                }
                safeTrace("gesture-cleanup", cleanupIndex - 1,
                        observed.name().toLowerCase());
                if (timedOut) {
                    finishTerminal(CleanupStatus.FAILED);
                } else {
                    scheduleCleanup(cleanupBound);
                }
            });
        }

        private void cleanupTimedOut() {
            CompletableFuture<CleanupAttemptStatus> pending;
            synchronized (this) {
                if (phase != Phase.CLEANING) {
                    return;
                }
                cleanupDeadlineReached = true;
                pending = cleanupFuture;
                if (pending == null) {
                    addRemainingDeadlineAttempts();
                }
            }
            if (pending == null) {
                finishTerminal(CleanupStatus.FAILED);
            } else {
                pending.cancel(false);
            }
        }

        private void addRemainingDeadlineAttempts() {
            while (cleanupIndex < cleanupOrder.size()) {
                cleanup.add(new CleanupAttempt(
                        cleanupOrder.get(cleanupIndex++),
                        CleanupAttemptStatus.DEADLINE_EXCEEDED));
            }
        }

        private void completeSuccess() {
            DeadlineScheduler.Cancellation requestCancellation;
            KeyboardGestureResult result;
            synchronized (this) {
                if (phase != Phase.NORMAL) {
                    return;
                }
                phase = Phase.TERMINAL;
                requestCancellation = requestDeadline;
                requestDeadline = null;
                result = result(
                        TerminalOutcome.COMPLETED, Optional.empty(), OptionalInt.empty(),
                        CleanupStatus.NOT_REQUIRED);
            }
            close(requestCancellation);
            safeTrace("gesture-completed", null, "completed");
            finished(this);
            super.complete(result);
        }

        private void finishTerminal(CleanupStatus cleanupStatus) {
            DeadlineScheduler.Cancellation cleanupCancellation;
            KeyboardGestureResult result;
            synchronized (this) {
                if (phase != Phase.CLEANING) {
                    return;
                }
                phase = Phase.TERMINAL;
                cleanupCancellation = cleanupDeadline;
                cleanupDeadline = null;
                result = result(
                        terminal.outcome(), Optional.of(terminal.failure()),
                        terminal.failureStep(), cleanupStatus);
            }
            close(cleanupCancellation);
            safeTrace("gesture-completed", null, terminal.outcome().name().toLowerCase());
            finished(this);
            super.complete(result);
        }

        private KeyboardGestureResult result(
                TerminalOutcome outcome,
                Optional<FailureCategory> failure,
                OptionalInt failureStep,
                CleanupStatus cleanupStatus) {
            return new KeyboardGestureResult(
                    KeyboardGestureRequest.SCHEMA_VERSION, outcome,
                    request.steps().size(), evidence.size(), completedSteps,
                    startRevision, startFrame,
                    revisions.getAsLong(), frameNumbers.getAsLong(),
                    Math.max(0L, deadline.clock().nanoTime() - startedAtNanos),
                    evidence, failureStep, failure, List.copyOf(held),
                    cleanupStatus, cleanup, Optional.empty());
        }

        private List<Integer> heldSnapshot() {
            synchronized (this) {
                return List.copyOf(held);
            }
        }

        private void safeTrace(String event, Integer index, String detail) {
            Map<String, String> traceEvidence;
            if (index == null && detail == null) {
                traceEvidence = Map.of("event", event);
            } else if (index == null) {
                traceEvidence = Map.of("event", event, "detail", detail);
            } else if (detail == null) {
                traceEvidence = Map.of("event", event, "step", Integer.toString(index));
            } else {
                traceEvidence = Map.of(
                        "event", event, "step", Integer.toString(index), "detail", detail);
            }
            try {
                traceSink.accept(new TraceEvent(
                        -1, TraceEvent.Kind.LOG, sessionId, requestId,
                        Math.max(0L, deadline.clock().nanoTime()),
                        Math.max(0L, frameNumbers.getAsLong()),
                        Math.max(0L, revisions.getAsLong()), null, traceEvidence));
            } catch (RuntimeException | Error ignored) {
                // Tracing is fail-soft and cannot replace input or cleanup evidence.
            }
        }
    }

    private static List<Integer> reverse(LinkedHashSet<Integer> held) {
        ArrayList<Integer> result = new ArrayList<>(held);
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    private static void close(FrameSignal.Subscription subscription) {
        if (subscription != null) {
            subscription.close();
        }
    }

    private static void close(DeadlineScheduler.Cancellation cancellation) {
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must contain 1 to 256 characters");
        }
        return value;
    }

    private enum Phase {
        NORMAL,
        CLEANING,
        TERMINAL
    }

    private record Terminal(
            TerminalOutcome outcome,
            FailureCategory failure,
            OptionalInt failureStep) {}

    private record TickTermination(
            TerminalOutcome outcome,
            FailureCategory failure) {}

    private record KeyDispatch(
            boolean success,
            boolean skipped,
            long beforeRevision,
            long beforeFrame,
            long afterRevision,
            long afterFrame,
            List<Integer> heldKeys) {
        static KeyDispatch completed(
                long beforeRevision,
                long beforeFrame,
                long afterRevision,
                long afterFrame,
                List<Integer> heldKeys) {
            return new KeyDispatch(
                    true, false, beforeRevision, beforeFrame,
                    afterRevision, afterFrame, heldKeys);
        }

        static KeyDispatch failed(
                long beforeRevision,
                long beforeFrame,
                long afterRevision,
                long afterFrame,
                List<Integer> heldKeys) {
            return new KeyDispatch(
                    false, false, beforeRevision, beforeFrame,
                    afterRevision, afterFrame, heldKeys);
        }

        static KeyDispatch skipped(
                long revision, long frame, List<Integer> heldKeys) {
            return new KeyDispatch(
                    false, true, revision, frame, revision, frame, heldKeys);
        }
    }
}
