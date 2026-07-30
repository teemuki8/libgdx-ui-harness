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
            "ui_sessions", "ui_snapshot", "ui_query", "ui_action", "ui_wait",
            "ui_screenshot", "ui_inspect_compare", "ui_typography_diagnose",
            "ui_layout_diagnose", "ui_trace_start", "ui_trace_stop", "ui_capabilities");

    private final HarnessToolCatalog catalog = new HarnessToolCatalog();

    @Test void exposesOnlyTheApprovedBoundedTools() {
        assertEquals(APPROVED, catalog.toolNames());
        assertEquals(12, catalog.tools().size());
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
                "diagnostics", List.of());

        assertTrue(McpJsonDefaults.getSchemaValidator()
                .validate(catalog.tool("ui_inspect_compare").outputSchema(), output)
                .valid());
    }

    @Test void catalogContainsNoPathExecutionReflectionOrCodeParameters() throws Exception {
        String json = ProtocolJson.mapper().writeValueAsString(catalog.tools().stream()
                .map(McpSchema.Tool::inputSchema)
                .toList());
        for (String forbidden : List.of("\"path\"", "\"command\"", "\"method\"",
                "\"script\"", "\"code\"", "\"class\"", "reflection")) {
            assertFalse(json.contains(forbidden), forbidden);
        }
        assertTrue(json.contains("maxDurationMillis"));
        assertTrue(json.contains("maxPngBytes"));
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
