package dev.gdx.uiharness.core.scenario;

import dev.gdx.uiharness.core.time.Deadline;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable caller inputs for one scenario start attempt. */
public record ScenarioRequest(
        String scenarioId,
        long seed,
        Map<String, String> configuration,
        String profileId,
        Deadline deadline) {
    public ScenarioRequest {
        scenarioId = ScenarioDefinition.identifier(scenarioId, "scenarioId");
        profileId = ScenarioDefinition.identifier(profileId, "profileId");
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.size() > ScenarioDefinition.MAX_ENTRIES) {
            throw new IllegalArgumentException("configuration exceeds 256 entries");
        }
        var copy = new LinkedHashMap<String, String>(configuration.size());
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            copy.put(
                    ScenarioDefinition.identifier(entry.getKey(), "configuration key"),
                    ScenarioDefinition.text(entry.getValue(), "configuration value"));
        }
        configuration = Collections.unmodifiableMap(copy);
        Objects.requireNonNull(deadline, "deadline");
    }
}
