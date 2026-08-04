package dev.gdx.uiharness.lwjgl3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegisteredLaunchCoordinatorTest {
    private static final MonotonicClock CLOCK = () -> 0L;

    @Test
    void aKnownCompatibleProfileReturnsReplacementIdentities() {
        var coordinator = coordinator("sample-app");

        var outcome = coordinator.restart("hidpi", deadline()).toCompletableFuture().join();
        var result = assertInstanceOf(RegisteredLaunchCoordinator.LaunchResult.class, outcome);

        assertEquals(1, result.schemaVersion());
        assertEquals("hidpi", result.profileId());
        assertEquals("sample-app", result.applicationId());
        assertEquals("process-before", result.previousProcessId());
        assertEquals("process-after", result.processId());
        assertEquals("session-before", result.previousSessionId());
        assertEquals("session-after", result.sessionId());
        assertNotEquals(result.previousProcessId(), result.processId());
        assertNotEquals(result.previousSessionId(), result.sessionId());
    }

    @Test
    void anUnknownProfileReturnsAProductionFailureOutcome() {
        var outcome = coordinator("sample-app").restart("unregistered", deadline())
                .toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.LaunchFailure.UNKNOWN_PROFILE, outcome);
    }

    @Test
    void anApplicationIncompatibleProfileReturnsAProductionFailureOutcome() {
        var outcome = coordinator("other-app").restart("hidpi", deadline())
                .toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.LaunchFailure.INCOMPATIBLE_APPLICATION, outcome);
    }

    @Test
    void expiredDeadlinesReturnAProductionFailureOutcome() {
        var expired = Deadline.after(CLOCK, Duration.ZERO);

        var outcome = coordinator("sample-app").restart("hidpi", expired)
                .toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.LaunchFailure.DEADLINE, outcome);
    }

    @Test
    void hostCancellationReturnsAProductionFailureOutcome() {
        RegisteredLaunchCoordinator coordinator = (profileId, deadline) ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.LaunchFailure.CANCELLED);

        var outcome = coordinator.restart("hidpi", deadline()).toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.LaunchFailure.CANCELLED, outcome);
    }

    @Test
    void publicRecordsContainOnlySafeIdentifiersAndTiming() {
        Set<String> forbidden = Set.of(
                "command", "path", "environment", "className", "launchArguments", "arguments");

        assertFalse(componentNames(LaunchProfile.class).stream().anyMatch(forbidden::contains));
        assertFalse(componentNames(RegisteredLaunchCoordinator.LaunchResult.class).stream()
                .anyMatch(forbidden::contains));
        assertEquals(Set.of("schemaVersion", "id", "applicationId"),
                Set.copyOf(componentNames(LaunchProfile.class)));
        assertEquals(Set.of("schemaVersion", "profileId", "applicationId", "previousProcessId",
                        "processId", "previousSessionId", "sessionId", "elapsed"),
                Set.copyOf(componentNames(RegisteredLaunchCoordinator.LaunchResult.class)));
    }

    @Test
    void profilesAndResultsEnforceVersionIdentifiersReplacementAndMaximumTiming() {
        assertThrows(IllegalArgumentException.class, () -> new LaunchProfile(2, "hidpi", "sample-app"));
        assertThrows(IllegalArgumentException.class, () -> new LaunchProfile(1, " ", "sample-app"));
        assertThrows(IllegalArgumentException.class,
                () -> result("same", "same", "session-before", "session-after", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> result("process-before", "process-after", "same", "same", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> result("process-before", "process-after", "session-before", "session-after",
                        Duration.ofMinutes(10).plusNanos(1)));
    }

    private static RegisteredLaunchCoordinator coordinator(String activeApplicationId) {
        Map<String, LaunchProfile> allowlist = Map.of(
                "hidpi", new LaunchProfile(1, "hidpi", "sample-app"));
        return (profileId, deadline) -> {
            LaunchProfile profile = allowlist.get(profileId);
            if (profile == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.LaunchFailure.UNKNOWN_PROFILE);
            }
            if (!profile.applicationId().equals(activeApplicationId)) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.LaunchFailure.INCOMPATIBLE_APPLICATION);
            }
            if (deadline.isExpired()) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.LaunchFailure.DEADLINE);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(result(
                    "process-before", "process-after", "session-before", "session-after", Duration.ZERO));
        };
    }

    private static RegisteredLaunchCoordinator.LaunchResult result(
            String previousProcessId,
            String processId,
            String previousSessionId,
            String sessionId,
            Duration elapsed) {
        return new RegisteredLaunchCoordinator.LaunchResult(
                1, "hidpi", "sample-app", previousProcessId, processId,
                previousSessionId, sessionId, elapsed);
    }

    private static Deadline deadline() {
        return Deadline.after(CLOCK, Duration.ofSeconds(1));
    }

    private static Set<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
    }
}
