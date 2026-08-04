package dev.gdx.uiharness.core.scenario;

import dev.gdx.uiharness.core.model.SemanticSnapshot;

/** Application-owned hooks for entering and identifying one deterministic start state. */
public interface ScenarioLifecycle {
    void setup(ScenarioRequest request);

    void reset(ScenarioRequest request);

    boolean ready(ScenarioRequest request);

    String startStateIdentity(ScenarioRequest request, SemanticSnapshot snapshot);

    void cleanup(ScenarioRequest request);
}
