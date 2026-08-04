package dev.gdx.uiharness.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ScenarioRegistryTest {
    @Test void definitionsAreListedInStableIdentifierOrder() {
        var registry = new ScenarioRegistry();
        registry.register(definition("z-last"), lifecycle());
        registry.register(definition("a-first"), lifecycle());

        assertEquals(List.of("a-first", "z-last"),
                registry.definitions().stream().map(ScenarioDefinition::id).toList());
    }

    @Test void duplicateAndUnknownIdentifiersAreRejected() {
        var registry = new ScenarioRegistry();
        registry.register(definition("known"), lifecycle());

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(definition("known"), lifecycle()));
        assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
    }

    @Test void registeredScenarioRetainsTheDefinitionAndLifecycle() {
        var registry = new ScenarioRegistry();
        var definition = definition("known");
        var lifecycle = lifecycle();
        registry.register(definition, lifecycle);

        var registered = registry.require("known");

        assertSame(definition, registered.definition());
        assertSame(lifecycle, registered.lifecycle());
    }

    @Test void definitionsAndRequestsDefensivelyCopyCallerCollections() {
        var profiles = new ArrayList<>(List.of("desktop"));
        var definition = definition("known", profiles);
        profiles.clear();

        var configuration = new HashMap<>(Map.of("difficulty", "hard"));
        var request = new ScenarioRequest(
                1, "known", 7L, configuration, "desktop",
                Deadline.after(() -> 0L, Duration.ofSeconds(1)));
        configuration.clear();

        assertEquals(List.of("desktop"), definition.supportedProfileIds());
        assertEquals(Map.of("difficulty", "hard"), request.configuration());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.supportedProfileIds().add("mobile"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.configuration().put("other", "value"));
    }

    @Test void configurationsHaveCanonicalKeyOrder() {
        var reverseOrder = new LinkedHashMap<String, String>();
        reverseOrder.put("z-last", "one");
        reverseOrder.put("a-first", "two");
        var forwardOrder = new LinkedHashMap<String, String>();
        forwardOrder.put("a-first", "two");
        forwardOrder.put("z-last", "one");

        var reverseRequest = request(reverseOrder, Duration.ofSeconds(1));
        var forwardRequest = request(forwardOrder, Duration.ofSeconds(1));

        assertEquals(List.of("a-first", "z-last"),
                List.copyOf(reverseRequest.configuration().keySet()));
        assertEquals(reverseRequest.configuration(), forwardRequest.configuration());
        assertEquals(
                List.copyOf(reverseRequest.configuration().entrySet()),
                List.copyOf(forwardRequest.configuration().entrySet()));
    }

    @Test void identifiersAndStringsHaveExplicitHardLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> definition("x".repeat(257)));
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDefinition(
                        1, "known", "x".repeat(16_385), "app", List.of("desktop"),
                        1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioRequest(
                        1, "known", 1L, Map.of("key", "x".repeat(16_385)), "desktop",
                        Deadline.after(() -> 0L, Duration.ofSeconds(1))));
    }

    @Test void configurationAndDefinitionCountsHaveExplicitHardLimits() {
        var configuration = new HashMap<String, String>();
        for (int index = 0; index < 257; index++) {
            configuration.put("key-" + index, "value");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioRequest(
                        1, "known", 1L, configuration, "desktop",
                        Deadline.after(() -> 0L, Duration.ofSeconds(1))));

        var registry = new ScenarioRegistry();
        for (int index = 0; index < 256; index++) {
            registry.register(definition("scenario-" + index), lifecycle());
        }
        assertThrows(IllegalStateException.class,
                () -> registry.register(definition("overflow"), lifecycle()));
    }

    @Test void profileAndSetupCountsAreBounded() {
        var profiles = new ArrayList<String>();
        for (int index = 0; index < 257; index++) {
            profiles.add("profile-" + index);
        }

        assertThrows(IllegalArgumentException.class,
                () -> definition("known", profiles));
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDefinition(
                        1, "known", "v1", "app", List.of("desktop"), 17,
                        Duration.ofSeconds(1)));
    }

    @Test void scenarioDurationsHaveAnInclusiveTenMinuteHardLimit() {
        var limit = Duration.ofMinutes(10);
        new ScenarioDefinition(
                1, "known", "v1", "app", List.of("desktop"), 1, limit);
        request(Map.of(), limit);
        result(limit);

        var overLimit = limit.plusNanos(1);
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDefinition(
                        1, "known", "v1", "app", List.of("desktop"), 1, overLimit));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of(), overLimit));
        assertThrows(IllegalArgumentException.class, () -> result(overLimit));
    }

    @Test void requestsAndResultsRejectUnsupportedSchemaVersions() {
        var deadline = Deadline.after(() -> 0L, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDefinition(
                        2, "known", "v1", "app", List.of("desktop"), 1,
                        Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioRequest(2, "known", 7L, Map.of(), "desktop", deadline));
        assertThrows(IllegalArgumentException.class,
                () -> result(2, Duration.ofSeconds(1)));
    }

    private static ScenarioDefinition definition(String id) {
        return definition(id, List.of("desktop"));
    }

    private static ScenarioDefinition definition(String id, List<String> profiles) {
        return new ScenarioDefinition(
                1, id, "v1", "fixture-app", profiles, 1, Duration.ofSeconds(5));
    }

    private static ScenarioRequest request(
            Map<String, String> configuration, Duration timeout) {
        return new ScenarioRequest(
                1, "known", 7L, configuration, "desktop",
                Deadline.after(() -> 0L, timeout));
    }

    private static ScenarioResult result(Duration elapsed) {
        return result(1, elapsed);
    }

    private static ScenarioResult result(int schemaVersion, Duration elapsed) {
        return new ScenarioResult(
                schemaVersion,
                "known",
                "v1",
                "digest",
                7L,
                "fixture-app",
                "process",
                "session",
                1L,
                1L,
                2L,
                2L,
                "desktop",
                "ready",
                elapsed,
                1,
                true,
                Optional.empty());
    }

    private static ScenarioLifecycle lifecycle() {
        return new ScenarioLifecycle() {
            @Override public void setup(ScenarioRequest request) {}

            @Override public void reset(ScenarioRequest request) {}

            @Override public boolean ready(ScenarioRequest request) {
                return true;
            }

            @Override public String startStateIdentity(
                    ScenarioRequest request, SemanticSnapshot snapshot) {
                return "ready";
            }

            @Override public void cleanup(ScenarioRequest request) {}
        };
    }
}
