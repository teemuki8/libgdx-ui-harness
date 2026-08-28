package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.protocol.ProtocolJson;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class HarnessToolCatalogTest {
    private static final Set<String> APPROVED = Set.of(
            "ui_sessions", "ui_artifact_read", "ui_snapshot", "ui_query", "ui_action",
            "ui_assert", "ui_wait",
            "ui_screenshot", "ui_inspect_compare", "ui_typography_diagnose",
            "ui_layout_diagnose", "ui_trace_start", "ui_trace_stop", "ui_capabilities",
            "ui_scenarios", "ui_scenario_start", "ui_navigation_inspect",
            "ui_navigation_validate", "ui_validate_layout", "ui_matrix_run",
            "ui_matrix_results", "ui_semantic_compare", "ui_trace_query",
            "ui_runtime_compare", "ui_runtime_observe", "ui_keyboard_gesture");

    private final HarnessToolCatalog catalog = new HarnessToolCatalog();

    @Test void exposesOnlyTheApprovedBoundedTools() {
        assertEquals(APPROVED, catalog.toolNames());
        assertEquals(26, catalog.tools().size());
        for (String name : APPROVED) {
            assertNotNull(catalog.accessMode(name));
        }
        assertEquals(HarnessToolCatalog.AccessMode.MUTATING, catalog.accessMode("ui_action"));
        assertEquals(HarnessToolCatalog.AccessMode.READ_ONLY, catalog.accessMode("ui_query"));
        assertThrows(IllegalArgumentException.class, () -> catalog.accessMode("ui_unknown"));
        for (McpSchema.Tool tool : catalog.tools()) {
            assertEquals("object", tool.inputSchema().get("type"));
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            assertFalse(tool.inputSchema().containsKey("unevaluatedProperties"));
            assertFalse(McpJsonDefaults.getSchemaValidator()
                    .validate(tool.inputSchema(), Map.of("path", "/tmp/attack"))
                    .valid(), tool.name());
        }
    }

    @Test void everyAllowlistedToolHasExactlyOneAccessMode() {
        Map<String, HarnessToolCatalog.AccessMode> expected = Map.ofEntries(
                Map.entry("ui_sessions", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_artifact_read", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_snapshot", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_query", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_action", HarnessToolCatalog.AccessMode.MUTATING),
                Map.entry("ui_assert", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_wait", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_screenshot", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_inspect_compare", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_typography_diagnose", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_layout_diagnose", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_trace_start", HarnessToolCatalog.AccessMode.MUTATING),
                Map.entry("ui_trace_stop", HarnessToolCatalog.AccessMode.MUTATING),
                Map.entry("ui_scenarios", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_scenario_start", HarnessToolCatalog.AccessMode.MUTATING),
                Map.entry("ui_navigation_inspect", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_navigation_validate", HarnessToolCatalog.AccessMode.MUTATING),
                Map.entry("ui_validate_layout", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_matrix_run", HarnessToolCatalog.AccessMode.MUTATING),
                Map.entry("ui_matrix_results", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_semantic_compare", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_trace_query", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_runtime_compare", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_runtime_observe", HarnessToolCatalog.AccessMode.READ_ONLY),
                Map.entry("ui_keyboard_gesture", HarnessToolCatalog.AccessMode.MUTATING),
                Map.entry("ui_capabilities", HarnessToolCatalog.AccessMode.READ_ONLY));
        assertEquals(APPROVED, expected.keySet());
        for (Map.Entry<String, HarnessToolCatalog.AccessMode> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), catalog.accessMode(entry.getKey()));
        }
    }

    @Test void artifactReadSchemaIsClosedAndChunkBounded() {
        Map<String, Object> valid = Map.of(
                "sessionId", "game",
                "reference", "artifact:opaque",
                "offset", 0,
                "maxBytes", ArtifactReference.MAX_CHUNK_BYTES);
        assertValid("ui_artifact_read", valid);
        assertInvalid("ui_artifact_read", with(valid, "path", "/tmp/secret"));
        assertInvalid("ui_artifact_read", with(valid, "maxBytes", 0));
        assertInvalid("ui_artifact_read", with(valid, "maxBytes",
                ArtifactReference.MAX_CHUNK_BYTES + 1));
        assertInvalid("ui_artifact_read", with(valid, "offset", -1));
        JsonNode output = ProtocolJson.mapper().valueToTree(
                catalog.tool("ui_artifact_read").outputSchema());
        assertEquals(87_384, output.at("/properties/data/maxLength").asInt());
        assertEquals(false, output.at("/additionalProperties").asBoolean());
    }

    @Test void runtimeObserveSchemaIsClosedBoundedAndLocatorFree() {
        Map<String, Object> arguments = Map.of(
                "sessionId", "game",
                "entityId", "body-1",
                "propertyId", "angle",
                "correlationToken", "render-frame",
                "maxDurationMillis", 2_000);

        assertValid("ui_runtime_observe", arguments);
        assertInvalid("ui_runtime_observe", with(arguments, "locator", Map.of()));
        assertInvalid("ui_runtime_observe", with(arguments, "extra", true));
        assertInvalid("ui_runtime_observe",
                with(arguments, "entityId", "x".repeat(257)));
        assertInvalid("ui_runtime_observe",
                with(arguments, "maxDurationMillis", 3_600_001));

        JsonNode input = ProtocolJson.mapper().valueToTree(
                catalog.tool("ui_runtime_observe").inputSchema());
        assertEquals(false, input.at("/additionalProperties").asBoolean());
        assertTrue(input.at("/properties/locator").isMissingNode());
        Map<String, Object> output = catalog.tool("ui_runtime_observe").outputSchema();
        Map<String, Object> progress = Map.of(
                "status", "available", "dimensions", Map.of(), "ruleId", "success/v1");
        Map<String, Object> recovery = Map.ofEntries(
                Map.entry("policyVersion", "recovery/v1"),
                Map.entry("consumedBefore", 0),
                Map.entry("consumed", 0),
                Map.entry("limit", 3),
                Map.entry("remainingBefore", 3),
                Map.entry("remaining", 3),
                Map.entry("elapsedMillis", 0),
                Map.entry("maxWallTimeMillis", 30_000),
                Map.entry("terminatingRule", "success/v1"));
        Map<String, Object> available = Map.of(
                "kind", "runtime-observation-result",
                "progress", progress,
                "recovery", recovery,
                "status", "AVAILABLE",
                "entityId", "body-1",
                "propertyId", "angle",
                "runtimeFrame", 41L,
                "runtimeRevision", 17L,
                "value", "1.25",
                "valueFormatId", "decimal");
        Map<String, Object> unavailable = Map.of(
                "kind", "runtime-observation-result",
                "progress", progress,
                "recovery", recovery,
                "status", "UNAVAILABLE",
                "entityId", "body-1",
                "propertyId", "angle");
        Map<String, Object> missingFormat = new java.util.LinkedHashMap<>(available);
        missingFormat.remove("valueFormatId");
        var availableValidation =
                McpJsonDefaults.getSchemaValidator().validate(output, available);
        assertTrue(availableValidation.valid(), availableValidation.toString());
        assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                output, unavailable).valid());
        assertFalse(McpJsonDefaults.getSchemaValidator().validate(
                output, missingFormat).valid());
        assertFalse(McpJsonDefaults.getSchemaValidator().validate(
                output, with(unavailable, "value", "guessed")).valid());
        JsonNode outputJson = ProtocolJson.mapper().valueToTree(output);
        for (JsonNode variant : outputJson.path("oneOf")) {
            assertEquals(false, variant.path("additionalProperties").asBoolean());
        }
    }

    @Test void keyboardGestureSchemaIsClosedBoundedAndLocatorFree() {
        Map<String, Object> frame = Map.of(
                "sessionId", "game", "schemaVersion", 1, "deadlineMillis", 30_000,
                "steps", List.of(
                        Map.of("kind", "key-down", "keycode", 29),
                        Map.of("kind", "wait-frames", "count", 30),
                        Map.of("kind", "key-up", "keycode", 29)));
        Map<String, Object> ticks = Map.of(
                "sessionId", "game", "schemaVersion", 2, "deadlineMillis", 30_000,
                "steps", List.of(
                        Map.of("kind", "key-down", "keycode", 29),
                        Map.of("kind", "wait-ticks", "count", 30),
                        Map.of("kind", "key-up", "keycode", 29)));
        assertValid("ui_keyboard_gesture", frame);
        assertValid("ui_keyboard_gesture", ticks);
        assertEquals(HarnessToolCatalog.AccessMode.MUTATING,
                catalog.accessMode("ui_keyboard_gesture"));
        JsonNode input = ProtocolJson.mapper().valueToTree(
                catalog.tool("ui_keyboard_gesture").inputSchema());
        assertTrue(input.at("/properties/locator").isMissingNode());
        assertEquals(2, input.at("/properties/steps/minItems").asInt());
        assertEquals(2, input.at("/allOf").size());
        assertEquals(256, input.at("/properties/steps/maxItems").asInt());
        assertEquals(false, input.at("/additionalProperties").asBoolean());
        for (JsonNode variant : input.at("/properties/steps/items/oneOf")) {
            assertEquals(false, variant.at("/additionalProperties").asBoolean());
        }

        assertInvalid("ui_keyboard_gesture", with(frame, "extra", true));
        assertInvalid("ui_keyboard_gesture", with(frame, "locator", Map.of()));
        assertInvalid("ui_keyboard_gesture", with(frame, "schemaVersion", 3));
        assertInvalid("ui_keyboard_gesture", with(frame, "deadlineMillis", 0));
        assertInvalid("ui_keyboard_gesture", with(frame, "deadlineMillis", 120_001));
        assertInvalid("ui_keyboard_gesture", with(frame, "steps", List.of(
                Map.of("kind", "key-down", "keycode", 29, "extra", true),
                Map.of("kind", "key-up", "keycode", 29))));
        assertInvalid("ui_keyboard_gesture", with(frame, "steps", List.of(
                Map.of("kind", "key-down", "keycode", -1),
                Map.of("kind", "key-up", "keycode", 29))));
        assertInvalid("ui_keyboard_gesture", with(frame, "steps", List.of(
                Map.of("kind", "key-down", "keycode", 256),
                Map.of("kind", "key-up", "keycode", 29))));
        assertInvalid("ui_keyboard_gesture", with(frame, "steps", List.of(
                Map.of("kind", "key-down", "keycode", 29),
                Map.of("kind", "wait-frames", "count", 0),
                Map.of("kind", "key-up", "keycode", 29))));
        assertInvalid("ui_keyboard_gesture", with(frame, "steps", List.of(
                Map.of("kind", "key-down", "keycode", 29),
                Map.of("kind", "wait-ticks", "count", 10_001),
                Map.of("kind", "key-up", "keycode", 29))));
        assertValid("ui_keyboard_gesture", with(
                with(frame, "schemaVersion", 2), "steps", balancedGestureSteps(256)));
        assertInvalid("ui_keyboard_gesture", with(
                with(frame, "schemaVersion", 2), "steps", balancedGestureSteps(257)));
        assertInvalid("ui_keyboard_gesture", with(
                frame, "steps", balancedGestureSteps(65)));


        JsonNode output = ProtocolJson.mapper().valueToTree(
                catalog.tool("ui_keyboard_gesture").outputSchema());
        assertEquals("keyboard-gesture-result", output.at("/properties/kind/const").asText());
        assertTrue(output.at("/required").toString().contains("cleanupStatus"));
        assertEquals(256, output.at("/properties/steps/maxItems").asInt());
        assertEquals(16, output.at("/properties/cleanup/maxItems").asInt());
        assertEquals(10_000,
                output.at("/properties/steps/items/properties/tick/properties/requestedTicks/maximum")
                        .asInt());
    }

    @Test void layoutSchemaEnforcesTheFixedTwoSecondQuiescenceBound() {
        assertValid("ui_layout_diagnose", Map.of(
                "sessionId", "game",
                "referenceId", "layout-reference",
                "viewportId", "main",
                "maxDurationMillis", 2_000,
                "maxResults", 16,
                "maxWidth", 1921,
                "maxHeight", 1080,
                "maxPixels", 2_073_600,
                "maxPngBytes", 4_194_304));
        assertInvalid("ui_layout_diagnose", Map.of(
                "sessionId", "game",
                "referenceId", "layout-reference",
                "viewportId", "main",
                "maxDurationMillis", 2_001,
                "maxResults", 16,
                "maxWidth", 1921,
                "maxHeight", 1080,
                "maxPixels", 2_073_600,
                "maxPngBytes", 4_194_304));
    }

    @Test void typographySchemaRequiresBoundedNamedReferenceAndViewport() {
        assertValid("ui_typography_diagnose", Map.of(
                "sessionId", "game",
                "referenceId", "title-reference",
                "viewportId", "main",
                "maxDurationMillis", 30_000,
                "maxResults", 16,
                "maxWidth", 1921,
                "maxHeight", 1080,
                "maxPixels", 2_073_600,
                "maxPngBytes", 4_194_304));
        assertInvalid("ui_typography_diagnose", Map.of(
                "sessionId", "game",
                "referenceId", "title-reference",
                "viewportId", "main",
                "maxDurationMillis", 30_000,
                "maxResults", 257,
                "maxWidth", 1921,
                "maxHeight", 1080,
                "maxPixels", 2_073_600,
                "maxPngBytes", 4_194_304));
    }

    @Test void goldenCatalogMatchesTypedSchemas() throws Exception {
        List<Map<String, Object>> projection = catalog.tools().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "inputSchema", tool.inputSchema(),
                        "outputSchema", tool.outputSchema()))
                .toList();
        JsonNode actual = ProtocolJson.mapper().valueToTree(projection);
        if ("true".equals(System.getenv("UPDATE_TOOL_CATALOG_GOLDEN"))) {
            Files.writeString(
                    Path.of("src/test/resources/mcp/tool-catalog-v1.json"),
                    ProtocolJson.mapper().writerWithDefaultPrettyPrinter()
                            .writeValueAsString(actual) + System.lineSeparator());
        }
        try (InputStream fixture = getClass().getResourceAsStream("/mcp/tool-catalog-v1.json")) {
            JsonNode expected = ProtocolJson.mapper().readTree(fixture);
            assertEquals(expected.toString(), actual.toString());
        }
    }

    @Test void matrixReportObservedIdentitySchemaBindsToTheCoreModelLimits() {
        JsonNode output = ProtocolJson.mapper().valueToTree(
                catalog.tool("ui_matrix_results").outputSchema());
        JsonNode items = output.at("/properties/report/properties/results/items");
        assertNotNull(items.get("properties"), "matrix-report results items must declare properties");
        assertMatrixObservedIdentityField(items, "observedLocale", 1, true);
        assertMatrixObservedIdentityField(items, "observedFontSetId", 0, false);
        assertMatrixObservedIdentityField(items, "observedRestartProfileId", 1, true);
    }

    private static void assertMatrixObservedIdentityField(
            JsonNode items, String field, int minimum, boolean nonBlank) {
        JsonNode schema = items.at("/properties/" + field);
        assertEquals("string", schema.at("/oneOf/0/type").asText(), field + " string variant");
        assertEquals(minimum, schema.at("/oneOf/0/minLength").asInt(),
                field + " non-null minimum length");
        assertEquals(256, schema.at("/oneOf/0/maxLength").asInt(),
                field + " must cap at the core model bound of 256");
        assertEquals("null", schema.at("/oneOf/1/type").asText(), field + " null variant");
        assertEquals(nonBlank, !schema.at("/oneOf/0/pattern").isMissingNode(),
                field + " non-blank pattern presence");
    }

    @Test void matrixReportObservedIdentitySchemaRejectsBlankAndAcceptsRepresentativeValues() {
        Map<String, Object> outputSchema = catalog.tool("ui_matrix_results").outputSchema();
        var validator = McpJsonDefaults.getSchemaValidator();
        assertFalse(validator.validate(outputSchema, matrixReport(
                "   ", "", "   ")).valid(),
                "whitespace-only observed identity values must be rejected like the core model");
        assertFalse(validator.validate(outputSchema, matrixReport(
                "   ", "", "desktop-restart-1280x720")).valid(),
                "blank observedLocale must be rejected");
        assertFalse(validator.validate(outputSchema, matrixReport(
                "en", "", "   ")).valid(),
                "blank observedRestartProfileId must be rejected");
        var representative = validator.validate(outputSchema, matrixReport(
                "en", "", "desktop-restart-1280x720"));
        assertTrue(representative.valid(),
                "representative non-blank observed identities must be accepted: "
                        + representative);
        assertTrue(validator.validate(outputSchema, matrixReport(
                "en", "   ", "desktop-restart-1280x720")).valid(),
                "observedFontSetId stays blank-allowed");
        assertTrue(validator.validate(outputSchema, matrixReport(
                "en", "", null)).valid(),
                "observed identities stay nullable");
    }

    private static Map<String, Object> matrixReport(
            String observedLocale, String observedFontSetId, String observedRestartProfileId) {
        Map<String, Object> caseSummary = new java.util.LinkedHashMap<>();
        caseSummary.put("index", 0);
        caseSummary.put("window", Map.of("width", 1280, "height", 720));
        caseSummary.put("uiScale", 1.0);
        caseSummary.put("devicePixelRatio", 1.0);
        caseSummary.put("hiDpiMode", "LOGICAL");
        caseSummary.put("locale", "en");
        caseSummary.put("fontSetId", "");
        caseSummary.put("aspectRatio", 16.0 / 9.0);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("caseSummary", caseSummary);
        result.put("status", "PASSED");
        result.put("observedLocale", observedLocale);
        result.put("observedFontSetId", observedFontSetId);
        result.put("observedRestartProfileId", observedRestartProfileId);
        return Map.of(
                "kind", "matrix-report",
                "progress", Map.of(
                        "status", "unavailable",
                        "dimensions", Map.of(),
                        "ruleId", "progress-fingerprint/v1"),
                "recovery", Map.of(
                        "policyVersion", "recovery-policy/v1",
                        "consumedBefore", 0,
                        "consumed", 0,
                        "limit", 3,
                        "remainingBefore", 3,
                        "remaining", 3,
                        "elapsedMillis", 10,
                        "maxWallTimeMillis", 30_000,
                        "terminatingRule", "success/v1"),
                "report", Map.of(
                        "runId", "run-1",
                        "scenarioId", "matrix",
                        "results", List.of(result),
                        "truncated", false));
    }

    @Test void everyAdvertisedExampleValidatesAgainstItsInputSchema() {
        for (Map<String, Object> operation : catalog.operationCatalog()) {
            String name = (String) operation.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> examples =
                    (List<Map<String, Object>>) operation.get("minimalExamples");
            assertFalse(examples.isEmpty(), name);
            for (Map<String, Object> example : examples) {
                assertValid(name, example);
                assertTrue(SchemaDiagnostics.validate(
                        catalog.tool(name).inputSchema(), example, example).isEmpty(), name);
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actionExamples =
                (List<Map<String, Object>>) catalog.operationCatalog().stream()
                        .filter(operation -> "ui_action".equals(operation.get("name")))
                        .findFirst().orElseThrow().get("minimalExamples");
        assertEquals(Set.of(
                        "click", "hover", "focus", "fill",
                        "press", "scroll", "drag", "pointer"),
                actionExamples.stream()
                        .map(example -> (Map<?, ?>) example.get("action"))
                        .map(action -> (String) action.get("kind"))
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test void assertionSchemaAcceptsExactlyAllThirteenClosedVersionedVariants() {
        List<Map<String, Object>> assertions = List.of(
                Map.of("kind", "visible"),
                Map.of("kind", "hidden"),
                Map.of("kind", "enabled"),
                Map.of("kind", "disabled"),
                Map.of("kind", "focused"),
                Map.of("kind", "checked"),
                Map.of("kind", "text-equals", "expected", "Ready"),
                Map.of("kind", "text-contains", "expected", "ead"),
                Map.of("kind", "count-equals", "expected", 2),
                Map.of("kind", "bounds-inside-viewport", "viewport",
                        Map.of("x", 0, "y", 0, "width", 800, "height", 600)),
                Map.of("kind", "does-not-overlap", "other",
                        Map.of("kind", "test-id", "testId", "dialog")),
                Map.of("kind", "stable-for-frames", "frames", 3,
                        "properties", List.of("bounds", "accessible-name")),
                Map.of("kind", "accessible-name-exists"));
        Map<String, Object> base = Map.of(
                "sessionId", "game",
                "schemaVersion", 1,
                "deadlineMillis", 500,
                "locator", Map.of("kind", "test-id", "testId", "save"));
        for (Map<String, Object> assertion : assertions) {
            assertValid("ui_assert", with(base, "assertion", assertion));
        }
        assertInvalid("ui_assert", with(base, "assertion", Map.of("kind", "future")));
        assertInvalid("ui_assert", with(base, "schemaVersion", 2));
        assertInvalid("ui_assert", with(base, "assertion",
                Map.of("kind", "visible", "surprise", true)));
        assertInvalid("ui_assert", with(base, "locator", Map.of(
                "kind", "filter",
                "locator", Map.of("kind", "role", "role", "button"),
                "filter", Map.of("kind", "has", "locator", Map.of(
                        "kind", "test-id", "testId", "child", "surprise", true)))));
        assertInvalid("ui_assert", with(base, "assertion", Map.of(
                "kind", "does-not-overlap",
                "other", Map.of("kind", "index",
                        "locator", Map.of("kind", "role", "role", "dialog",
                                "surprise", true), "index", 0))));
    }

    @Test void schemasRejectMalformedAndArbitraryExecutionInputs() {
        assertInvalid("ui_action", Map.of("sessionId", "game", "method", "java.lang.Runtime.exec"));
        assertInvalid("ui_action", Map.of("sessionId", "game", "script", "System.exit(0)"));
        assertInvalid("ui_screenshot", Map.of("sessionId", "game", "path", "/tmp/out.png"));
        assertInvalid("ui_query", Map.of("sessionId", "game", "locator", Map.of(
                "kind", "text", "field", "text", "match", Map.of(
                        "mode", "regex", "source", "x".repeat(16_385)))));
        assertInvalid("ui_trace_start", Map.of("sessionId", "game",
                "maxDurationMillis", 3_600_001, "maxBytes", 1));
        assertValid("ui_action", Map.of(
                "sessionId", "game",
                "locator", Map.of("kind", "role", "role", "button"),
                "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                        "force", false)));
    }

    @Test void scenarioStartSchemaAcceptsOnlyBoundedRegisteredInputs() {
        Map<String, Object> valid = Map.of(
                "sessionId", "game",
                "scenarioId", "main-menu",
                "seed", 7,
                "configuration", Map.of("locale", "en"),
                "profileId", "desktop",
                "deadlineMillis", 600_000);
        assertValid("ui_scenario_start", valid);
        assertInvalid("ui_scenario_start", with(valid, "command", "java -jar game.jar"));
        assertInvalid("ui_scenario_start", with(valid, "path", "/tmp/game"));
        assertInvalid("ui_scenario_start", with(valid, "environment", Map.of("TOKEN", "secret")));
        assertInvalid("ui_scenario_start", with(valid, "class", "example.Game"));
        assertInvalid("ui_scenario_start", with(valid, "launchArguments", List.of("--unsafe")));
        Map<String, String> oversized = new java.util.LinkedHashMap<>();
        for (int index = 0; index <= 256; index++) {
            oversized.put("key-" + index, "value");
        }
        assertInvalid("ui_scenario_start", with(valid, "configuration", oversized));
        assertValid("ui_scenario_start", with(valid, "deadlineMillis", 600_000));
        assertInvalid("ui_scenario_start", with(valid, "deadlineMillis", 600_001));
    }

    @Test void recursiveLocatorCeilingsFitInsideProtocolLimits() {
        assertTrue(HarnessToolHandler.MAX_LOCATOR_DEPTH
                < ProtocolJson.MAX_NESTING_DEPTH);
        assertTrue(HarnessToolHandler.MAX_LOCATOR_NODES
                <= ProtocolJson.MAX_REQUEST_BYTES / 256);
    }

    @Test void comparisonOutputAllowsUnattributedRasterResidual() {
        Map<String, Object> output = Map.of(
                "kind", "inspect-compare-result",
                "status", "not-converged",
                "policy", "pixel-exact/v1",
                "iterations", 1,
                "elapsedMillis", 10,
                "progress", Map.of(
                        "status", "unavailable",
                        "dimensions", Map.of(),
                        "ruleId", "progress-fingerprint/v1"),
                "recovery", Map.of(
                        "policyVersion", "recovery-policy/v1",
                        "consumedBefore", 0,
                        "consumed", 0,
                        "limit", 3,
                        "remainingBefore", 3,
                        "remaining", 3,
                        "elapsedMillis", 10,
                        "maxWallTimeMillis", 30_000,
                        "terminatingRule", "success/v1"),
                "differences", List.of(Map.of(
                        "category", "raster-residual",
                        "path", "$.pixels",
                        "expected", "reference pixels",
                        "observed", "1 current pixel differs",
                        "blocking", true)),
                "regions", List.of(Map.of(
                        "category", "raster-residual",
                        "x", 10,
                        "y", 22,
                        "width", 2,
                        "height", 3,
                        "differingPixels", 4,
                        "meanAbsoluteError", 12.5)),
                "diagnostics", List.of());

        assertTrue(McpJsonDefaults.getSchemaValidator()
                .validate(catalog.tool("ui_inspect_compare").outputSchema(), output)
                .valid());
    }

    @Test void assertionOutputAcceptsEmptySetLevelNodeIdForCountEvidence() {
        Map<String, Object> output = Map.ofEntries(
                Map.entry("kind", "assertion-result"),
                Map.entry("schemaVersion", 1),
                Map.entry("outcome", "passed"),
                Map.entry("locator", Map.of("kind", "role", "role", "button")),
                Map.entry("assertion", Map.of("kind", "count-equals", "expected", 0)),
                Map.entry("nodeId", ""),
                Map.entry("expected", "count equals 0"),
                Map.entry("lastObserved", "0"),
                Map.entry("actionability", "satisfied"),
                Map.entry("revision", 1),
                Map.entry("frame", 1),
                Map.entry("elapsedMillis", 0),
                Map.entry("candidates", List.of()),
                Map.entry("progress", Map.of(
                        "status", "unavailable",
                        "dimensions", Map.of(),
                        "ruleId", "progress-fingerprint/v1")),
                Map.entry("recovery", Map.of(
                        "policyVersion", "recovery-policy/v1",
                        "consumedBefore", 0,
                        "consumed", 0,
                        "limit", 3,
                        "remainingBefore", 3,
                        "remaining", 3,
                        "elapsedMillis", 0,
                        "maxWallTimeMillis", 30_000,
                        "terminatingRule", "success/v1")),
                Map.entry("truncated", false));

        var validation = McpJsonDefaults.getSchemaValidator()
                .validate(catalog.tool("ui_assert").outputSchema(), output);
        assertTrue(validation.valid(), validation.toString());
    }

    @Test void catalogContainsNoPathExecutionReflectionOrCodeParameters() throws Exception {
        String json = ProtocolJson.mapper().writeValueAsString(catalog.tools().stream()
                .map(McpSchema.Tool::inputSchema)
                .toList());
        for (String forbidden : List.of("\"path\"", "\"command\"", "\"method\"",
                "\"script\"", "\"code\"", "\"class\"", "\"environment\"",
                "\"launchArguments\"", "reflection")) {
            assertFalse(json.contains(forbidden), forbidden);
        }
        assertTrue(json.contains("maxDurationMillis"));
        assertTrue(json.contains("maxPngBytes"));
    }

    private static List<Map<String, Object>> balancedGestureSteps(int count) {
        ArrayList<Map<String, Object>> steps = new ArrayList<>(count);
        int transitions = count - (count & 1);
        for (int index = 0; index < transitions; index += 2) {
            steps.add(Map.of("kind", "key-down", "keycode", 29));
            steps.add(Map.of("kind", "key-up", "keycode", 29));
        }
        if ((count & 1) != 0) {
            steps.add(steps.size() - 1, Map.of("kind", "wait-frames", "count", 1));
        }
        return steps;
    }

    private static Map<String, Object> with(
            Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private void assertValid(String name, Map<String, Object> arguments) {
        assertTrue(McpJsonDefaults.getSchemaValidator()
                .validate(catalog.tool(name).inputSchema(), arguments).valid(), name);
    }

    private void assertInvalid(String name, Map<String, Object> arguments) {
        assertFalse(McpJsonDefaults.getSchemaValidator()
                .validate(catalog.tool(name).inputSchema(), arguments).valid());
    }
}
