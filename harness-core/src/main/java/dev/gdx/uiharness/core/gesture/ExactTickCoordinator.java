package dev.gdx.uiharness.core.gesture;

import dev.gdx.uiharness.core.time.Deadline;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;

/** Provider-neutral boundary for exact application-owned controlled simulation advancement. */
public interface ExactTickCoordinator {
    /** Checks current controller state and bounds without advancing simulation. */
    TickPreflight preflight(int ticks, Deadline deadline);

    /** Advances exactly the validated positive tick count before the monotonic deadline. */
    CompletionStage<TickAdvanceResult> advance(int ticks, Deadline deadline);

    /** Closed preflight outcome. */
    sealed interface TickPreflight permits TickPreflight.Ready, TickPreflight.Rejected {
        /** Ready state and the provider's current hard tick ceiling. */
        record Ready(int maximumTicks) implements TickPreflight {
            /** Validates the positive ceiling against the harness bound. */
            public Ready {
                requireTickCount(maximumTicks, "maximumTicks");
            }
        }

        /** Closed rejection that proves no tick was requested. */
        record Rejected(TickFailure failure) implements TickPreflight {
            /** Requires one bounded failure. */
            public Rejected {
                Objects.requireNonNull(failure, "failure");
            }
        }
    }

    /** Closed exact-advance outcome. */
    sealed interface TickAdvanceResult
            permits TickAdvanceResult.Completed, TickAdvanceResult.Failed {
        /** Exact completed advancement evidence. */
        record Completed(TickEvidence evidence) implements TickAdvanceResult {
            /** Requires complete evidence. */
            public Completed {
                Objects.requireNonNull(evidence, "evidence");
            }
        }

        /** Bounded failure without guessed tick evidence. */
        record Failed(TickFailure failure) implements TickAdvanceResult {
            /** Requires one bounded failure. */
            public Failed {
                Objects.requireNonNull(failure, "failure");
            }
        }
    }

    /** Exact controlled-tick and optional proven frame correlation evidence. */
    record TickEvidence(
            int requestedTicks,
            int completedTicks,
            long startTick,
            long finalTick,
            long executionEpoch,
            OptionalLong firstRuntimeFrame,
            OptionalLong finalRuntimeFrame,
            OptionalLong firstUiFrame,
            OptionalLong finalUiFrame,
            long configuredDeltaNanos) {
        /** Validates exact completion, one epoch, positive delta, and optional frame pairs. */
        public TickEvidence {
            requireTickCount(requestedTicks, "requestedTicks");
            if (completedTicks != requestedTicks) {
                throw new IllegalArgumentException(
                        "completedTicks must equal requestedTicks");
            }
            requireNonNegative(startTick, "startTick");
            requireNonNegative(finalTick, "finalTick");
            requireNonNegative(executionEpoch, "executionEpoch");
            long expectedFinal = addTicks(startTick, completedTicks);
            if (finalTick != expectedFinal) {
                throw new IllegalArgumentException(
                        "finalTick must equal startTick plus completedTicks");
            }
            firstRuntimeFrame = requireOptional(firstRuntimeFrame, "firstRuntimeFrame");
            finalRuntimeFrame = requireOptional(finalRuntimeFrame, "finalRuntimeFrame");
            firstUiFrame = requireOptional(firstUiFrame, "firstUiFrame");
            finalUiFrame = requireOptional(finalUiFrame, "finalUiFrame");
            requirePair(firstRuntimeFrame, finalRuntimeFrame, "runtime frames");
            requirePair(firstUiFrame, finalUiFrame, "UI frames");
            if (configuredDeltaNanos <= 0) {
                throw new IllegalArgumentException("configuredDeltaNanos must be positive");
            }
        }
    }

    /** Bounded provider failure with a stable closed category. */
    record TickFailure(TickFailureCategory category, Map<String, String> evidence) {
        private static final int MAX_EVIDENCE_ENTRIES = 16;
        private static final int MAX_TEXT_LENGTH = 512;

        /** Validates, bounds, and defensively copies safe provider evidence. */
        public TickFailure {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(evidence, "evidence");
            if (evidence.size() > MAX_EVIDENCE_ENTRIES) {
                throw new IllegalArgumentException(
                        "tick failure evidence exceeds " + MAX_EVIDENCE_ENTRIES + " entries");
            }
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            evidence.forEach((key, value) -> copy.put(
                    requireText(key, "evidence key"),
                    requireText(value, "evidence value")));
            evidence = java.util.Collections.unmodifiableMap(copy);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                        name + " must contain 1 to " + MAX_TEXT_LENGTH + " characters");
            }
            return value;
        }
    }

    /** Stable provider failure categories. */
    enum TickFailureCategory {
        /** No exact controlled-tick provider is installed. */
        UNSUPPORTED_CAPABILITY,
        /** Current controller or pause state cannot execute ticks. */
        INVALID_STATE,
        /** Provider or harness tick bounds reject the request. */
        LIMIT_EXCEEDED,
        /** Advancement exceeded its monotonic deadline. */
        TIMED_OUT,
        /** The application-owned controlled-tick callback failed. */
        CALLBACK_FAILED,
        /** The runtime execution epoch changed during advancement. */
        EPOCH_CHANGED,
        /** Advancement was cancelled before exact completion. */
        CANCELLED,
        /** A bounded provider-internal failure prevented exact evidence. */
        INTERNAL_FAILURE
    }

    private static void requireTickCount(int ticks, String name) {
        if (ticks < 1 || ticks > KeyboardGestureRequest.MAX_WAIT) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + KeyboardGestureRequest.MAX_WAIT);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static long addTicks(long startTick, int completedTicks) {
        try {
            return Math.addExact(startTick, completedTicks);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("tick range overflow", overflow);
        }
    }

    private static OptionalLong requireOptional(OptionalLong value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isPresent()) {
            requireNonNegative(value.orElseThrow(), name);
        }
        return value;
    }

    private static void requirePair(
            OptionalLong first, OptionalLong last, String name) {
        if (first.isPresent() != last.isPresent()) {
            throw new IllegalArgumentException(name + " must be both present or both absent");
        }
        if (first.isPresent() && last.orElseThrow() < first.orElseThrow()) {
            throw new IllegalArgumentException(name + " must be ordered");
        }
    }
}
