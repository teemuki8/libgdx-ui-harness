package benchmark.palisade.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Hidden, treatment-neutral assertions over launcher-produced state snapshots. */
public final class FunctionalContract {
    private static final String CORPUS_SCHEMA = "agentic-palisade/v1";
    private static final int MAX_EVIDENCE_CHARACTERS = 512;

    private final List<Check> checks;

    private FunctionalContract(List<Check> checks) {
        this.checks = List.copyOf(checks);
    }

    /** Builds the immutable v1 contract from the frozen public corpus. */
    public static FunctionalContract fromCorpus(JsonNode corpus) {
        Objects.requireNonNull(corpus, "corpus");
        if (!corpus.isObject() || !CORPUS_SCHEMA.equals(corpus.path("schemaVersion").textValue())
                || !corpus.path("controls").isArray() || !corpus.path("states").isArray()
                || !corpus.path("transitions").isArray()) {
            throw new IllegalArgumentException("Unsupported or malformed Palisade corpus");
        }
        JsonNode immutable = corpus.deepCopy();
        List<Check> checks = new ArrayList<>();
        addControlChecks(checks, immutable);
        addStateChecks(checks, immutable);
        addConditionalChecks(checks);
        addSeedChecks(checks);
        addTransitionChecks(checks, immutable);
        return new FunctionalContract(checks);
    }

    /** Returns stable hidden assertion IDs in deterministic evaluation order. */
    public List<String> assertionIds() {
        return checks.stream().map(Check::id).toList();
    }

    /** Evaluates every assertion independently; malformed evidence becomes failed data. */
    public Result evaluate(JsonNode evidence) {
        JsonNode safeEvidence = evidence == null ? JsonNodeFactory.instance.objectNode() : evidence;
        List<Assertion> assertions = new ArrayList<>(checks.size());
        for (Check check : checks) {
            boolean passed;
            String detail;
            try {
                passed = safeEvidence.isObject() && check.test().test(safeEvidence);
                detail = passed ? "observed" : evidenceSnippet(check.observed().apply(safeEvidence));
            } catch (RuntimeException malformed) {
                passed = false;
                detail = "malformed:" + malformed.getClass().getSimpleName();
            }
            assertions.add(new Assertion(check.id(), passed, bound(detail)));
        }
        return new Result(assertions);
    }

    private static void addControlChecks(List<Check> checks, JsonNode corpus) {
        JsonNode controls = corpus.path("controls");
        JsonNode initial = byId(corpus.path("states"), "initial");
        add(checks, "controls.order",
                evidence -> checkpoint(evidence, "initial").path("controlOrder")
                        .equals(project(controls, "id")),
                evidence -> checkpoint(evidence, "initial").path("controlOrder"));
        add(checks, "controls.kinds",
                evidence -> projectedControls(evidence, "kind")
                        .equals(project(controls, "kind")),
                evidence -> projectedControls(evidence, "kind"));
        add(checks, "controls.labels",
                evidence -> projectedControls(evidence, "label")
                        .equals(project(controls, "label")),
                evidence -> projectedControls(evidence, "label"));
        add(checks, "controls.options",
                evidence -> projectedControls(evidence, "options")
                        .equals(project(controls, "options")),
                evidence -> projectedControls(evidence, "options"));
        add(checks, "controls.defaults",
                evidence -> projectedControls(evidence, "default")
                        .equals(project(controls, "default")),
                evidence -> projectedControls(evidence, "default"));
        add(checks, "controls.focus-order",
                evidence -> checkpoint(evidence, "initial").path("focusOrder")
                        .equals(initial.path("visibleControls")),
                evidence -> checkpoint(evidence, "initial").path("focusOrder"));
        add(checks, "controls.validation",
                evidence -> projectedControls(evidence, "validation")
                        .equals(project(controls, "validation")),
                evidence -> projectedControls(evidence, "validation"));
    }

