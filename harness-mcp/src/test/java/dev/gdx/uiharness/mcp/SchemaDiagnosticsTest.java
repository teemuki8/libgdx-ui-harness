package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.protocol.DiagnosticCode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SchemaDiagnosticsTest {
    @Test void nullableLayoutLocatorAcceptsValidSubtreeAndExplicitNull() {
        var catalog = new HarnessToolCatalog();
        Map<String, Object> schema = catalog.tool("ui_validate_layout").inputSchema();
        var spec = new LinkedHashMap<String, Object>(Map.of(
                "targetMode", "subtree", "enabledChecks", List.of("clipped-text", "text-collision"),
                "minTargetWidth", 64, "minTargetHeight", 64, "minSpacing", 1,
                "maxAlignmentDelta", 1, "maxFindings", 256, "maxNodes", 10000,
                "maxDurationMillis", 2000, "failOn", "error"));
        spec.put("locator", Map.of("kind", "test-id", "testId", "username"));
        Map<String, Object> request = Map.of("sessionId", "reference-ui", "spec", spec);
        assertTrue(McpJsonDefaults.getSchemaValidator().validate(schema, request).valid());
        assertEquals(List.of(), SchemaDiagnostics.validate(schema, request, request));

        spec.put("targetMode", "stage");
        spec.put("locator", null);
        assertTrue(McpJsonDefaults.getSchemaValidator().validate(schema, request).valid());
        assertEquals(List.of(), SchemaDiagnostics.validate(schema, request, request));
    }

    @Test void nullableLocatorStillReportsUnknownKindAndUnknownProperties() {
        Map<String, Object> schema = new HarnessToolCatalog().tool("ui_validate_layout").inputSchema();
        Map<String, Object> request = Map.of("sessionId", "reference-ui", "spec", Map.of(
                "targetMode", "subtree", "locator", Map.of("kind", "execute")));
        var unknown = SchemaDiagnostics.validate(schema, request, Map.of());
        assertTrue(unknown.stream().anyMatch(problem ->
                problem.fieldPath().equals("$.spec.locator.kind")
                        && problem.code() == DiagnosticCode.INVALID_ENUM_VALUE
                        && problem.admissible().contains("test-id")));

        request = Map.of("sessionId", "reference-ui", "spec", Map.of(
                "targetMode", "subtree", "locator", Map.of("kind", "test-id", "testId", "username",
                        "method", "draw")));
        var extra = SchemaDiagnostics.validate(schema, request, Map.of());
        assertTrue(extra.stream().anyMatch(problem ->
                problem.fieldPath().equals("$.spec.locator.method")
                        && problem.code() == DiagnosticCode.UNKNOWN_ARGUMENT));
    }

    @Test void nullablePrimitiveRetainsTypeAndRangeValidation() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "value", Map.of("oneOf", List.of(
                        Map.of("type", "integer", "minimum", 1, "maximum", 3),
                        Map.of("type", "null")))));
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("value", 2);
        assertEquals(List.of(), SchemaDiagnostics.validate(schema, arguments, Map.of()));
        arguments.put("value", null);
        assertEquals(List.of(), SchemaDiagnostics.validate(schema, arguments, Map.of()));
        arguments.put("value", 4);
        assertEquals(DiagnosticCode.OUT_OF_RANGE,
                SchemaDiagnostics.validate(schema, arguments, Map.of()).getFirst().code());
        arguments.put("value", "2");
        assertEquals(DiagnosticCode.INVALID_ARGUMENT_TYPE,
                SchemaDiagnostics.validate(schema, arguments, Map.of()).getFirst().code());
    }
}
