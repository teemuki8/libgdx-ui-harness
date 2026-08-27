package dev.gdx.uiharness.core.gesture;

import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickEvidence;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable bounded terminal evidence for one atomic keyboard gesture. */
public record KeyboardGestureResult(
        int schemaVersion,
        TerminalOutcome outcome,
        int requestedSteps,
        int startedSteps,
        int completedSteps,
        long startRevision,
        long startFrame,
        long endRevision,
        long endFrame,
        long elapsedNanos,
        List<StepEvidence> steps,
        OptionalInt failureStep,
        Optional<FailureCategory> failure,
        List<Integer> heldKeys,
        CleanupStatus cleanupStatus,
        List<CleanupAttempt> cleanup,
        Optional<String> traceId) {
    /** Validates cross-field terminal invariants and defensively copies all evidence. */
    public KeyboardGestureResult {
        KeyboardGestureRequest.maximumSteps(schemaVersion);
        Objects.requireNonNull(outcome, "outcome");
        if (requestedSteps < 2
                || requestedSteps > KeyboardGestureRequest.MAX_STEPS_V2) {
            throw new IllegalArgumentException("requestedSteps is outside the gesture bound");
        }
        if (startedSteps < 0 || startedSteps > requestedSteps) {
            throw new IllegalArgumentException("startedSteps is outside the requested range");
        }
        if (completedSteps < 0 || completedSteps > startedSteps) {
            throw new IllegalArgumentException("completedSteps is outside the started range");
        }
        requireNonNegative(startRevision, "startRevision");
        requireNonNegative(startFrame, "startFrame");
        requireNonNegative(endRevision, "endRevision");
        requireNonNegative(endFrame, "endFrame");
        if (endRevision < startRevision || endFrame < startFrame) {
            throw new IllegalArgumentException("terminal revision and frame must not go backwards");
        }
        requireNonNegative(elapsedNanos, "elapsedNanos");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.size() != startedSteps) {
            throw new IllegalArgumentException("step evidence count must equal startedSteps");
        }
        int observedCompleted = 0;
        for (int index = 0; index < steps.size(); index++) {
            StepEvidence step = steps.get(index);
            if (step.index() != index) {
                throw new IllegalArgumentException("step evidence indices must be contiguous");
            }
            if (step.status() == StepStatus.COMPLETED) {
                observedCompleted++;
            }
        }
        if (observedCompleted != completedSteps) {
            throw new IllegalArgumentException(
                    "completedSteps must equal completed step evidence");
        }
        failureStep = Objects.requireNonNull(failureStep, "failureStep");
        if (failureStep.isPresent()
                && (failureStep.getAsInt() < 0 || failureStep.getAsInt() >= requestedSteps)) {
            throw new IllegalArgumentException("failureStep is outside the request");
        }
        failure = Objects.requireNonNull(failure, "failure");
        heldKeys = copyHeldKeys(heldKeys);
        Objects.requireNonNull(cleanupStatus, "cleanupStatus");
        cleanup = List.copyOf(Objects.requireNonNull(cleanup, "cleanup"));
        if (cleanup.size() > KeyboardGestureRequest.MAX_HELD_KEYS) {
            throw new IllegalArgumentException("cleanup evidence exceeds held-key bound");
        }
        validateCleanup(cleanupStatus, cleanup);
        traceId = copyTraceId(traceId);
        validateOutcome(outcome, requestedSteps, startedSteps, completedSteps,
                failureStep, failure, heldKeys, cleanupStatus, cleanup);
    }

    private static void validateOutcome(
            TerminalOutcome outcome,
            int requestedSteps,
            int startedSteps,
            int completedSteps,
            OptionalInt failureStep,
            Optional<FailureCategory> failure,
            List<Integer> heldKeys,
            CleanupStatus cleanupStatus,
            List<CleanupAttempt> cleanup) {
        if (outcome == TerminalOutcome.COMPLETED) {
            if (startedSteps != requestedSteps || completedSteps != requestedSteps
                    || failureStep.isPresent() || failure.isPresent()
                    || !heldKeys.isEmpty() || cleanupStatus != CleanupStatus.NOT_REQUIRED
                    || !cleanup.isEmpty()) {
                throw new IllegalArgumentException("completed outcome contains failure evidence");
            }
        } else if (failure.isEmpty()) {
            throw new IllegalArgumentException("non-completed outcome requires a failure category");
        }
    }

    private static void validateCleanup(
            CleanupStatus status, List<CleanupAttempt> attempts) {
        if (status == CleanupStatus.NOT_REQUIRED && !attempts.isEmpty()) {
            throw new IllegalArgumentException("not-required cleanup must have no attempts");
        }
        if (status == CleanupStatus.FAILED
                && attempts.stream().allMatch(attempt ->
                        attempt.status() == CleanupAttemptStatus.RELEASED)) {
            throw new IllegalArgumentException("failed cleanup requires a failed attempt");
        }
        if (status == CleanupStatus.COMPLETED
                && attempts.stream().anyMatch(attempt ->
                        attempt.status() != CleanupAttemptStatus.RELEASED)) {
            throw new IllegalArgumentException("completed cleanup contains a failed attempt");
        }
    }

    private static Optional<String> copyTraceId(Optional<String> traceId) {
        Objects.requireNonNull(traceId, "traceId");
        if (traceId.isPresent()) {
            String value = Objects.requireNonNull(traceId.orElseThrow(), "traceId value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "traceId must contain between 1 and 256 characters");
            }
        }
        return traceId;
    }

    private static List<Integer> copyHeldKeys(List<Integer> source) {
        List<Integer> copy = List.copyOf(Objects.requireNonNull(source, "heldKeys"));
        if (copy.size() > KeyboardGestureRequest.MAX_HELD_KEYS
                || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("heldKeys exceeds bounds or contains duplicates");
        }
        for (int keycode : copy) {
            requireKeycode(keycode);
        }
        return copy;
    }

    /** Evidence for one started request step. */
    public record StepEvidence(
            int index,
            StepKind kind,
            StepStatus status,
            OptionalInt keycode,
            OptionalInt count,
            long beforeRevision,
            long beforeFrame,
            long afterRevision,
            long afterFrame,
            List<Integer> heldKeys,
            Optional<TickEvidence> tick) {
        /** Validates the closed shape for the selected step kind. */
        public StepEvidence {
            if (index < 0 || index >= KeyboardGestureRequest.MAX_STEPS_V2) {
                throw new IllegalArgumentException("step index is outside the gesture bound");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            keycode = Objects.requireNonNull(keycode, "keycode");
            count = Objects.requireNonNull(count, "count");
            requireNonNegative(beforeRevision, "beforeRevision");
            requireNonNegative(beforeFrame, "beforeFrame");
            requireNonNegative(afterRevision, "afterRevision");
            requireNonNegative(afterFrame, "afterFrame");
            if (afterRevision < beforeRevision || afterFrame < beforeFrame) {
                throw new IllegalArgumentException("step evidence must not go backwards");
            }
            heldKeys = copyHeldKeys(heldKeys);
            tick = Objects.requireNonNull(tick, "tick");
            validateStepShape(kind, status, keycode, count, tick);
        }
    }

    private static void validateStepShape(
            StepKind kind,
            StepStatus status,
            OptionalInt keycode,
            OptionalInt count,
            Optional<TickEvidence> tick) {
        switch (kind) {
            case KEY_DOWN, KEY_UP -> {
                if (keycode.isEmpty() || count.isPresent() || tick.isPresent()) {
                    throw new IllegalArgumentException("key step has invalid evidence shape");
                }
                requireKeycode(keycode.getAsInt());
            }
            case WAIT_FRAMES -> {
                if (keycode.isPresent() || count.isEmpty() || tick.isPresent()) {
                    throw new IllegalArgumentException("frame wait has invalid evidence shape");
                }
                requireCount(count.getAsInt());
            }
            case WAIT_TICKS -> {
                if (keycode.isPresent() || count.isEmpty()
                        || (status == StepStatus.COMPLETED && tick.isEmpty())) {
                    throw new IllegalArgumentException("tick wait has invalid evidence shape");
                }
                requireCount(count.getAsInt());
                if (tick.isPresent() && tick.orElseThrow().requestedTicks() != count.getAsInt()) {
                    throw new IllegalArgumentException("tick evidence count does not match step");
                }
            }
        }
    }

    /** One reverse-order release attempt made after abnormal termination. */
    public record CleanupAttempt(int keycode, CleanupAttemptStatus status) {
        /** Validates the keycode and closed attempt status. */
        public CleanupAttempt {
            requireKeycode(keycode);
            Objects.requireNonNull(status, "status");
        }
    }

    /** Closed request-step kinds. */
    public enum StepKind {
        /** Key-down callback. */
        KEY_DOWN,
        /** Completed-frame observation. */
        WAIT_FRAMES,
        /** Exact controlled-tick advancement. */
        WAIT_TICKS,
        /** Key-up callback. */
        KEY_UP
    }

    /** Closed status of one started step. */
    public enum StepStatus {
        /** Step reached its requested terminal state. */
        COMPLETED,
        /** Step terminated the gesture before completion. */
        FAILED
    }

    /** Closed externally visible terminal outcomes. */
    public enum TerminalOutcome {
        /** Every requested step completed and no key remains held. */
        COMPLETED,
        /** Preflight rejected the gesture before input. */
        REJECTED,
        /** Execution or cleanup failed. */
        FAILED,
        /** Normal execution exceeded its deadline. */
        TIMED_OUT,
        /** Caller cancellation completed cleanup. */
        CANCELLED,
        /** Session shutdown completed cleanup. */
        SESSION_CLOSED
    }

    /** Closed primary gesture failure categories. */
    public enum FailureCategory {
        /** Request contents failed validation. */
        INVALID_REQUEST,
        /** Exact controlled-tick support is unavailable. */
        UNSUPPORTED_TICK_CAPABILITY,
        /** Current runtime control state cannot advance. */
        INVALID_RUNTIME_STATE,
        /** Another gesture owns the session lease. */
        SESSION_BUSY,
        /** A configured input callback failed. */
        KEY_DISPATCH_FAILURE,
        /** The completed-frame source closed during a wait. */
        FRAME_SOURCE_CLOSED,
        /** Exact controlled tick advancement failed. */
        TICK_ADVANCE_FAILURE,
        /** Runtime execution epoch changed during tick advancement. */
        EPOCH_CHANGED,
        /** The request exceeded its monotonic deadline. */
        TIMEOUT,
        /** Caller cancelled the request. */
        CANCELLED,
        /** Session shutdown cancelled the request. */
        SESSION_CLOSED,
        /** One or more abnormal release attempts failed. */
        CLEANUP_FAILURE
    }

    /** Closed aggregate cleanup status. */
    public enum CleanupStatus {
        /** No abnormal release was required. */
        NOT_REQUIRED,
        /** Every required abnormal release callback returned successfully. */
        COMPLETED,
        /** At least one required release could not be delivered successfully. */
        FAILED
    }

    /** Closed status of one abnormal release attempt. */
    public enum CleanupAttemptStatus {
        /** Key-up callback returned successfully. */
        RELEASED,
        /** Key-up callback threw. */
        DISPATCH_FAILED,
        /** Fresh cleanup deadline elapsed before dispatch. */
        DEADLINE_EXCEEDED,
        /** Render-thread scheduler rejected the release. */
        SCHEDULER_REJECTED
    }

    private static void requireKeycode(int keycode) {
        if (keycode < 0 || keycode > KeyboardGestureRequest.MAX_KEYCODE) {
            throw new IllegalArgumentException("keycode is outside the gesture bound");
        }
    }

    private static void requireCount(int count) {
        if (count < 1 || count > KeyboardGestureRequest.MAX_WAIT) {
            throw new IllegalArgumentException("count is outside the gesture wait bound");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
