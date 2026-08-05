package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.protocol.ProtocolJson;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class HarnessToolCatalogTest {
    private static final Set<String> APPROVED = Set.of(
            "ui_sessions", "ui_snapshot", "ui_query", "ui_action", "ui_assert", "ui_wait",
            "ui_screenshot", "ui_inspect_compare", "ui_typography_diagnose",
            "ui_layout_diagnose", "ui_trace_start", "ui_trace_stop", "ui_capabilities",
            "ui_scenarios", "ui_scenario_start", "ui_navigation_inspect",
            "ui_navigation_validate");

    private final HarnessToolCatalog catalog = new HarnessToolCatalog();

    @Test void exposesOnlyTheApprovedBoundedTools() {
        assertEquals(APPROVED, catalog.toolNames());
        assertEquals(17, catalog.tools().size());
        for (McpSchema.Tool tool : catalog.tools()) {
            assertEquals("object", tool.inputSchema().get("type"));
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            assertFalse(tool.inputSchema().containsKey("unevaluatedProperties"));
            assertFalse(McpJsonDefaults.getSchemaValidator()
                    .validate(tool.inputSchema(), Map.of("path", "/tmp/attack"))
                    .valid(), tool.name());
        }
    }

    @Test void layoutSchemaEnforcesTheFixedTwoSecondQuiescenceBound() {
        assertValid("ui_layout_diagnose", Map.of(
                "sessionId", "game",
                "referenceId", "layout-reference",
                "viewportId", "main",
                "maxDurationMillis", 2_000,
                "maxResults", 16,
                "maxWidth", 1920,
                "maxHeight", 1080,
                "maxPixels", 2_073_600,
                "maxPngBytes", 4_194_304));
        assertInvalid("ui_layout_diagnose", Map.of(
                "sessionId", "game",
                "referenceId", "layout-reference",
                "viewportId", "main",
                "maxDurationMillis", 2_001,
                "maxResults", 16,
                "maxWidth", 1920,
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
                "maxWidth", 1920,
                "maxHeight", 1080,
                "maxPixels", 2_073_600,
                "maxPngBytes", 4_194_304));
        assertInvalid("ui_typography_diagnose", Map.of(
                "sessionId", "game",
                "referenceId", "title-reference",
                "viewportId", "main",
                "maxDurationMillis", 30_000,
                "maxResults", 257,
                "maxWidth", 1920,
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
                        "y", 20,
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

    private static Map<String, Object> with(
            Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private void assertValid(String name, Map<String, Object> arguments) {
        assertTrue(McpJsonDefaults.getSchemaValidator()
                .validate(catalog.tool(name).inputSchema(), arguments).valid());
    }

    private void assertInvalid(String name, Map<String, Object> arguments) {
        assertFalse(McpJsonDefaults.getSchemaValidator()
                .validate(catalog.tool(name).inputSchema(), arguments).valid());
    }
}