    private static void addStateChecks(List<Check> checks, JsonNode corpus) {
        JsonNode initial = byId(corpus.path("states"), "initial");
        JsonNode bottom = byId(corpus.path("states"), "bottom");
        add(checks, "state.initial.values",
                evidence -> checkpoint(evidence, "initial").path("values").equals(initial.path("values")),
                evidence -> checkpoint(evidence, "initial").path("values"));
        add(checks, "state.initial.visibility",
                evidence -> checkpoint(evidence, "initial").path("visibleControls").equals(initial.path("visibleControls")),
                evidence -> checkpoint(evidence, "initial").path("visibleControls"));
        add(checks, "state.bottom.scroll",
                evidence -> "bottom".equals(checkpoint(evidence, "bottom").path("scrollPosition").textValue())
                        && checkpoint(evidence, "bottom").path("values").equals(bottom.path("values")),
                evidence -> checkpoint(evidence, "bottom"));
    }

    private static void addConditionalChecks(List<Check> checks) {
        add(checks, "conditional.rival-target.visible", evidence -> {
            JsonNode state = checkpoint(evidence, "conditionalVisible");
            return "rival-target".equals(state.path("values").path("victoryCondition").textValue())
                    && containsText(state.path("visibleControls"), "rivalTargetCount");
        }, evidence -> checkpoint(evidence, "conditionalVisible"));
        add(checks, "conditional.rival-target.value", evidence -> {
            JsonNode value = checkpoint(evidence, "conditionalVisible").path("values").path("rivalTargetCount");
            return value.canConvertToInt() && value.intValue() >= 1 && value.intValue() <= 3;
        }, evidence -> checkpoint(evidence, "conditionalVisible").path("values").path("rivalTargetCount"));
        add(checks, "conditional.rival-target.hidden", evidence -> {
            JsonNode state = checkpoint(evidence, "conditionalHidden");
            JsonNode value = state.path("values").path("rivalTargetCount");
            return "conquest".equals(state.path("values").path("victoryCondition").textValue())
                    && !containsText(state.path("visibleControls"), "rivalTargetCount")
                    && (value.isNull() || value.isMissingNode());
        }, evidence -> checkpoint(evidence, "conditionalHidden"));
        add(checks, "conditional.rival-target.focus-restored",
                evidence -> "victoryCondition".equals(
                        checkpoint(evidence, "conditionalHidden").path("focusedControlId").textValue()),
                evidence -> checkpoint(evidence, "conditionalHidden").path("focusedControlId"));
    }

    private static void addSeedChecks(List<Check> checks) {
        add(checks, "seed.minimum.valid", evidence -> seedCheckpoint(evidence, "minimumSeed", 0L, true),
                evidence -> checkpoint(evidence, "minimumSeed"));
        add(checks, "seed.maximum.valid", evidence -> seedCheckpoint(evidence, "maximumSeed", 4_294_967_295L, true),
                evidence -> checkpoint(evidence, "maximumSeed"));
        add(checks, "seed.below-minimum.invalid", evidence -> invalidSeedCheckpoint(evidence, "belowMinimumSeed", "-1"),
                evidence -> checkpoint(evidence, "belowMinimumSeed"));
        add(checks, "seed.above-maximum.invalid", evidence -> invalidSeedCheckpoint(evidence, "aboveMaximumSeed", "4294967296"),
                evidence -> checkpoint(evidence, "aboveMaximumSeed"));
        add(checks, "seed.invalid.start-blocked", evidence -> {
            JsonNode state = checkpoint(evidence, "invalidStart");
            return !state.path("valid").asBoolean(true) && !state.path("started").asBoolean(true);
        }, evidence -> checkpoint(evidence, "invalidStart"));
    }

