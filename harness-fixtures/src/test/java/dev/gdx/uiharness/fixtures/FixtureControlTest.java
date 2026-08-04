package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator;
import dev.gdx.uiharness.protocol.HarnessResponse;
import org.junit.jupiter.api.Test;

final class FixtureControlTest {
    @Test
    void unknownProfileIsRejectedBeforeExecution() {
        assertRejected(RegisteredLaunchCoordinator.HandoffFailure.UNKNOWN_PROFILE,
                "unsupported-profile");
    }

    @Test
    void incompatibleApplicationIsRejectedBeforeExecution() {
        assertRejected(RegisteredLaunchCoordinator.HandoffFailure.INCOMPATIBLE_APPLICATION,
                "incompatible-scenario");
    }

    @Test
    void deadlineIsAClosedTerminalFailure() {
        assertFailed(RegisteredLaunchCoordinator.HandoffFailure.DEADLINE, "deadline");
    }

    @Test
    void cancellationIsADistinctClosedTerminalFailure() {
        assertFailed(RegisteredLaunchCoordinator.HandoffFailure.CANCELLED, "cancelled");
    }

    private static void assertRejected(
            RegisteredLaunchCoordinator.HandoffFailure failure, String reason) {
        HarnessResponse.ScenarioStartOutcome.Rejected outcome = assertInstanceOf(
                HarnessResponse.ScenarioStartOutcome.Rejected.class,
                FixtureControl.mapHandoffFailure(failure));
        assertEquals(reason, outcome.reason());
    }

    private static void assertFailed(
            RegisteredLaunchCoordinator.HandoffFailure failure, String reason) {
        HarnessResponse.ScenarioStartOutcome.Failed outcome = assertInstanceOf(
                HarnessResponse.ScenarioStartOutcome.Failed.class,
                FixtureControl.mapHandoffFailure(failure));
        assertEquals(reason, outcome.reason());
    }
}
