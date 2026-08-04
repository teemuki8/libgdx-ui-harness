package dev.gdx.uiharness.core.scenario;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable terminal evidence from one scenario start. */
public record ScenarioResult(
        int schemaVersion,
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
        schemaVersion = ScenarioDefinition.supportedSchemaVersion(schemaVersion);
        scenarioId = ScenarioDefinition.identifier(scenarioId, "scenarioId");
        definitionVersion = ScenarioDefinition.text(definitionVersion, "definitionVersion");
        configurationDigest = ScenarioDefinition.text(configurationDigest, "configurationDigest");
        applicationId = ScenarioDefinition.identifier(applicationId, "applicationId");
        processId = ScenarioDefinition.identifier(processId, "processId");
        sessionId = ScenarioDefinition.identifier(sessionId, "sessionId");
        profileId = ScenarioDefinition.identifier(profileId, "profileId");
        startStateIdentity = ScenarioDefinition.text(startStateIdentity, "startStateIdentity");
        elapsed = ScenarioDefinition.duration(elapsed, "elapsed", true);
        if (setupAttempts < 0 || setupAttempts > ScenarioDefinition.MAX_SETUP_ATTEMPTS) {
            throw new IllegalArgumentException("setupAttempts must be between 0 and 16");
        }
        failure = Objects.requireNonNull(failure, "failure");
    }
}
