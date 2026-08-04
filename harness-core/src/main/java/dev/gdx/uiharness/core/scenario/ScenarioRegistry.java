package dev.gdx.uiharness.core.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit bounded registry of application-provided scenarios. */
public final class ScenarioRegistry {
    private static final int MAX_SCENARIOS = 256;

    private final Map<String, RegisteredScenario> scenarios = new LinkedHashMap<>();

    /** Registers one unique definition and its lifecycle hooks. */
    public void register(ScenarioDefinition definition, ScenarioLifecycle lifecycle) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (scenarios.containsKey(definition.id())) {
            throw new IllegalArgumentException("scenario already registered: " + definition.id());
        }
        if (scenarios.size() >= MAX_SCENARIOS) {
            throw new IllegalStateException("scenario registry exceeds 256 entries");
        }
        scenarios.put(definition.id(), new RegisteredScenario(definition, lifecycle));
    }

    /** Returns immutable definitions in stable identifier order. */
    public List<ScenarioDefinition> definitions() {
        var definitions = new ArrayList<ScenarioDefinition>(scenarios.size());
        for (RegisteredScenario scenario : scenarios.values()) {
            definitions.add(scenario.definition());
        }
        definitions.sort((left, right) -> left.id().compareTo(right.id()));
        return List.copyOf(definitions);
    }

    /** Returns the registered scenario or rejects an unknown identifier. */
    public RegisteredScenario require(String id) {
        ScenarioDefinition.identifier(id, "id");
        var scenario = scenarios.get(id);
        if (scenario == null) {
            throw new IllegalArgumentException("unknown scenario: " + id);
        }
        return scenario;
    }

    /** Immutable association between declared metadata and application lifecycle hooks. */
    public record RegisteredScenario(ScenarioDefinition definition, ScenarioLifecycle lifecycle) {
        public RegisteredScenario {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }
}