    private static void addTransitionChecks(List<Check> checks, JsonNode corpus) {
        JsonNode initial = byId(corpus.path("states"), "initial");
        JsonNode confirmation = byId(corpus.path("states"), "confirmation");
        add(checks, "transition.scroll-to-bottom", evidence -> {
            JsonNode state = checkpoint(evidence, "bottom");
            return "bottom".equals(state.path("scrollPosition").textValue())
                    && state.path("visibleControls").equals(initial.path("visibleControls"));
        }, evidence -> checkpoint(evidence, "bottom"));
        add(checks, "transition.copy-seed", evidence -> {
            JsonNode state = checkpoint(evidence, "copySeed");
            return state.path("seed").longValue() == 305_419_896L
                    && "305419896".equals(state.path("clipboardText").textValue());
        }, evidence -> checkpoint(evidence, "copySeed"));
        add(checks, "transition.random-seed", evidence -> {
            JsonNode state = checkpoint(evidence, "randomSeed");
            return state.path("previousSeed").longValue() == 1L
                    && state.path("seed").longValue() == 305_419_896L
                    && state.path("valid").asBoolean(false);
        }, evidence -> checkpoint(evidence, "randomSeed"));
        add(checks, "transition.cancel", evidence -> dismissed(checkpoint(evidence, "cancel")),
                evidence -> checkpoint(evidence, "cancel"));
        add(checks, "transition.escape", evidence -> dismissed(checkpoint(evidence, "escape")),
                evidence -> checkpoint(evidence, "escape"));
        add(checks, "transition.start-battle", evidence -> {
            JsonNode state = checkpoint(evidence, "confirmation");
            return "confirmation".equals(state.path("outcome").textValue())
                    && state.path("payload").equals(confirmation.path("payload"));
        }, evidence -> checkpoint(evidence, "confirmation"));
    }

    private static boolean seedCheckpoint(JsonNode evidence, String name, long seed, boolean valid) {
        JsonNode state = checkpoint(evidence, name);
        return state.path("seed").isIntegralNumber() && state.path("seed").longValue() == seed
                && state.path("valid").asBoolean(!valid) == valid;
    }

    private static boolean invalidSeedCheckpoint(JsonNode evidence, String name, String seedText) {
        JsonNode state = checkpoint(evidence, name);
        return seedText.equals(state.path("seedText").textValue()) && !state.path("valid").asBoolean(true);
    }

    private static boolean dismissed(JsonNode state) {
        return "dismissed".equals(state.path("outcome").textValue())
                && state.path("payloadDiscarded").asBoolean(false);
    }

    private static JsonNode checkpoint(JsonNode evidence, String name) {
        return evidence.path("checkpoints").path(name);
    }


    private static ArrayNode projectedControls(JsonNode evidence, String field) {
        return project(
                checkpoint(evidence, "initial").path("controls"), field);
    }

    private static ArrayNode project(JsonNode controls, String field) {
        ArrayNode values = JsonNodeFactory.instance.arrayNode();
        if (!controls.isArray()) {
            return values;
        }
        controls.forEach(control -> values.add(control.path(field).deepCopy()));
        return values;
    }

    private static boolean containsText(JsonNode array, String expected) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            if (expected.equals(value.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode byId(JsonNode array, String id) {
        for (JsonNode value : array) {
            if (id.equals(value.path("id").textValue())) {
                return value;
            }
        }
        throw new IllegalArgumentException("Corpus is missing " + id);
    }

    private static void add(List<Check> checks, String id, Predicate<JsonNode> test,
            java.util.function.Function<JsonNode, JsonNode> observed) {
        checks.add(new Check(id, test, observed));
    }

    private static String evidenceSnippet(JsonNode observed) {
        if (observed == null || observed.isMissingNode()) {
            return "missing";
        }
        return observed.toString();
    }

    private static String bound(String value) {
        String safe = value == null ? "missing" : value;
        return safe.length() <= MAX_EVIDENCE_CHARACTERS
                ? safe : safe.substring(0, MAX_EVIDENCE_CHARACTERS);
    }

    /** One stable pass/fail outcome with bounded internal evidence. */
    public record Assertion(String id, boolean passed, String evidence) {
        public Assertion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** Complete exact functional outcome, independent from visual channels. */
    public record Result(List<Assertion> assertions) {
        public Result {
            assertions = List.copyOf(assertions);
        }

        public int passedCount() {
            return (int) assertions.stream().filter(Assertion::passed).count();
        }

        public boolean allPassed() {
            return passedCount() == assertions.size();
        }
    }

    private record Check(String id, Predicate<JsonNode> test,
            java.util.function.Function<JsonNode, JsonNode> observed) {
    }
}
