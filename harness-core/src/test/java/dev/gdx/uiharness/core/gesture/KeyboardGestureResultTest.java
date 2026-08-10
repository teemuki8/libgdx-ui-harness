package dev.gdx.uiharness.core.gesture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickAdvanceResult;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickEvidence;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickFailure;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickFailureCategory;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickPreflight;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupAttempt;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupAttemptStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.FailureCategory;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepEvidence;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepKind;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.TerminalOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class KeyboardGestureResultTest {
    @Test void completedResultIsImmutableAndInternallyConsistent() {
        ArrayList<StepEvidence> source = new ArrayList<>(List.of(
                keyStep(0, StepKind.KEY_DOWN, List.of(29)),
                frameStep(1, 30, List.of(29)),
                keyStep(2, StepKind.KEY_UP, List.of())));

        KeyboardGestureResult result = new KeyboardGestureResult(
                1, TerminalOutcome.COMPLETED, 3, 3, 3,
                10, 20, 13, 50, 4_000,
                source, OptionalInt.empty(), Optional.empty(), List.of(),
                CleanupStatus.NOT_REQUIRED, List.of(), Optional.of("trace-1"));
        source.clear();

        assertEquals(3, result.steps().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.steps().add(frameStep(3, 1, List.of())));
    }

    @Test void failedResultRetainsPrimaryFailureAndCleanupEvidence() {
        KeyboardGestureResult result = new KeyboardGestureResult(
                1, TerminalOutcome.FAILED, 4, 2, 1,
                10, 20, 11, 21, 2_000,
                List.of(
                        keyStep(0, StepKind.KEY_DOWN, List.of(29)),
                        new StepEvidence(
                                1, StepKind.KEY_DOWN, StepStatus.FAILED,
                                OptionalInt.of(30), OptionalInt.empty(),
                                11, 20, 11, 20, List.of(29, 30), Optional.empty())),
                OptionalInt.of(1), Optional.of(FailureCategory.KEY_DISPATCH_FAILURE),
                List.of(30), CleanupStatus.FAILED,
                List.of(
                        new CleanupAttempt(30, CleanupAttemptStatus.DISPATCH_FAILED),
                        new CleanupAttempt(29, CleanupAttemptStatus.RELEASED)),
                Optional.empty());

        assertEquals(FailureCategory.KEY_DISPATCH_FAILURE, result.failure().orElseThrow());
        assertEquals(List.of(30), result.heldKeys());
        assertEquals(CleanupStatus.FAILED, result.cleanupStatus());
    }

    @Test void rejectsImpossibleSuccessAndFailureCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new KeyboardGestureResult(
                1, TerminalOutcome.COMPLETED, 3, 2, 2,
                0, 0, 1, 1, 1, List.of(
                        keyStep(0, StepKind.KEY_DOWN, List.of(1)),
                        keyStep(1, StepKind.KEY_UP, List.of())),
                OptionalInt.empty(), Optional.empty(), List.of(),
                CleanupStatus.NOT_REQUIRED, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new KeyboardGestureResult(
                1, TerminalOutcome.COMPLETED, 2, 2, 2,
                0, 0, 1, 1, 1, List.of(
                        keyStep(0, StepKind.KEY_DOWN, List.of(1)),
                        keyStep(1, StepKind.KEY_UP, List.of())),
                OptionalInt.empty(), Optional.empty(), List.of(1),
                CleanupStatus.FAILED,
                List.of(new CleanupAttempt(1, CleanupAttemptStatus.DISPATCH_FAILED)),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new KeyboardGestureResult(
                1, TerminalOutcome.FAILED, 2, 1, 0,
                0, 0, 0, 0, 1,
                List.of(new StepEvidence(
                        0, StepKind.KEY_DOWN, StepStatus.FAILED,
                        OptionalInt.of(1), OptionalInt.empty(),
                        0, 0, 0, 0, List.of(1), Optional.empty())),
                OptionalInt.empty(), Optional.empty(), List.of(1),
                CleanupStatus.FAILED,
                List.of(new CleanupAttempt(1, CleanupAttemptStatus.DISPATCH_FAILED)),
                Optional.empty()));
    }

    @Test void stepEvidenceEnforcesClosedShapeAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> new StepEvidence(
                0, StepKind.WAIT_FRAMES, StepStatus.COMPLETED,
                OptionalInt.of(1), OptionalInt.of(2),
                0, 0, 1, 2, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new StepEvidence(
                0, StepKind.WAIT_TICKS, StepStatus.COMPLETED,
                OptionalInt.empty(), OptionalInt.of(2),
                0, 0, 1, 2, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CleanupAttempt(
                256, CleanupAttemptStatus.RELEASED));
    }

    @Test void exactTickEvidenceRequiresOneEpochAndExactCompletion() {
        TickEvidence evidence = tickEvidence(30);
        assertEquals(30, evidence.completedTicks());
        assertThrows(IllegalArgumentException.class, () -> new TickEvidence(
                30, 29, 100, 129, 7,
                OptionalLong.of(20), OptionalLong.of(48),
                OptionalLong.empty(), OptionalLong.empty(), 16_000_000));
        assertThrows(IllegalArgumentException.class, () -> new TickEvidence(
                30, 30, 100, 130, 7,
                OptionalLong.empty(), OptionalLong.of(49),
                OptionalLong.empty(), OptionalLong.empty(), 16_000_000));
    }

    @Test void tickPreflightAndFailuresAreClosedAndBounded() {
        assertEquals(10_000, new TickPreflight.Ready(10_000).maximumTicks());
        assertThrows(IllegalArgumentException.class, () -> new TickPreflight.Ready(0));

        LinkedHashMap<String, String> mutable = new LinkedHashMap<>();
        mutable.put("state", "not-paused");
        TickFailure failure = new TickFailure(TickFailureCategory.INVALID_STATE, mutable);
        mutable.clear();
        assertEquals(Map.of("state", "not-paused"), failure.evidence());
        assertThrows(UnsupportedOperationException.class,
                () -> failure.evidence().put("new", "value"));
        assertEquals(failure, new TickPreflight.Rejected(failure).failure());
        assertEquals(failure, new TickAdvanceResult.Failed(failure).failure());

        LinkedHashMap<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            tooMany.put("key-" + index, "value");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new TickFailure(TickFailureCategory.INTERNAL_FAILURE, tooMany));
    }

    private static StepEvidence keyStep(
            int index, StepKind kind, List<Integer> heldKeys) {
        return new StepEvidence(
                index, kind, StepStatus.COMPLETED,
                OptionalInt.of(29), OptionalInt.empty(),
                10 + index, 20 + index, 11 + index, 20 + index,
                heldKeys, Optional.empty());
    }

    private static StepEvidence frameStep(
            int index, int count, List<Integer> heldKeys) {
        return new StepEvidence(
                index, StepKind.WAIT_FRAMES, StepStatus.COMPLETED,
                OptionalInt.empty(), OptionalInt.of(count),
                10 + index, 20, 11 + index, 20 + count,
                heldKeys, Optional.empty());
    }

    private static TickEvidence tickEvidence(int ticks) {
        return new TickEvidence(
                ticks, ticks, 100, 100 + ticks, 7,
                OptionalLong.of(20), OptionalLong.of(20 + ticks - 1L),
                OptionalLong.of(40), OptionalLong.of(41), 16_000_000);
    }
}
