package dev.gdx.uiharness.lwjgl3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegisteredLaunchCoordinatorTest {
    private static final MonotonicClock CLOCK = () -> 0L;

    @Test
    void aKnownCompatibleProfileReturnsReplacementScenarioResultAndReconnectIdentity() {
        var coordinator = coordinator("sample-app");

        var outcome = coordinator.restart(request("hidpi", deadline())).toCompletableFuture().join();
        var result = assertInstanceOf(RegisteredLaunchCoordinator.HandoffResult.class, outcome);

        assertEquals("process-after", result.scenario().processId());
        assertEquals("session-after", result.scenario().sessionId());
        assertEquals("reconnect-after", result.reconnectIdentity());
    }

    @Test
    void anUnknownProfileReturnsAProductionFailureOutcome() {
        var outcome = coordinator("sample-app").restart(request("unregistered", deadline()))
                .toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.HandoffFailure.UNKNOWN_PROFILE, outcome);
    }

    @Test
    void anApplicationIncompatibleProfileReturnsAProductionFailureOutcome() {
        var outcome = coordinator("other-app").restart(request("hidpi", deadline()))
                .toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.HandoffFailure.INCOMPATIBLE_APPLICATION, outcome);
    }

    @Test
    void expiredDeadlinesReturnAProductionFailureOutcome() {
        var expired = Deadline.after(CLOCK, Duration.ZERO);

        var outcome = coordinator("sample-app").restart(request("hidpi", expired))
                .toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.HandoffFailure.DEADLINE, outcome);
    }

    @Test
    void hostCancellationReturnsAProductionFailureOutcome() {
        RegisteredLaunchCoordinator coordinator = request ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.HandoffFailure.CANCELLED);

        var outcome = coordinator.restart(request("hidpi", deadline())).toCompletableFuture().join();

        assertEquals(RegisteredLaunchCoordinator.HandoffFailure.CANCELLED, outcome);
    }

    @Test
    void publicHandoffContainsOnlyTheValidatedRequestResultAndOpaqueIdentity() {
        Set<String> forbidden = Set.of(
                "command", "path", "environment", "className", "launchArguments", "arguments");

        assertFalse(componentNames(LaunchProfile.class).stream().anyMatch(forbidden::contains));
        assertFalse(componentNames(RegisteredLaunchCoordinator.HandoffResult.class).stream()
                .anyMatch(forbidden::contains));
        assertEquals(Set.of("schemaVersion", "id", "applicationId"),
                Set.copyOf(componentNames(LaunchProfile.class)));
        assertEquals(Set.of("scenario", "reconnectIdentity"),
                Set.copyOf(componentNames(RegisteredLaunchCoordinator.HandoffResult.class)));
    }

    @Test
    void handoffResultRequiresBoundedReconnectIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegisteredLaunchCoordinator.HandoffResult(scenarioResult(), " "));
        assertThrows(IllegalArgumentException.class,
                () -> new RegisteredLaunchCoordinator.HandoffResult(
                        scenarioResult(), "x".repeat(257)));
    }

    private static RegisteredLaunchCoordinator coordinator(String activeApplicationId) {
        Map<String, LaunchProfile> allowlist = Map.of(
                "hidpi", new LaunchProfile(1, "hidpi", "sample-app"));
        return request -> {
            LaunchProfile profile = allowlist.get(request.profileId());
            if (profile == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.HandoffFailure.UNKNOWN_PROFILE);
            }
            if (!profile.applicationId().equals(activeApplicationId)) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.HandoffFailure.INCOMPATIBLE_APPLICATION);
            }
            if (request.deadline().isExpired()) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.HandoffFailure.DEADLINE);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new RegisteredLaunchCoordinator.HandoffResult(
                            scenarioResult(), "reconnect-after"));
        };
    }

    private static ScenarioRequest request(String profileId, Deadline deadline) {
        return new ScenarioRequest(1, "known", 7, Map.of(), profileId, deadline);
    }

    private static ScenarioResult scenarioResult() {
        return new ScenarioResult(
                1, "known", "v1", "digest", 7, "sample-app", "process-after",
                "session-after", 1, 1, 2, 2, "hidpi", "ready", Duration.ZERO,
                1, true, Optional.empty());
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
