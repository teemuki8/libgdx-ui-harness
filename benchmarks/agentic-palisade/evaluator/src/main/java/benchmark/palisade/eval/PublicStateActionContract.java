package benchmark.palisade.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit bridge from the published state/action contract to frozen evaluator predicates. */
public final class PublicStateActionContract {
    private static final String SUITE_VERSION = "state-action-suite/v1";
    private static final String CONTRACT_MAJOR = "state-action/v1.";

    private PublicStateActionContract() {}

    /**
     * Validates a deterministic scenario suite and projects only documented public fields into
     * the frozen predicate input. Candidate aliases and permissive defaults are not accepted.
     */
    public static ObjectNode toFunctionalEvidence(JsonNode suite) {
        require(suite != null && suite.isObject(), "$", "object", suite);
        require(SUITE_VERSION.equals(suite.path("schemaVersion").textValue()),
                "$.schemaVersion", SUITE_VERSION, suite.path("schemaVersion"));
        JsonNode scenarios = suite.path("scenarios");
        require(scenarios.isObject(), "$.scenarios", "object", scenarios);
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        ObjectNode checkpoints = evidence.putObject("checkpoints");
        var entries = scenarios.properties().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            try {
                checkpoints.set(entry.getKey(), checkpoint(entry.getKey(), entry.getValue()));
            } catch (ContractDiagnosticException incompatible) {
                throw incompatible.withAffectedAssertions(affectedAssertions(entry.getKey()));
            }
        }
        return evidence;
    }

    private static Set<String> affectedAssertions(String scenario) {
        return switch (scenario) {
            case "initial" -> Set.of(
                    "controls.order", "controls.kinds", "controls.labels",
                    "controls.options", "controls.defaults", "controls.focus-order",
                    "controls.validation", "state.initial.values",
                    "state.initial.visibility", "transition.scroll-to-bottom");
            case "bottom" -> Set.of("state.bottom.scroll", "transition.scroll-to-bottom");
            case "conditionalVisible" -> Set.of(
                    "conditional.rival-target.visible", "conditional.rival-target.value");
            case "conditionalHidden" -> Set.of(
                    "conditional.rival-target.hidden",
                    "conditional.rival-target.focus-restored");
            case "minimumSeed" -> Set.of("seed.minimum.valid");
            case "maximumSeed" -> Set.of("seed.maximum.valid");
            case "belowMinimumSeed" -> Set.of("seed.below-minimum.invalid");
            case "aboveMaximumSeed" -> Set.of("seed.above-maximum.invalid");
            case "invalidStart" -> Set.of("seed.invalid.start-blocked");
            case "copySeed" -> Set.of("transition.copy-seed");
            case "randomSeed" -> Set.of("transition.random-seed");
            case "cancel" -> Set.of("transition.cancel");
            case "escape" -> Set.of("transition.escape");
            case "confirmation" -> Set.of("transition.start-battle");
            default -> Set.of();
        };
    }

    private static ObjectNode checkpoint(String scenario, JsonNode contract) {
        String base = "$.scenarios." + scenario;
        validateContract(base, contract);
        ObjectNode checkpoint = JsonNodeFactory.instance.objectNode();
        ObjectNode values = checkpoint.putObject("values");
        ArrayNode visible = checkpoint.putArray("visibleControls");
        ArrayNode controlOrder = checkpoint.putArray("controlOrder");
        ArrayNode controls = checkpoint.putArray("controls");
        for (JsonNode control : contract.path("controls")) {
            String id = control.path("id").textValue();
            controlOrder.add(id);
            JsonNode currentValue =
                    value(control.path("currentValue"), base + ".controls.currentValue");
            if (!currentValue.isNull() || "rivalTargetCount".equals(id)) {
                values.set(id, currentValue);
            }
            if (control.path("visible").booleanValue()) {
                visible.add(id);
            }
            ObjectNode projected = controls.addObject();
            projected.put("id", id);
            projected.put("kind", control.path("kind").textValue());
            projected.put("label", control.path("accessibleName").textValue());
            ArrayNode options = projected.putArray("options");
            for (JsonNode option : control.path("options")) {
                ObjectNode projectedOption = options.addObject();
                projectedOption.set("value", value(
                        option.path("value"), base + ".controls.options.value"));
                projectedOption.put("label", option.path("label").textValue());
            }
            projected.set("default", value(
                    control.path("defaultValue"), base + ".controls.defaultValue"));
            JsonNode rule = control.path("validationRule");
            ObjectNode validation = projected.putObject("validation");
            validation.set("minimum", optionalValue(rule.get("minimum"), base + ".minimum"));
            validation.set("maximum", optionalValue(rule.get("maximum"), base + ".maximum"));
            validation.set("step", optionalValue(rule.get("step"), base + ".step"));
            validation.put("format", rule.path("format").textValue());
            if ("seed".equals(id)) {
                checkpoint.put("valid",
                        control.path("validationStatus").path("valid").booleanValue());
            }
        }
        if (values.has("seed")) {
            JsonNode seed = values.path("seed");
            if (seed.isTextual()) {
                checkpoint.put("seedText", seed.textValue());
            } else if (!seed.isNull()) {
                checkpoint.set("seed", seed.deepCopy());
            }
        }
        checkpoint.set("focusOrder", contract.path("focusOrder").deepCopy());
        if (!contract.path("focusedControlId").isMissingNode()) {
            checkpoint.set("focusedControlId", contract.path("focusedControlId").deepCopy());
        }
        JsonNode viewport = contract.path("viewports").path(0);
        if (!viewport.isMissingNode()) {
            boolean bottom = viewport.path("maxScrollY").doubleValue() > 0
                    && Double.compare(viewport.path("scrollY").doubleValue(),
                            viewport.path("maxScrollY").doubleValue()) == 0;
            checkpoint.put("scrollPosition", bottom ? "bottom" : "top");
        }
        projectTransition(checkpoint, contract.path("transition"), values);
        return checkpoint;
    }

    private static void projectTransition(
            ObjectNode checkpoint, JsonNode transition, ObjectNode values) {
        if (!transition.isObject()) {
            return;
        }
        checkpoint.put("valid", transition.path("validation").path("valid").booleanValue());
        checkpoint.put("started", transition.path("accepted").booleanValue());
        String action = transition.path("actionId").textValue();
        if ("copy-seed".equals(action)) {
            checkpoint.set("seed", values.path("seed").deepCopy());
            checkpoint.set("clipboardText", transition.path("clipboardText").deepCopy());
        } else if ("random-seed".equals(action)) {
            JsonNode payload = transition.path("acceptedPayload");
            checkpoint.set("previousSeed", value(payload.path("previousSeed"),
                    "$.transition.acceptedPayload.previousSeed"));
            checkpoint.set("seed", value(payload.path("seed"),
                    "$.transition.acceptedPayload.seed"));
        }
        String kind = transition.path("kind").textValue();
        if ("dismissed".equals(kind)) {
            checkpoint.put("outcome", "dismissed");
            checkpoint.put("payloadDiscarded",
                    transition.path("acceptedPayload").isObject()
                            && transition.path("acceptedPayload").isEmpty());
        } else if ("confirmation".equals(kind)) {
            checkpoint.put("outcome", "confirmation");
            ObjectNode payload = checkpoint.putObject("payload");
            transition.path("acceptedPayload").properties().forEach(
                    entry -> payload.set(entry.getKey(), value(
                            entry.getValue(), "$.transition.acceptedPayload." + entry.getKey())));
        }
    }

    private static void validateContract(String base, JsonNode contract) {
        require(contract.isObject(), base, "object", contract);
        require(contract.path("schemaVersion").isTextual()
                        && contract.path("schemaVersion").textValue().startsWith(CONTRACT_MAJOR),
                base + ".schemaVersion", "supported major state-action/v1", contract.path("schemaVersion"));
        for (String required : new String[] {
                "stateId", "revision", "frame", "controls", "focusOrder",
                "conditions", "viewports"}) {
            require(!contract.path(required).isMissingNode(),
                    base + "." + required, "required field", contract.path(required));
        }
        require(contract.path("controls").isArray(), base + ".controls", "array",
                contract.path("controls"));
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (JsonNode control : contract.path("controls")) {
            String path = base + ".controls[" + index + "]";
            for (String required : new String[] {
                    "id", "role", "kind", "accessibleName", "options", "defaultValue",
                    "currentValue", "visible", "enabled", "actionable", "focusable",
                    "focused", "validationRule", "validationStatus"}) {
                require(!control.path(required).isMissingNode(),
                        path + "." + required, "required field", control.path(required));
            }
            require(ids.add(control.path("id").textValue()), path + ".id",
                    "unique stable control ID", control.path("id"));
            index++;
        }
    }

    private static JsonNode optionalValue(JsonNode node, String path) {
        return node == null ? JsonNodeFactory.instance.nullNode() : value(node, path);
    }

    private static JsonNode value(JsonNode typed, String path) {
        require(typed != null && typed.isObject(), path, "typed value object", typed);
        return switch (typed.path("type").asText()) {
            case "null" -> JsonNodeFactory.instance.nullNode();
            case "boolean" -> {
                require(typed.path("booleanValue").isBoolean(),
                        path + ".booleanValue", "boolean", typed.path("booleanValue"));
                yield typed.path("booleanValue").deepCopy();
            }
            case "integer" -> {
                require(typed.path("integerValue").isIntegralNumber(),
                        path + ".integerValue", "integer", typed.path("integerValue"));
                long integer = typed.path("integerValue").longValue();
                yield integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE
                        ? JsonNodeFactory.instance.numberNode((int) integer)
                        : JsonNodeFactory.instance.numberNode(integer);
            }
            case "decimal" -> {
                require(typed.path("decimalValue").isTextual(),
                        path + ".decimalValue", "canonical decimal string",
                        typed.path("decimalValue"));
                yield JsonNodeFactory.instance.numberNode(
                        new java.math.BigDecimal(typed.path("decimalValue").textValue()));
            }
            case "text" -> {
                require(typed.path("textValue").isTextual(),
                        path + ".textValue", "string", typed.path("textValue"));
                yield typed.path("textValue").deepCopy();
            }
            default -> throw diagnostic(path + ".type", "closed typed value name",
                    typed.path("type"));
        };
    }

    private static void require(
            boolean condition, String path, String expected, JsonNode observed) {
        if (!condition) {
            throw diagnostic(path, expected, observed);
        }
    }

    private static ContractDiagnosticException diagnostic(
            String path, String expected, JsonNode observed) {
        String actual = observed == null || observed.isMissingNode()
                ? "absent" : observed.toString();
        return new ContractDiagnosticException(path, expected, actual,
                "state-action/v1", Set.of());
    }

    /** Evaluator-safe compatibility failure with affected assertions attached by the caller. */
    public static final class ContractDiagnosticException extends IllegalArgumentException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final String path;
        private final String expected;
        private final String observed;
        private final String schemaVersion;
        private final Set<String> affectedAssertions;

        ContractDiagnosticException(
                String path,
                String expected,
                String observed,
                String schemaVersion,
                Set<String> affectedAssertions) {
            super(path + " expected " + expected + " but observed " + observed);
            this.path = Objects.requireNonNull(path, "path");
            this.expected = Objects.requireNonNull(expected, "expected");
            this.observed = Objects.requireNonNull(observed, "observed");
            this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
            this.affectedAssertions = Set.copyOf(
                    Objects.requireNonNull(affectedAssertions, "affectedAssertions"));
        }

        public String path() {
            return path;
        }

        public String expected() {
            return expected;
        }

        public String observed() {
            return observed;
        }

        public String schemaVersion() {
            return schemaVersion;
        }

        public Set<String> affectedAssertions() {
            return affectedAssertions;
        }

        ContractDiagnosticException withAffectedAssertions(Set<String> assertions) {
            return new ContractDiagnosticException(
                    path, expected, observed, schemaVersion, assertions);
        }
    }
}
