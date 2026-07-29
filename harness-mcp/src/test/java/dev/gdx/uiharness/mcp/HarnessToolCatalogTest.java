package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.protocol.ProtocolJson;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class HarnessToolCatalogTest {
    private static final Set<String> APPROVED = Set.of(
            "ui_sessions", "ui_snapshot", "ui_query", "ui_action", "ui_wait",
            "ui_screenshot", "ui_inspect_compare", "ui_trace_start",
            "ui_trace_stop", "ui_capabilities");

    private final HarnessToolCatalog catalog = new HarnessToolCatalog();

    @Test void exposesOnlyTheApprovedBoundedTools() {
        assertEquals(APPROVED, catalog.toolNames());
        assertEquals(10, catalog.tools().size());
        for (McpSchema.Tool tool : catalog.tools()) {
            assertEquals("object", tool.inputSchema().get("type"));
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            assertFalse(tool.inputSchema().containsKey("unevaluatedProperties"));
            assertFalse(McpJsonDefaults.getSchemaValidator()
                    .validate(tool.inputSchema(), Map.of("path", "/tmp/attack"))
                    .valid(), tool.name());
        }
    }

    @Test void goldenCatalogMatchesTypedSchemas() throws Exception {
        List<Map<String, Object>> projection = catalog.tools().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "inputSchema", tool.inputSchema(),
                        "outputSchema", tool.outputSchema()))
                .toList();
        JsonNode actual = ProtocolJson.mapper().valueToTree(projection);
        try (InputStream fixture = getClass().getResourceAsStream("/mcp/tool-catalog-v1.json")) {
            JsonNode expected = ProtocolJson.mapper().readTree(fixture);
            assertEquals(expected.toString(), actual.toString());
        }
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
