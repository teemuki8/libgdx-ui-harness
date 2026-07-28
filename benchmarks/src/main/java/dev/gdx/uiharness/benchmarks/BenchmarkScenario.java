package dev.gdx.uiharness.benchmarks;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One ordered, data-defined semantic benchmark scenario shared by both systems. */
public record BenchmarkScenario(
        String id,
        String description,
        int logicalDelayMillis,
        List<Step> steps,
        String expected) {
    private static final int FIXED_STEP_MILLIS = 16;
    private static final Set<String> ACTIONS = Set.of(
            "fill", "click", "wait-visible", "scroll", "select", "screenshot",
            "expect-click-failure");

    /** Validates scenario data and makes its ordered steps immutable. */
    public BenchmarkScenario {
        id = requireText("id", id);
        description = requireText("description", description);
        expected = requireText("expected", expected);
        if (logicalDelayMillis <= 0 || logicalDelayMillis % FIXED_STEP_MILLIS != 0) {
            throw new IllegalArgumentException(
                    "logicalDelayMillis must be a positive multiple of 16");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
    }

    /** Strictly parses a schema-versioned corpus while preserving JSON array order. */
    public static List<BenchmarkScenario> parse(Path path) {
        Objects.requireNonNull(path, "path");
        ObjectMapper mapper = ProtocolJson.mapper().copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        final Corpus corpus;
        try {
            corpus = mapper.readValue(path.toFile(), Corpus.class);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Malformed benchmark corpus: " + path, failure);
        }
        if (corpus.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported corpus schemaVersion");
        }
        List<BenchmarkScenario> scenarios = List.copyOf(corpus.scenarios());
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("corpus scenarios must not be empty");
        }
        HashSet<String> ids = new HashSet<>();
        for (BenchmarkScenario scenario : scenarios) {
            if (!ids.add(scenario.id())) {
                throw new IllegalArgumentException("Duplicate scenario id: " + scenario.id());
            }
        }
        return scenarios;
    }

    /** A semantic operation interpreted independently by the harness and Playwright runners. */
    public record Step(String action, Locator locator, String value, Double amountY) {
        /** Validates action shape without imposing system-specific locator syntax. */
        public Step {
            action = requireText("action", action);
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("Unsupported scenario action: " + action);
            }
            boolean locatorRequired = !"screenshot".equals(action);
            if (locatorRequired && locator == null) {
                throw new IllegalArgumentException(action + " requires a locator");
            }
            if (("fill".equals(action) || "select".equals(action))
                    && (value == null || value.isBlank())) {
                throw new IllegalArgumentException(action + " requires a value");
            }
            amountY = amountY == null ? 0.0 : amountY;
            if ("scroll".equals(action) && amountY == 0.0) {
                throw new IllegalArgumentException("scroll requires non-zero amountY");
            }
        }
    }

    /** Portable role, text, label, or test-ID locator. */
    public record Locator(String kind, String value, String name, Boolean exact) {
        /** Validates the portable locator vocabulary. */
        public Locator {
            kind = requireText("locator kind", kind);
            value = requireText("locator value", value);
            if (!Set.of("role", "text", "label", "test-id").contains(kind)) {
                throw new IllegalArgumentException("Unsupported locator kind: " + kind);
            }
            if ("role".equals(kind)) {
                name = requireText("role locator name", name);
            } else if (name != null) {
                throw new IllegalArgumentException("name is only valid for role locators");
            }
            exact = exact == null ? Boolean.TRUE : exact;
        }
    }

    private record Corpus(int schemaVersion, List<BenchmarkScenario> scenarios) {
        private Corpus {
            scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
        }
    }

    private static String requireText(String name, String value) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
