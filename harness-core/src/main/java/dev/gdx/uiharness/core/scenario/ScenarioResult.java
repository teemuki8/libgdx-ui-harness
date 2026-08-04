package dev.gdx.uiharness.core.scenario;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable terminal evidence from one scenario start. */
public record ScenarioResult(
        String scenarioId,
        String definitionVersion,
        String configurationDigest,
        long seed,
        String applicationId,
        String processId,
        String sessionId,
        long startFrame,
        long startRevision,
        long readyFrame,
        long readyRevision,
        String profileId,
        String startStateIdentity,
        Duration elapsed,
        int setupAttempts,
        boolean cleanupCompleted,
        Optional<ScenarioFailure> failure) {
    public ScenarioResult {
        scenarioId = ScenarioDefinition.identifier(scenarioId, "scenarioId");
        definitionVersion = ScenarioDefinition.text(definitionVersion, "definitionVersion");
        configurationDigest = ScenarioDefinition.text(configurationDigest, "configurationDigest");
        applicationId = ScenarioDefinition.identifier(applicationId, "applicationId");
        processId = ScenarioDefinition.identifier(processId, "processId");
        sessionId = ScenarioDefinition.identifier(sessionId, "sessionId");
        profileId = ScenarioDefinition.identifier(profileId, "profileId");
        startStateIdentity = ScenarioDefinition.text(startStateIdentity, "startStateIdentity");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
        if (setupAttempts < 0 || setupAttempts > ScenarioDefinition.MAX_SETUP_ATTEMPTS) {
            throw new IllegalArgumentException("setupAttempts must be between 0 and 16");
        }
        failure = Objects.requireNonNull(failure, "failure");
    }
}
