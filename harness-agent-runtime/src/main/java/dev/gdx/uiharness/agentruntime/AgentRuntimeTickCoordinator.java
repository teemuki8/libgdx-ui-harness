package dev.gdx.uiharness.agentruntime;

import dev.gdx.uiharness.core.gesture.ExactTickCoordinator;
import dev.gdx.uiharness.core.gesture.KeyboardGestureRequest;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.wait.FrameSignal;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ControlOperation;
import io.github.teemuki8.libgdx.agent.runtime.core.ControlStopReason;
import io.github.teemuki8.libgdx.agent.runtime.core.ExecutionEpochId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/** Exact-tick coordinator over the released agent-runtime-core 1.0.0 control API. */
public final class AgentRuntimeTickCoordinator implements ExactTickCoordinator, AutoCloseable {
    private static final int CORRELATION_PAGE_SIZE = 64;

    private final AgentRuntime runtime;
    private final String uiSessionId;
    private final long fixedDeltaNanos;
    private final FrameSignal completionFrames;
    private final DeadlineScheduler deadlines;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Object lifecycle = new Object();
    private final Set<Advance> active =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean open = true;

    /** Creates an adapter with one explicit application-configured fixed simulation delta. */
    public AgentRuntimeTickCoordinator(
            AgentRuntime runtime,
            String uiSessionId,
            long fixedDeltaNanos,
            FrameSignal completionFrames,
            DeadlineScheduler deadlines) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.uiSessionId = requireText(uiSessionId, "uiSessionId");
        this.fixedDeltaNanos = fixedDeltaNanos;
        this.completionFrames = Objects.requireNonNull(completionFrames, "completionFrames");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
    }

    /** Checks current released-runtime control state without submitting application work. */
    @Override public TickPreflight preflight(int ticks, Deadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (ticks < 1 || ticks > KeyboardGestureRequest.MAX_WAIT) {
            return rejected(TickFailureCategory.LIMIT_EXCEEDED, "reason", "tick-bound");
        }
        synchronized (lifecycle) {
            if (!open) {
                return rejected(TickFailureCategory.INVALID_STATE, "reason", "closed");
            }
        }
        if (deadline.isExpired()) {
            return rejected(TickFailureCategory.TIMED_OUT, "reason", "deadline");
        }
        if (runtime.commands().isEmpty() || !runtime.controls().available()) {
            return rejected(
                    TickFailureCategory.UNSUPPORTED_CAPABILITY, "reason", "control-unavailable");
        }
        long maximumDelta = runtime.controls().limits().maximumDeltaNanos();
        if (fixedDeltaNanos <= 0 || fixedDeltaNanos > maximumDelta) {
            return rejected(TickFailureCategory.INVALID_STATE, "reason", "fixed-delta");
        }
        if (!runtime.controls().paused()) {
            return rejected(TickFailureCategory.INVALID_STATE, "reason", "not-paused");
        }
        int maximumTicks = Math.min(
                KeyboardGestureRequest.MAX_WAIT,
                runtime.controls().limits().ticksPerOperation());
        if (ticks > maximumTicks) {
            return rejected(TickFailureCategory.LIMIT_EXCEEDED, "reason", "provider-tick-bound");
        }
        return new TickPreflight.Ready(maximumTicks);
    }

    /** Submits or observes one idempotently correlated exact runtime control operation. */
    @Override public CompletionStage<TickAdvanceResult> advance(int ticks, Deadline deadline) {
        TickPreflight preflight = preflight(ticks, deadline);
        if (preflight instanceof TickPreflight.Rejected rejected) {
            return CompletableFuture.completedFuture(
                    new TickAdvanceResult.Failed(rejected.failure()));
        }
        Advance operation = new Advance(
                "ui-gesture-" + requestSequence.incrementAndGet(), ticks, deadline);
        synchronized (lifecycle) {
            if (!open) {
                return CompletableFuture.completedFuture(new TickAdvanceResult.Failed(
                        failure(TickFailureCategory.INVALID_STATE, "reason", "closed")));
            }
            active.add(operation);
        }
        operation.start();
        return operation;
    }

    private void finished(Advance operation) {
        synchronized (lifecycle) {
            active.remove(operation);
        }
    }

    /** Cancels retained operations and rejects future advancement without closing the runtime. */
    @Override public void close() {
        List<Advance> operations;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            operations = new ArrayList<>(active);
        }
        operations.forEach(operation -> operation.cancel(false));
    }

    private final class Advance extends CompletableFuture<TickAdvanceResult>
            implements FrameSignal.FrameListener {
        private final String controlRequestId;
        private final int requestedTicks;
        private final Deadline deadline;
        private final ExecutionEpochId startEpoch;
        private final long startTick;
        private FrameSignal.Subscription frameSubscription;
        private DeadlineScheduler.Cancellation deadlineCancellation;
        private boolean finished;
        private boolean polling;
        private boolean closeFrameWhenAttached;
        private boolean cancelDeadlineWhenAttached;

        Advance(String controlRequestId, int requestedTicks, Deadline deadline) {
            this.controlRequestId = controlRequestId;
            this.requestedTicks = requestedTicks;
            this.deadline = deadline;
            startEpoch = runtime.currentEpoch();
            startTick = runtime.controls().currentTick();
        }

        void start() {
            if (poll()) {
                return;
            }
            FrameSignal.Subscription subscription = completionFrames.subscribe(this);
            attachFrameSubscription(subscription);
            DeadlineScheduler.Cancellation cancellation = deadlines.schedule(
                    deadline.remaining(), this::deadlineReached);
            attachDeadlineCancellation(cancellation);
            poll();
        }

        @Override public void onFrame(FrameSignal.Frame frame) {
            poll();
        }

        @Override public void onClosed() {
            completeResult(new TickAdvanceResult.Failed(
                    failure(TickFailureCategory.INVALID_STATE,
                            "reason", "frame-source-closed")));
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (this) {
                if (finished) {
                    return false;
                }
            }
            runtime.commands().ifPresent(commands -> commands.cancel(controlRequestId));
            completeResult(new TickAdvanceResult.Failed(
                    failure(TickFailureCategory.CANCELLED, "reason", "cancelled")));
            return false;
        }

        private boolean poll() {
            synchronized (this) {
                if (finished || polling) {
                    return finished;
                }
                polling = true;
            }
            try {
                if (!runtime.currentEpoch().equals(startEpoch)) {
                    completeResult(new TickAdvanceResult.Failed(
                            failure(TickFailureCategory.EPOCH_CHANGED,
                                    "reason", "execution-epoch")));
                    return true;
                }
                if (deadline.isExpired()) {
                    deadlineReached();
                    return true;
                }
                ControlOperation operation;
                try {
                    operation = runtime.controls().advance(
                            controlRequestId, requestedTicks,
                            fixedDeltaNanos, deadline.remaining());
                } catch (RuntimeException failure) {
                    completeResult(new TickAdvanceResult.Failed(
                            AgentRuntimeTickCoordinator.failure(
                                    TickFailureCategory.INTERNAL_FAILURE,
                                    "phase", "advance")));
                    return true;
                }
                if (!runtime.currentEpoch().equals(startEpoch)) {
                    completeResult(new TickAdvanceResult.Failed(
                            failure(TickFailureCategory.EPOCH_CHANGED,
                                    "reason", "execution-epoch")));
                    return true;
                }
                if (operation.stopReason() == ControlStopReason.PENDING) {
                    return false;
                }
                if (operation.stopReason() != ControlStopReason.COMPLETED) {
                    completeResult(new TickAdvanceResult.Failed(
                            failure(map(operation.stopReason()),
                                    "stopReason", operation.stopReason().name())));
                    return true;
                }
                if (operation.requestedTicks() != requestedTicks
                        || operation.completedTicks() != requestedTicks
                        || operation.firstFrameId().isEmpty()
                        || operation.finalFrameId().isEmpty()) {
                    completeResult(new TickAdvanceResult.Failed(
                            failure(TickFailureCategory.INTERNAL_FAILURE,
                                    "reason", "incomplete-evidence")));
                    return true;
                }
                long finalTick = runtime.controls().currentTick();
                if (finalTick != Math.addExact(startTick, requestedTicks)) {
                    completeResult(new TickAdvanceResult.Failed(
                            failure(TickFailureCategory.INTERNAL_FAILURE,
                                    "reason", "tick-mismatch")));
                    return true;
                }
                FrameId firstRuntime = operation.firstFrameId().orElseThrow();
                FrameId finalRuntime = operation.finalFrameId().orElseThrow();
                OptionalLong firstUi = correlatedUiFrame(startEpoch, firstRuntime);
                OptionalLong finalUi = correlatedUiFrame(startEpoch, finalRuntime);
                if (firstUi.isPresent() != finalUi.isPresent()) {
                    firstUi = OptionalLong.empty();
                    finalUi = OptionalLong.empty();
                }
                completeResult(new TickAdvanceResult.Completed(new TickEvidence(
                        requestedTicks, operation.completedTicks(), startTick, finalTick,
                        startEpoch.value(),
                        OptionalLong.of(firstRuntime.value()),
                        OptionalLong.of(finalRuntime.value()),
                        firstUi, finalUi, fixedDeltaNanos)));
                return true;
            } catch (ArithmeticException overflow) {
                completeResult(new TickAdvanceResult.Failed(
                        failure(TickFailureCategory.INTERNAL_FAILURE,
                                "reason", "tick-overflow")));
                return true;
            } finally {
                synchronized (this) {
                    polling = false;
                }
            }
        }

        private void deadlineReached() {
            runtime.commands().ifPresent(commands -> commands.cancel(controlRequestId));
            completeResult(new TickAdvanceResult.Failed(
                    failure(TickFailureCategory.TIMED_OUT, "reason", "deadline")));
        }

        private void attachFrameSubscription(FrameSignal.Subscription subscription) {
            boolean close;
            synchronized (this) {
                close = finished || closeFrameWhenAttached;
                if (!close) {
                    frameSubscription = subscription;
                }
            }
            if (close) {
                subscription.close();
            }
        }

        private void attachDeadlineCancellation(
                DeadlineScheduler.Cancellation cancellation) {
            boolean cancel;
            synchronized (this) {
                cancel = finished || cancelDeadlineWhenAttached;
                if (!cancel) {
                    deadlineCancellation = cancellation;
                }
            }
            if (cancel) {
                cancellation.cancel();
            }
        }

        private void completeResult(TickAdvanceResult result) {
            FrameSignal.Subscription subscription;
            DeadlineScheduler.Cancellation cancellation;
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                subscription = frameSubscription;
                frameSubscription = null;
                closeFrameWhenAttached = subscription == null;
                cancellation = deadlineCancellation;
                deadlineCancellation = null;
                cancelDeadlineWhenAttached = cancellation == null;
            }
            if (result instanceof TickAdvanceResult.Failed) {
                runtime.commands().ifPresent(commands -> commands.cancel(controlRequestId));
            }
            if (subscription != null) {
                subscription.close();
            }
            if (cancellation != null) {
                cancellation.cancel();
            }
            finished(this);
            super.complete(result);
        }
    }

    private OptionalLong correlatedUiFrame(ExecutionEpochId epoch, FrameId runtimeFrame) {
        int limit = Math.min(
                CORRELATION_PAGE_SIZE,
                runtime.uiCorrelations().limits().queryResults());
        return runtime.uiCorrelations().framesForUiSession(uiSessionId, limit).items().stream()
                .filter(correlation -> correlation.runtimeEpochId().equals(epoch))
                .filter(correlation -> correlation.runtimeFrameId().equals(runtimeFrame))
                .map(UiFrameCorrelation::uiFrameId)
                .flatMap(java.util.Optional::stream)
                .map(AgentRuntimeTickCoordinator::parseNonNegativeLong)
                .filter(OptionalLong::isPresent)
                .mapToLong(OptionalLong::orElseThrow)
                .findFirst();
    }

    private static OptionalLong parseNonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? OptionalLong.empty() : OptionalLong.of(parsed);
        } catch (NumberFormatException invalid) {
            return OptionalLong.empty();
        }
    }

    private static TickFailureCategory map(ControlStopReason reason) {
        return switch (reason) {
            case TIMED_OUT -> TickFailureCategory.TIMED_OUT;
            case CALLBACK_FAILED -> TickFailureCategory.CALLBACK_FAILED;
            case INVALID_STATE -> TickFailureCategory.INVALID_STATE;
            case TICK_LIMIT -> TickFailureCategory.LIMIT_EXCEEDED;
            case PENDING, COMPLETED, CONDITION_SATISFIED, ASSERTION_SATISFIED ->
                    TickFailureCategory.INTERNAL_FAILURE;
        };
    }

    private static TickPreflight.Rejected rejected(
            TickFailureCategory category, String key, String value) {
        return new TickPreflight.Rejected(failure(category, key, value));
    }

    private static TickFailure failure(
            TickFailureCategory category, String key, String value) {
        return new TickFailure(category, Map.of(key, value));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must contain 1 to 256 characters");
        }
        return value;
    }
}
