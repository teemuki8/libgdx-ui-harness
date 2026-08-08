package dev.gdx.uiharness.mcp;

import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.DiagnosticCode;
import dev.gdx.uiharness.protocol.RecoveryPolicy;
import dev.gdx.uiharness.protocol.ProtocolJson;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Immutable catalog of the fifteen allowlisted MCP tools and their bounded JSON schemas. */
public final class HarnessToolCatalog {
    private static final int MAX_IDENTIFIER = 256;
    private static final Map<String, Object> ARTIFACT_SCHEMA = object(Map.of(
            "reference", string(1, ProtocolJson.MAX_STRING_LENGTH),
            "mediaType", string(1, 256),
            "byteLength", integer(0, ProtocolJson.MAX_RESPONSE_BYTES),
            "sha256", string(64, 64)),
            List.of("reference", "mediaType", "byteLength", "sha256"));

    /** Access classification of one allowlisted tool by whether it mutates session or app state. */
    public enum AccessMode {
        READ_ONLY, MUTATING
    }

    /** One immutable allowlisted definition: the schema source and its single access mode. */
    private record ToolDefinition(McpSchema.Tool tool, AccessMode mode) {}

    private final List<McpSchema.Tool> tools;
    private final Map<String, McpSchema.Tool> byName;
    private final Map<String, AccessMode> accessModes;
    private final Map<String, List<Map<String, Object>>> examples;

    /** Builds the fixed V1 catalog. */
    public HarnessToolCatalog() {
        List<ToolDefinition> definitions = List.of(
                tool(AccessMode.READ_ONLY, "ui_sessions", "List active harness sessions",
                        sessionsInput(),
                        output("sessions-result", Map.of(
                                "sessions", array(sessionSchema(), 65_536),
                                "artifact", ARTIFACT_SCHEMA), List.of())),
                tool(AccessMode.READ_ONLY, "ui_snapshot",
                        "Capture a compact semantic snapshot summary",
                        sessionInput(Map.of(), List.of()),
                        output("snapshot-summary", Map.of(
                                "revision", integer(0, Long.MAX_VALUE),
                                "frame", integer(0, Long.MAX_VALUE),
                                "rootId", string(1, MAX_IDENTIFIER),
                                "nodeCount", integer(0, Integer.MAX_VALUE),
                                "contractSchemaVersion",
                                        string(1, ProtocolJson.MAX_STRING_LENGTH),
                                "stateId", string(1, ProtocolJson.MAX_STRING_LENGTH),
                                "controlCount", integer(0, 256),
                                "contract", Map.of("type", "object"),
                                "artifact", ARTIFACT_SCHEMA),
                                List.of("revision", "frame", "rootId", "nodeCount"))),
                tool(AccessMode.READ_ONLY, "ui_query", "Query a semantic locator",
                        locatorInput(Map.of(), List.of()),
                        output("query-result", Map.of(
                                "matchCount", integer(0, Integer.MAX_VALUE),
                                "matches", array(nodeSummarySchema(), 65_536),
                                "evidence", array(evidenceSchema(), 65_536),
                                "artifact", ARTIFACT_SCHEMA), List.of("matchCount"))),
                tool(AccessMode.MUTATING, "ui_action", "Perform one allowlisted UI action",
                        locatorInput(Map.of("action", actionSchema()), List.of("action")),
                        output("action-result", Map.of(
                                "beforeRevision", integer(0, Long.MAX_VALUE),
                                "afterRevision", integer(1, Long.MAX_VALUE),
                                "observedState", string(1, ProtocolJson.MAX_STRING_LENGTH),
                                "evidence", evidenceSchema(),
                                "artifact", ARTIFACT_SCHEMA),
                                List.of("beforeRevision", "afterRevision", "observedState"))),
                tool(AccessMode.READ_ONLY, "ui_assert",
                        "Evaluate one bounded declarative UI assertion",
                        assertionInput(), assertionOutput()),
                tool(AccessMode.READ_ONLY, "ui_wait", "Wait for a bounded semantic condition",
                        locatorInput(Map.of("condition", enumString("present", "visible")),
                                List.of("condition")),
                        output("wait-result", Map.of(
                                "revision", integer(0, Long.MAX_VALUE),
                                "frame", integer(0, Long.MAX_VALUE),
                                "matchCount", integer(0, Integer.MAX_VALUE),
                                "matches", array(nodeSummarySchema(), 65_536),
                                "evidence", array(evidenceSchema(), 65_536),
                                "artifact", ARTIFACT_SCHEMA),
                                List.of("revision", "frame", "matchCount"))),
                tool(AccessMode.READ_ONLY, "ui_screenshot",
                        "Capture a bounded screenshot as an opaque artifact",
                        locatorInput(Map.of(
                                "maxWidth", integer(1, 8_192),
                                "maxHeight", integer(1, 8_192),
                                "maxPixels", integer(1, 33_554_432L),
                                "maxPngBytes", integer(1,
                                        HarnessResponse.Result.Screenshot.MAX_PNG_BYTES)),
                                List.of("maxWidth", "maxHeight", "maxPixels", "maxPngBytes"),
                                false),
                        output("screenshot-result", Map.of(
                                "artifact", ARTIFACT_SCHEMA,
                                "frame", integer(0, Long.MAX_VALUE),
                                "revision", integer(0, Long.MAX_VALUE),
                                "width", integer(1, 8_192),
                                "height", integer(1, 8_192),
                                "scaleX", positiveNumber(Double.MAX_VALUE),
                                "scaleY", positiveNumber(Double.MAX_VALUE)),
                                List.of("artifact", "frame", "revision", "width", "height",
                                        "scaleX", "scaleY"))),
                tool(AccessMode.READ_ONLY, "ui_inspect_compare",
                        "Inspect, capture, and compare one current full frame. Minimal valid "
                                + "arguments: {\"sessionId\":\"game\",\"referenceId\":\"main\","
                                + "\"policyId\":\"pixel-exact\",\"policyVersion\":1,"
                                + "\"viewportId\":\"main\",\"maxIterations\":1,"
                                + "\"maxDurationMillis\":30000,\"maxWidth\":8192,"
                                + "\"maxHeight\":8192,\"maxPixels\":33554432,"
                                + "\"maxPngBytes\":12579840}",
                        sessionInput(Map.of(
                                "referenceId", string(1, MAX_IDENTIFIER),
                                "policyId", string(1, 240),
                                "policyVersion", integer(1, Integer.MAX_VALUE),
                                "viewportId", string(1, MAX_IDENTIFIER),
                                "maxIterations", integer(1, 64),
                                "maxDurationMillis", integer(1, 120_000),
                                "maxWidth", integer(1, 8_192),
                                "maxHeight", integer(1, 8_192),
                                "maxPixels", integer(1, 33_554_432L),
                                "maxPngBytes", integer(
                                        1, HarnessResponse.Result.Screenshot.MAX_PNG_BYTES)),
                                List.of(
                                        "referenceId", "policyId", "policyVersion",
                                        "viewportId", "maxIterations", "maxDurationMillis",
                                        "maxWidth", "maxHeight", "maxPixels", "maxPngBytes")),
                        output("inspect-compare-result", Map.ofEntries(
                                Map.entry("status", enumString(
                                        "incomplete", "stale",
                                        "not-converged", "converged")),
                                Map.entry("policy", string(1, MAX_IDENTIFIER)),
                                Map.entry("referenceId", string(1, MAX_IDENTIFIER)),
                                Map.entry("currentArtifact", ARTIFACT_SCHEMA),
                                Map.entry("heatmapArtifact", ARTIFACT_SCHEMA),
                                Map.entry("evidenceArtifact", ARTIFACT_SCHEMA),
                                Map.entry("revision", integer(0, Long.MAX_VALUE)),
                                Map.entry("frame", integer(0, Long.MAX_VALUE)),
                                Map.entry("width", integer(1, 8_192)),
                                Map.entry("height", integer(1, 8_192)),
                                Map.entry("scaleX", positiveNumber(Double.MAX_VALUE)),
                                Map.entry("scaleY", positiveNumber(Double.MAX_VALUE)),
                                Map.entry("sha256", string(64, 64)),
                                Map.entry("iterations", integer(0, 64)),
                                Map.entry("elapsedMillis", integer(0, 120_000)),
                                Map.entry("metrics", comparisonMetricsSchema()),
                                Map.entry("differences", array(
                                        visualDifferenceSchema(), 1_024)),
                                Map.entry("regions", array(
                                        visualRegionSchema(), 256)),
                                Map.entry("diagnostics", array(
                                        comparisonDiagnosticSchema(), 256))),
                                List.of(
                                        "status", "policy", "iterations",
                                        "elapsedMillis", "differences", "regions",
                                        "diagnostics"))),
                tool(AccessMode.READ_ONLY, "ui_typography_diagnose",
                        "Capture and diagnose actor-attributed typography against a named "
                                + "reference",
                        sessionInput(Map.of(
                                "referenceId", string(1, MAX_IDENTIFIER),
                                "viewportId", string(1, MAX_IDENTIFIER),
                                "maxDurationMillis", integer(1, 120_000),
                                "maxResults", integer(1, 256),
                                "maxWidth", integer(1, 8_192),
                                "maxHeight", integer(1, 8_192),
                                "maxPixels", integer(1, 33_554_432L),
                                "maxPngBytes", integer(
                                        1, HarnessResponse.Result.Screenshot.MAX_PNG_BYTES)),
                                List.of(
                                        "referenceId", "viewportId", "maxDurationMillis",
                                        "maxResults", "maxWidth", "maxHeight",
                                        "maxPixels", "maxPngBytes")),
                        output("typography-diagnostic-result", Map.ofEntries(
                                Map.entry("status", enumString(
                                        "pixel-sharp", "not-pixel-sharp", "incomplete",
                                        "not-diagnosable", "stale", "not-stable")),
                                Map.entry("referenceId", string(1, MAX_IDENTIFIER)),
                                Map.entry("currentArtifact", ARTIFACT_SCHEMA),
                                Map.entry("evidenceArtifact", ARTIFACT_SCHEMA),
                                Map.entry("revision", integer(0, Long.MAX_VALUE)),
                                Map.entry("frame", integer(0, Long.MAX_VALUE)),
                                Map.entry("width", integer(1, 8_192)),
                                Map.entry("height", integer(1, 8_192)),
                                Map.entry("scaleX", positiveNumber(Double.MAX_VALUE)),
                                Map.entry("scaleY", positiveNumber(Double.MAX_VALUE)),
                                Map.entry("sha256", string(64, 64)),
                                Map.entry("reportCount", integer(0, 256)),
                                Map.entry("reports", array(typographyReportSchema(), 256)),
                                Map.entry("diagnostics", array(
                                        comparisonDiagnosticSchema(), 256)),
                                Map.entry("elapsedMillis", integer(0, 120_000))),
                                List.of(
                                        "status", "reportCount", "reports",
                                        "diagnostics", "elapsedMillis"))),
                tool(AccessMode.READ_ONLY, "ui_layout_diagnose",
                        "Capture and diagnose actor-attributed layout, clipping, and viewport "
                                + "geometry against a named reference",
                        sessionInput(Map.of(
                                "referenceId", string(1, MAX_IDENTIFIER),
                                "viewportId", string(1, MAX_IDENTIFIER),
                                "maxDurationMillis", integer(1, 2_000),
                                "maxResults", integer(1, 256),
                                "maxWidth", integer(1, 8_192),
                                "maxHeight", integer(1, 8_192),
                                "maxPixels", integer(1, 33_554_432L),
                                "maxPngBytes", integer(
                                        1, HarnessResponse.Result.Screenshot.MAX_PNG_BYTES)),
                                List.of(
                                        "referenceId", "viewportId", "maxDurationMillis",
                                        "maxResults", "maxWidth", "maxHeight",
                                        "maxPixels", "maxPngBytes")),
                        output("layout-diagnostic-result", Map.ofEntries(
                                Map.entry("status", enumString(
                                        "conformant", "non-conformant", "incomplete",
                                        "not-diagnosable", "stale", "not-stable")),
                                Map.entry("referenceId", string(1, MAX_IDENTIFIER)),
                                Map.entry("currentArtifact", ARTIFACT_SCHEMA),
                                Map.entry("evidenceArtifact", ARTIFACT_SCHEMA),
                                Map.entry("revision", integer(0, Long.MAX_VALUE)),
                                Map.entry("frame", integer(0, Long.MAX_VALUE)),
                                Map.entry("width", integer(1, 8_192)),
                                Map.entry("height", integer(1, 8_192)),
                                Map.entry("scaleX", positiveNumber(Double.MAX_VALUE)),
                                Map.entry("scaleY", positiveNumber(Double.MAX_VALUE)),
                                Map.entry("sha256", string(64, 64)),
                                Map.entry("reportCount", integer(0, 256)),
                                Map.entry("reports", array(
                                        layoutReportSummarySchema(), 256)),
                                Map.entry("quiescence", layoutQuiescenceSummarySchema()),
                                Map.entry("diagnostics", array(
                                        comparisonDiagnosticSchema(), 256)),
                                Map.entry("elapsedMillis", integer(0, 2_000))),
                                List.of(
                                        "status", "reportCount", "reports",
                                        "diagnostics", "elapsedMillis"))),
                tool(AccessMode.MUTATING, "ui_trace_start", "Start bounded trace collection",
                        sessionInput(Map.of(
                                "maxDurationMillis", integer(1, 3_600_000),
                                "maxBytes", integer(1, 64L * 1_024 * 1_024)),
                                List.of("maxDurationMillis", "maxBytes")),
                        output("trace-started", Map.of(
                                "traceId", string(1, MAX_IDENTIFIER)), List.of("traceId"))),
                tool(AccessMode.MUTATING, "ui_trace_stop",
                        "Stop trace collection and return its opaque reference",
                        sessionInput(Map.of(), List.of()),
                        output("trace-stopped", Map.of(
                                "traceId", string(1, MAX_IDENTIFIER),
                                "traceReference", string(1, ProtocolJson.MAX_STRING_LENGTH),
                                "eventCount", integer(0, Long.MAX_VALUE),
                                "bytes", integer(0, 64L * 1_024 * 1_024)),
                                List.of("traceId", "traceReference", "eventCount", "bytes"))),
                tool(AccessMode.READ_ONLY, "ui_scenarios",
                        "List application-registered bounded scenarios",
                        sessionInput(Map.of(), List.of()),
                        output("scenarios-result", Map.of(
                                "available", Map.of("type", "boolean"),
                                "scenarios", array(scenarioDefinitionSchema(), 256)),
                                List.of("available", "scenarios"))),
                tool(AccessMode.MUTATING, "ui_scenario_start",
                        "Start one registered scenario using only bounded deterministic inputs",
                        sessionInput(Map.of(
                                "scenarioId", string(1, MAX_IDENTIFIER),
                                "seed", integer(Long.MIN_VALUE, Long.MAX_VALUE),
                                "configuration", configurationSchema(),
                                "profileId", string(1, MAX_IDENTIFIER),
                                "deadlineMillis", integer(
                                        1, HarnessRequest.MAX_SCENARIO_DEADLINE_MILLIS)),
                                List.of("scenarioId", "seed", "configuration", "profileId",
                                        "deadlineMillis")),
                        output("scenario-start-result", Map.of(
                                "outcome", scenarioStartOutcomeSchema()),
                                List.of("outcome"))),
                tool(AccessMode.READ_ONLY, "ui_navigation_inspect",
                        "Inspect one declared keyboard/controller focus path from a registered "
                                + "scenario using real configured input",
                        sessionInput(Map.of(
                                "spec", navigationSpecSchema()),
                                List.of("spec")),
                        output("navigation-result", navigationResultSchema(),
                                List.of("result"))),
                tool(AccessMode.MUTATING, "ui_navigation_validate",
                        "Validate one navigation path and reset through the registered scenario",
                        sessionInput(Map.of(
                                "spec", navigationSpecSchema()),
                                List.of("spec")),
                        output("navigation-result", navigationResultSchema(),
                                List.of("result"))),
                tool(AccessMode.READ_ONLY, "ui_validate_layout",
                        "Validate whole-stage or strict subtree layout invariants from one "
                                + "completed-frame observation",
                        layoutValidationInput(Map.of(
                                "spec", layoutValidationSpecSchema()),
                                List.of("spec")),
                        output("layout-validation-result", layoutValidationResultSchema(),
                                List.of("result"))),
                tool(AccessMode.MUTATING, "ui_matrix_run",
                        "Run one registered scenario and assertion set across a bounded "
                                + "display/locale matrix",
                        sessionInput(Map.of(
                                "spec", matrixRunSpecSchema()),
                                List.of("spec")),
                        output("matrix-run-started", Map.of(
                                "runId", string(1, MAX_IDENTIFIER)),
                                List.of("runId"))),
                tool(AccessMode.READ_ONLY, "ui_matrix_results",
                        "Retrieve the compact report for one matrix run",
                        sessionInput(Map.of(
                                "runId", string(1, MAX_IDENTIFIER)),
                                List.of("runId")),
                        output("matrix-report", matrixReportSchema(),
                                List.of("report"))),
                tool(AccessMode.READ_ONLY, "ui_runtime_compare",
                        "Compare one bound node's displayed value against its runtime "
                                + "observation with typed correlation",
                        locatorInput(Map.of(
                                "maxDurationMillis", integer(
                                        1, HarnessRequest.MAX_DEADLINE_MILLIS)),
                                List.of("maxDurationMillis")),
                        output("runtime-compare-result", Map.of(
                                "status", enumString(
                                        "EQUAL", "MISMATCH", "STALE", "UNCORRELATED",
                                        "MISSING", "UNAVAILABLE", "AMBIGUOUS"),
                                "entityId", string(1, MAX_IDENTIFIER),
                                "propertyId", string(1, MAX_IDENTIFIER),
                                "displayedValue", string(0, ProtocolJson.MAX_STRING_LENGTH),
                                "runtimeValue", nullableString(),
                                "displayedFrame", integer(0, Long.MAX_VALUE),
                                "runtimeFrame", nullableInt()),
                                List.of("status", "entityId", "propertyId",
                                        "displayedFrame"))),
                tool(AccessMode.READ_ONLY, "ui_trace_query",
                        "Query compact state-transition summaries from one retained bounded "
                                + "trace without downloading the archive",
                        sessionInput(Map.of(
                                "spec", traceQuerySpecSchema()),
                                List.of("spec")),
                        output("trace-query-result", Map.ofEntries(
                                Map.entry("traceId",
                                        string(1, ProtocolJson.MAX_STRING_LENGTH)),
                                Map.entry("transitions", array(transitionSchema(), 4_096)),
                                Map.entry("truncated", Map.of("type", "boolean")),
                                Map.entry("gapCount", integer(0, 1_024)),
                                Map.entry("unknownCauseCount", integer(0, 4_096))),
                                List.of("traceId", "transitions", "truncated", "gapCount",
                                        "unknownCauseCount"))),
                tool(AccessMode.READ_ONLY, "ui_semantic_compare",
                        "Compare a versioned registered semantic baseline against the current "
                                + "snapshot without raster capture",
                        sessionInput(Map.of(
                                "spec", semanticCompareSpecSchema()),
                                List.of("spec")),
                        output("semantic-compare-result", Map.of(
                                "matched", Map.of("type", "boolean"),
                                "differences", array(semanticDifferenceSchema(), 4_096),
                                "comparedNodes", integer(0, 10_000),
                                "truncated", Map.of("type", "boolean")),
                                List.of("matched", "differences", "comparedNodes", "truncated"))),
                tool(AccessMode.READ_ONLY, "ui_capabilities",
                        "Discover capabilities for one harness session",
                        sessionInput(Map.of(), List.of()),
                        output("capabilities-result", Map.ofEntries(
                                Map.entry("capabilities",
                                        array(string(1, MAX_IDENTIFIER), 256)),
                                Map.entry("catalogSchemaVersion",
                                        string(1, MAX_IDENTIFIER)),
                                Map.entry("operations",
                                        array(operationCatalogEntrySchema(), 256)),
                                Map.entry("diagnosticRegistryVersion",
                                        string(1, MAX_IDENTIFIER)),
                                Map.entry("diagnosticRegistry",
                                        array(diagnosticRegistryEntrySchema(), 256)),
                                Map.entry("recoveryPolicyVersion",
                                        string(1, MAX_IDENTIFIER)),
                                Map.entry("recoveryPolicy", recoveryPolicySchema())),
                                List.of("capabilities"))));

        LinkedHashMap<String, McpSchema.Tool> index = new LinkedHashMap<>();
        LinkedHashMap<String, AccessMode> modes = new LinkedHashMap<>();
        for (ToolDefinition definition : definitions) {
            index.put(definition.tool().name(), definition.tool());
            modes.put(definition.tool().name(), definition.mode());
        }
        tools = List.copyOf(definitions.stream().map(ToolDefinition::tool).toList());
        byName = Map.copyOf(index);
        accessModes = Map.copyOf(modes);
        examples = examples();
    }

    private static Map<String, Object> layoutReportSummarySchema() {
        return object(Map.of(
                "controlId", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "actorId", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "status", enumString(
                        "conformant", "non-conformant", "incomplete",
                        "not-diagnosable", "stale", "not-stable"),
                "diagnosticCount", integer(0, 256)),
                List.of("controlId", "actorId", "status", "diagnosticCount"));
    }

    private static Map<String, Object> layoutQuiescenceSummarySchema() {
        return object(Map.of(
                "settled", Map.of("type", "boolean"),
                "status", enumString("settled", "not-stable", "incomplete"),
                "stableFrameCount", integer(0, 125),
                "elapsedMillis", integer(0, 2_000),
                "sampleCount", integer(0, 125)),
                List.of(
                        "settled", "status", "stableFrameCount",
                        "elapsedMillis", "sampleCount"));
    }

    private static Map<String, Object> configurationSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "propertyNames", string(1, MAX_IDENTIFIER),
                "maxProperties", 256);
    }

    private static Map<String, Object> scenarioDefinitionSchema() {
        return object(Map.of(
                "schemaVersion", Map.of("const", 1, "type", "integer"),
                "id", string(1, MAX_IDENTIFIER),
                "definitionVersion", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "applicationId", string(1, MAX_IDENTIFIER),
                "supportedProfileIds", array(string(1, MAX_IDENTIFIER), 256),
                "maxSetupAttempts", integer(1, 16),
                "maxDurationMillis", integer(1, 600_000)),
                List.of("schemaVersion", "id", "definitionVersion", "applicationId",
                        "supportedProfileIds", "maxSetupAttempts", "maxDurationMillis"));
    }

    private static Map<String, Object> layoutValidationSpecSchema() {
        return object(Map.ofEntries(
                Map.entry("targetMode", enumString("stage", "subtree")),
                Map.entry("locator", nullableLocator()),
                Map.entry("enabledChecks", array(enumString(
                        "outside-viewport", "clipped-text", "interactive-overlap", "zero-size",
                        "below-target-size", "duplicate-test-id", "missing-accessible-name",
                        "keyboard-unreachable", "obscured", "invalid-clip-scroll",
                        "inconsistent-alignment", "inconsistent-spacing"), 32)),
                Map.entry("minTargetWidth", number(0, Double.MAX_VALUE)),
                Map.entry("minTargetHeight", number(0, Double.MAX_VALUE)),
                Map.entry("maxAlignmentDelta", number(0, Double.MAX_VALUE)),
                Map.entry("minSpacing", number(0, Double.MAX_VALUE)),
                Map.entry("failOn", enumString("info", "warning", "error")),
                Map.entry("maxFindings", integer(1, 4_096)),
                Map.entry("maxNodes", integer(1, 10_000)),
                Map.entry("maxDurationMillis", integer(1, 3_600_000))),
                List.of("targetMode", "enabledChecks", "minTargetWidth", "minTargetHeight",
                        "maxAlignmentDelta", "minSpacing", "failOn", "maxFindings", "maxNodes",
                        "maxDurationMillis"));
    }

    private static Map<String, Object> nullableLocator() {
        return Map.of("oneOf", List.of(locatorRef(), Map.of("type", "null")));
    }

    private static Map<String, Object> locatorRef() {
        return Map.of("$ref", "#/$defs/locator");
    }

    private static Map<String, Object> layoutValidationResultSchema() {
        Map<String, Object> findingProperties = new LinkedHashMap<>();
        findingProperties.put("reason", enumString(java.util.Arrays.stream(
                dev.gdx.uiharness.core.layout.LayoutValidationReason.values())
                .map(Enum::name).toArray(String[]::new)));
        findingProperties.put("severity", enumString("INFO", "WARNING", "ERROR"));
        findingProperties.put("nodeId", string(1, ProtocolJson.MAX_STRING_LENGTH));
        findingProperties.put("relatedActorId", nullableString());
        findingProperties.put("stageBounds", object(Map.of(
                "x", number(0.0, Double.MAX_VALUE),
                "y", number(0.0, Double.MAX_VALUE),
                "width", number(0.0, Double.MAX_VALUE),
                "height", number(0.0, Double.MAX_VALUE)),
                List.of("x", "y", "width", "height")));
        findingProperties.put("evidence", string(1, ProtocolJson.MAX_STRING_LENGTH));
        Map<String, Object> resultProperties = new LinkedHashMap<>();
        resultProperties.put("status", enumString("PASS", "FAIL", "INCOMPLETE"));
        resultProperties.put("findings", array(
                object(findingProperties, List.of("reason", "severity", "nodeId", "evidence")),
                4_096));
        resultProperties.put("examinedNodes", integer(0, 10_000));
        resultProperties.put("truncated", Map.of("type", "boolean"));
        resultProperties.put("appliedConfig", object(Map.ofEntries(
                Map.entry("enabledChecks", array(enumString(java.util.Arrays.stream(
                        dev.gdx.uiharness.core.layout.LayoutValidationCheck.values())
                        .map(Enum::name).toArray(String[]::new)), 32)),
                Map.entry("minTargetWidth", number(0.0, Double.MAX_VALUE)),
                Map.entry("minTargetHeight", number(0.0, Double.MAX_VALUE)),
                Map.entry("maxAlignmentDelta", number(0.0, Double.MAX_VALUE)),
                Map.entry("minSpacing", number(0.0, Double.MAX_VALUE)),
                Map.entry("failOn", enumString("INFO", "WARNING", "ERROR")),
                Map.entry("maxFindings", integer(1, 4_096)),
                Map.entry("maxNodes", integer(1, 10_000))),
                List.of("enabledChecks", "minTargetWidth", "minTargetHeight",
                        "maxAlignmentDelta", "minSpacing", "failOn", "maxFindings",
                        "maxNodes")));
        return Map.ofEntries(Map.entry("result", object(resultProperties,
                List.of("status", "findings", "examinedNodes", "truncated"))));
    }

    private static Map<String, Object> transitionSchema() {
        return object(Map.ofEntries(
                Map.entry("kind", enumString(
                        "APPEARED", "DISAPPEARED", "ENABLED",
                        "DISABLED", "TEXT_CHANGED", "BOUNDS_CHANGED",
                        "FOCUS_CHANGED", "MODAL_CHANGED",
                        "OBSCURATION_CHANGED", "Z_ORDER_CHANGED",
                        "IDENTITY_AMBIGUOUS")),
                Map.entry("beforeSequence", integer(0, Long.MAX_VALUE)),
                Map.entry("afterSequence", integer(0, Long.MAX_VALUE)),
                Map.entry("beforeFrame", integer(0, Long.MAX_VALUE)),
                Map.entry("afterFrame", integer(0, Long.MAX_VALUE)),
                Map.entry("beforeRevision", integer(0, Long.MAX_VALUE)),
                Map.entry("afterRevision", integer(0, Long.MAX_VALUE)),
                Map.entry("actorIdentity", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("propertyPaths", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 16)),
                Map.entry("beforeValues", evidenceSchema()),
                Map.entry("afterValues", evidenceSchema()),
                Map.entry("causeSequence", nullableInt())),
                List.of("kind", "beforeSequence", "afterSequence", "beforeFrame",
                        "afterFrame", "beforeRevision", "afterRevision",
                        "actorIdentity", "propertyPaths", "beforeValues", "afterValues",
                        "causeSequence"));
    }

    private static Map<String, Object> traceQuerySpecSchema() {
        return object(Map.ofEntries(
                Map.entry("traceId", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("locator", nullableLocator()),
                Map.entry("kinds", array(enumString(
                        "appeared", "disappeared", "enabled", "disabled", "text-changed",
                        "bounds-changed", "focus-changed", "modal-changed",
                        "obscuration-changed", "z-order-changed", "identity-ambiguous"), 16)),
                Map.entry("propertyPaths", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 16)),
                Map.entry("frameFrom", nullableInt()),
                Map.entry("frameTo", nullableInt()),
                Map.entry("maxTransitions", integer(1, 4_096)),
                Map.entry("maxEvidenceBytes", integer(1, 1_048_576)),
                Map.entry("maxDurationMillis", integer(1, 3_600_000))),
                List.of("traceId", "kinds", "propertyPaths", "maxTransitions",
                        "maxEvidenceBytes", "maxDurationMillis"));
    }

    private static Map<String, Object> nullableInt() {
        return Map.of("oneOf", List.of(integer(0, Long.MAX_VALUE), Map.of("type", "null")));
    }

    private static Map<String, Object> semanticDifferenceSchema() {
        return object(Map.ofEntries(
                Map.entry("kind", enumString("ADDED", "REMOVED", "CHANGED", "AMBIGUOUS")),
                Map.entry("baselineKey", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("propertyPaths", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 16)),
                Map.entry("beforeValues", evidenceSchema()),
                Map.entry("afterValues", evidenceSchema()),
                Map.entry("ambiguousIdentities", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 16))),
                List.of("kind", "baselineKey", "propertyPaths", "beforeValues",
                        "afterValues", "ambiguousIdentities"));
    }

    private static Map<String, Object> semanticCompareSpecSchema() {
        return object(Map.ofEntries(
                Map.entry("baselineId", string(1, MAX_IDENTIFIER)),
                Map.entry("strictNodes", Map.of("type", "boolean")),
                Map.entry("tolerances", array(object(Map.of(
                        "id", string(1, MAX_IDENTIFIER),
                        "space", enumString("local", "stage", "screen", "framebuffer"),
                        "units", string(1, ProtocolJson.MAX_STRING_LENGTH),
                        "deltaX", number(0.0, Double.MAX_VALUE),
                        "deltaY", number(0.0, Double.MAX_VALUE),
                        "deltaWidth", number(0.0, Double.MAX_VALUE),
                        "deltaHeight", number(0.0, Double.MAX_VALUE)),
                        List.of("id", "space", "units")), 16)),
                Map.entry("excludedProperties", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 256)),
                Map.entry("maxDifferences", integer(1, 4_096)),
                Map.entry("maxDurationMillis", integer(1, 3_600_000))),
                List.of("baselineId", "strictNodes", "tolerances", "excludedProperties",
                        "maxDifferences", "maxDurationMillis"));
    }

    private static Map<String, Object> matrixRunSpecSchema() {
        return object(Map.ofEntries(
                Map.entry("scenarioId", string(1, MAX_IDENTIFIER)),
                Map.entry("windows", array(object(Map.of(
                        "width", integer(1, 16_384),
                        "height", integer(1, 16_384)),
                        List.of("width", "height")), 64)),
                Map.entry("uiScales", array(number(0.0, Double.MAX_VALUE), 64)),
                Map.entry("devicePixelRatios", array(number(0.0, Double.MAX_VALUE), 64)),
                Map.entry("hiDpiModes", array(enumString("LOGICAL", "PIXELS"), 64)),
                Map.entry("locales", array(string(1, MAX_IDENTIFIER), 64)),
                Map.entry("fontSetIds", array(string(1, MAX_IDENTIFIER), 64)),
                Map.entry("assertions", array(matrixAssertionSchema(), 256)),
                Map.entry("maxCases", integer(1, 10_000)),
                Map.entry("maxDurationMillis", integer(1, 3_600_000))),
                List.of("scenarioId", "windows", "uiScales", "devicePixelRatios",
                        "hiDpiModes", "locales", "maxCases", "maxDurationMillis"));
    }

    private static Map<String, Object> matrixAssertionSchema() {
        return object(Map.of(
                "locator", locatorRef(),
                "assertion", assertionRef()),
                List.of("locator", "assertion"));
    }

    private static Map<String, Object> assertionRef() {
        return Map.of("$ref", "#/$defs/assertion");
    }

    private static Map<String, Object> matrixReportSchema() {
        Map<String, Object> caseSummaryProperties = new LinkedHashMap<>();
        caseSummaryProperties.put("index", integer(0, 10_000));
        caseSummaryProperties.put("window", object(Map.of(
                "width", integer(1, 16_384),
                "height", integer(1, 16_384)),
                List.of("width", "height")));
        caseSummaryProperties.put("uiScale", number(0.0, Double.MAX_VALUE));
        caseSummaryProperties.put("devicePixelRatio", number(0.0, Double.MAX_VALUE));
        caseSummaryProperties.put("hiDpiMode", enumString("LOGICAL", "PIXELS"));
        caseSummaryProperties.put("locale", string(1, MAX_IDENTIFIER));
        caseSummaryProperties.put("fontSetId", nullableString());
        caseSummaryProperties.put("aspectRatio", number(0.0, Double.MAX_VALUE));
        Map<String, Object> resultProperties = new LinkedHashMap<>();
        resultProperties.put("caseSummary", object(caseSummaryProperties,
                List.of("index", "window", "uiScale", "devicePixelRatio",
                        "hiDpiMode", "locale", "aspectRatio")));
        resultProperties.put("status", enumString(
                "PASSED", "FAILED", "UNSTARTED", "CANCELLED",
                "UNSUPPORTED", "MISAPPLIED"));
        resultProperties.put("observedWindow", nullableObject(Map.of(
                "width", integer(1, 16_384),
                "height", integer(1, 16_384)),
                List.of("width", "height")));
        resultProperties.put("observedUiScale", nullableNumber());
        resultProperties.put("observedDevicePixelRatio", nullableNumber());
        resultProperties.put("observedHiDpiMode", nullableEnum(
                "LOGICAL", "PIXELS"));
        resultProperties.put("observedLocale", nullableString());
        resultProperties.put("observedFontSetId", nullableString());
        resultProperties.put("observedRestartProfileId", nullableString());
        resultProperties.put("passedAssertions", array(integer(0, 255), 256));
        resultProperties.put("failedAssertions", array(integer(0, 255), 256));
        resultProperties.put("artifactReferences", array(
                string(1, ProtocolJson.MAX_STRING_LENGTH), 64));
        resultProperties.put("evidence", string(0, 4_096));
        return Map.ofEntries(Map.entry("report", object(Map.ofEntries(
                Map.entry("runId", string(1, MAX_IDENTIFIER)),
                Map.entry("scenarioId", string(1, MAX_IDENTIFIER)),
                Map.entry("results", array(
                        object(resultProperties, List.of("caseSummary", "status")), 10_000)),
                Map.entry("truncated", Map.of("type", "boolean"))),
                List.of("runId", "scenarioId", "results", "truncated"))));
    }

    private static Map<String, Object> nullableNumber() {
        return Map.of("oneOf", List.of(
                number(0.0, Double.MAX_VALUE), Map.of("type", "null")));
    }

    private static Map<String, Object> nullableEnum(String... values) {
        return Map.of("oneOf", List.of(
                enumString(values), Map.of("type", "null")));
    }

    private static Map<String, Object> nullableObject(
            Map<String, Object> properties, List<String> required) {
        return Map.of("oneOf", List.of(
                object(properties, required), Map.of("type", "null")));
    }

    private static Map<String, Object> navigationSpecSchema() {
        return object(Map.ofEntries(
                Map.entry("scenarioId", string(1, MAX_IDENTIFIER)),
                Map.entry("seed", integer(Long.MIN_VALUE, Long.MAX_VALUE)),
                Map.entry("configuration", configurationSchema()),
                Map.entry("profileId", string(1, MAX_IDENTIFIER)),
                Map.entry("applicationId", string(1, MAX_IDENTIFIER)),
                Map.entry("processId", string(1, MAX_IDENTIFIER)),
                Map.entry("sessionId", string(1, MAX_IDENTIFIER)),
                Map.entry("inputs", array(enumString(
                        "tab", "shift-tab", "up", "down", "left", "right", "escape", "back",
                        "controller-up", "controller-down", "controller-left",
                        "controller-right", "controller-confirm", "controller-back"), 4_096)),
                Map.entry("startFocus", nullableString()),
                Map.entry("controllerSupported", Map.of("type", "boolean")),
                Map.entry("maxSteps", integer(1, 4_096)),
                Map.entry("maxActors", integer(1, 10_000)),
                Map.entry("maxResultBytes", integer(1, 1_048_576)),
                Map.entry("maxEvidenceBytes", integer(1, 1_048_576)),
                Map.entry("maxDurationMillis", integer(1, 3_600_000))),
                List.of("scenarioId", "seed", "configuration", "profileId", "applicationId",
                        "processId", "sessionId", "inputs", "controllerSupported", "maxSteps",
                        "maxActors", "maxResultBytes", "maxEvidenceBytes", "maxDurationMillis"));
    }

    private static Map<String, Object> navigationResultSchema() {
        return Map.ofEntries(
                Map.entry("result", object(Map.ofEntries(
                        Map.entry("schemaVersion", Map.of("const", 1, "type", "integer")),
                        Map.entry("path", object(Map.ofEntries(
                                Map.entry("schemaVersion", Map.of("const", 1, "type", "integer")),
                                Map.entry("defaultFocusIdentity", nullableString()),
                                Map.entry("steps", array(navigationStepSchema(), 4_096)),
                                Map.entry("reason", enumString(
                                        "COMPLETE", "CYCLE", "DEAD_END", "MODAL_ESCAPE",
                                        "FOCUS_LOST", "UNREACHABLE_CONTROL",
                                        "UNSUPPORTED_CONTROLLER_PATH", "DEADLINE",
                                        "TRUNCATED"))),
                                List.of("schemaVersion", "steps", "reason"))),
                        Map.entry("knownFocusables", array(
                                string(1, ProtocolJson.MAX_STRING_LENGTH), 10_000)),
                        Map.entry("unreachableFocusables", array(
                                string(1, ProtocolJson.MAX_STRING_LENGTH), 10_000)),
                        Map.entry("truncated", Map.of("type", "boolean"))),
                        List.of("schemaVersion", "path", "knownFocusables",
                                "unreachableFocusables", "truncated"))));
    }

    private static Map<String, Object> navigationStepSchema() {
        return object(Map.ofEntries(
                Map.entry("input", enumString(
                        "TAB", "SHIFT_TAB", "UP", "DOWN", "LEFT", "RIGHT", "ESCAPE", "BACK",
                        "CONTROLLER_UP", "CONTROLLER_DOWN", "CONTROLLER_LEFT",
                        "CONTROLLER_RIGHT", "CONTROLLER_CONFIRM", "CONTROLLER_BACK")),
                Map.entry("beforeFrame", integer(0, Long.MAX_VALUE)),
                Map.entry("beforeRevision", integer(0, Long.MAX_VALUE)),
                Map.entry("afterFrame", integer(0, Long.MAX_VALUE)),
                Map.entry("afterRevision", integer(0, Long.MAX_VALUE)),
                Map.entry("beforeIdentity", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("afterIdentity", nullableString()),
                Map.entry("modalBoundaryId", nullableString())),
                List.of("input", "beforeFrame", "beforeRevision", "afterFrame",
                        "afterRevision", "beforeIdentity"));
    }

    private static Map<String, Object> scenarioResultSchema() {
        return object(Map.ofEntries(
                Map.entry("schemaVersion", Map.of("const", 1, "type", "integer")),
                Map.entry("scenarioId", string(1, MAX_IDENTIFIER)),
                Map.entry("definitionVersion", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("configurationDigest", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("seed", integer(Long.MIN_VALUE, Long.MAX_VALUE)),
                Map.entry("applicationId", string(1, MAX_IDENTIFIER)),
                Map.entry("processId", string(1, MAX_IDENTIFIER)),
                Map.entry("sessionId", string(1, MAX_IDENTIFIER)),
                Map.entry("startFrame", integer(0, Long.MAX_VALUE)),
                Map.entry("startRevision", integer(0, Long.MAX_VALUE)),
                Map.entry("readyFrame", integer(0, Long.MAX_VALUE)),
                Map.entry("readyRevision", integer(0, Long.MAX_VALUE)),
                Map.entry("profileId", string(1, MAX_IDENTIFIER)),
                Map.entry("startStateIdentity", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("elapsedMillis", integer(0, 600_000)),
                Map.entry("setupAttempts", integer(0, 16)),
                Map.entry("cleanupCompleted", Map.of("type", "boolean")),
                Map.entry("failure", enumString(java.util.Arrays.stream(
                                HarnessResponse.ScenarioFailureData.values())
                        .map(HarnessResponse.ScenarioFailureData::wireName)
                        .toArray(String[]::new)))),
                List.of("schemaVersion", "scenarioId", "definitionVersion",
                        "configurationDigest", "seed", "applicationId", "processId",
                        "sessionId", "startFrame", "startRevision", "readyFrame",
                        "readyRevision", "profileId", "startStateIdentity", "elapsedMillis",
                        "setupAttempts", "cleanupCompleted"));
    }

    private static Map<String, Object> scenarioStartOutcomeSchema() {
        return Map.of("oneOf", List.of(
                object(Map.of("kind", Map.of("const", "unavailable", "type", "string")),
                        List.of("kind")),
                object(Map.of(
                        "kind", Map.of("const", "rejected", "type", "string"),
                        "reason", enumString(
                                "unknown-scenario", "incompatible-scenario",
                                "unsupported-profile")),
                        List.of("kind", "reason")),
                object(Map.of(
                        "kind", Map.of("const", "failed", "type", "string"),
                        "reason", enumString("deadline", "cancelled")),
                        List.of("kind", "reason")),
                object(Map.of(
                        "kind", Map.of("const", "completed", "type", "string"),
                        "scenario", scenarioResultSchema(),
                        "reconnectIdentity", string(1, MAX_IDENTIFIER)),
                        List.of("kind", "scenario"))));
    }

    /** Returns catalog order used by MCP tools/list. */
    public List<McpSchema.Tool> tools() {
        return tools;
    }

    /** Returns the exact approved tool-name set. */
    public Set<String> toolNames() {
        return byName.keySet();
    }

    /** Finds one approved tool or throws for an unknown name. */
    public McpSchema.Tool tool(String name) {
        McpSchema.Tool tool = byName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool;
    }

    /** Returns the single access classification for one approved tool or throws for an unknown name. */
    public AccessMode accessMode(String name) {
        AccessMode mode = accessModes.get(name);
        if (mode == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return mode;
    }

    /** Returns the versioned bounded operation, schema, output, and example catalog. */
    public List<Map<String, Object>> operationCatalog() {
        return tools.stream().map(tool -> Map.<String, Object>of(
                "name", tool.name(),
                "inputSchema", tool.inputSchema(),
                "outputSchema", tool.outputSchema(),
                "minimalExamples", examples.get(tool.name()))).toList();
    }

    /** Returns one minimal example selected by an optional tagged variant. */
    public Map<String, Object> minimalExample(String name, Map<String, Object> arguments) {
        List<Map<String, Object>> values = examples.get(name);
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> selected;
        if ("ui_action".equals(name)
                && arguments.get("action") instanceof Map<?, ?> action
                && action.get("kind") instanceof String kind) {
            selected = values.stream()
                    .filter(example -> example.get("action") instanceof Map<?, ?> exampleAction
                            && kind.equals(exampleAction.get("kind")))
                    .findFirst()
                    .orElse(values.getFirst());
        } else {
            selected = values.getFirst();
        }
        LinkedHashMap<String, Object> contextual = new LinkedHashMap<>(selected);
        Object sessionId = arguments.get("sessionId");
        if (sessionId instanceof String session
                && !session.isBlank() && session.length() <= MAX_IDENTIFIER) {
            contextual.put("sessionId", session);
        }
        return Map.copyOf(contextual);
    }

    /** Returns the fixed recovery policy advertised to agent clients. */
    public static RecoveryPolicy recoveryPolicy() {
        return new RecoveryPolicy(3, 3, 3, 1, 1, 30_000);
    }

    /** Returns the stable diagnostic registry projection. */
    public static List<DiagnosticCode.Entry> diagnosticRegistry() {
        return DiagnosticCode.registry();
    }

    private static Map<String, Object> operationCatalogEntrySchema() {
        return object(Map.of(
                "name", string(1, MAX_IDENTIFIER),
                "inputSchema", Map.of("type", "object"),
                "outputSchema", Map.of("type", "object"),
                "minimalExamples", array(Map.of("type", "object"), 256)),
                List.of("name", "inputSchema", "outputSchema", "minimalExamples"));
    }

    private static Map<String, Object> diagnosticRegistryEntrySchema() {
        return object(Map.of(
                "code", enumString(java.util.Arrays.stream(DiagnosticCode.values())
                        .map(Enum::name).toArray(String[]::new)),
                "disposition", enumString("transient", "terminal"),
                "retryable", Map.of("type", "boolean"),
                "meaning", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                List.of("code", "disposition", "retryable", "meaning"));
    }

    private static Map<String, Object> recoveryPolicySchema() {
        return object(Map.of(
                "maxSchemaRecoveries", integer(1, Integer.MAX_VALUE),
                "maxStateRetries", integer(1, Integer.MAX_VALUE),
                "maxUnchangedInspectCycles", integer(1, Integer.MAX_VALUE),
                "maxUnchangedBuilds", integer(1, Integer.MAX_VALUE),
                "maxUnchangedLaunches", integer(1, Integer.MAX_VALUE),
                "maxWallTimeMillis", integer(1, Long.MAX_VALUE)),
                List.of(
                        "maxSchemaRecoveries", "maxStateRetries",
                        "maxUnchangedInspectCycles", "maxUnchangedBuilds",
                        "maxUnchangedLaunches", "maxWallTimeMillis"));
    }

    private static Map<String, List<Map<String, Object>>> examples() {
        Map<String, Object> session = Map.of("sessionId", "SESSION");
        Map<String, Object> roleButton =
                Map.of("kind", "role", "role", "button");
        LinkedHashMap<String, List<Map<String, Object>>> values = new LinkedHashMap<>();
        values.put("ui_sessions", List.of(Map.of()));
        values.put("ui_snapshot", List.of(session));
        values.put("ui_query", List.of(Map.of(
                "sessionId", "SESSION", "locator", roleButton)));
        values.put("ui_action", List.of(
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of(
                                "kind", "click", "pointer", 0,
                                "button", 0, "force", false)),
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of("kind", "hover", "force", false)),
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of("kind", "focus", "force", false)),
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of(
                                "kind", "fill", "value", "", "force", false)),
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of(
                                "kind", "press", "keycode", 66, "force", false)),
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of(
                                "kind", "scroll", "amountX", 0, "amountY", 1,
                                "force", false)),
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of(
                                "kind", "drag", "deltaX", 10, "deltaY", 0,
                                "pointer", 0, "button", 0, "force", false)),
                Map.of(
                        "sessionId", "SESSION",
                        "locator", roleButton,
                        "action", Map.of(
                                "kind", "pointer", "phase", "move",
                                "offsetX", 0, "offsetY", 0,
                                "pointer", 0, "button", 0, "force", false))));
        values.put("ui_assert", assertionExamples(roleButton));
        values.put("ui_wait", List.of(Map.of(
                "sessionId", "SESSION", "locator", roleButton,
                "condition", "visible")));
        values.put("ui_screenshot", List.of(Map.of(
                "sessionId", "SESSION",
                "maxWidth", 1280,
                "maxHeight", 720,
                "maxPixels", 921_600,
                "maxPngBytes", 4_194_304)));
        values.put("ui_inspect_compare", List.of(Map.ofEntries(
                Map.entry("sessionId", "SESSION"),
                Map.entry("referenceId", "REFERENCE"),
                Map.entry("policyId", "pixel-exact"),
                Map.entry("policyVersion", 1),
                Map.entry("viewportId", "1280x720"),
                Map.entry("maxIterations", 1),
                Map.entry("maxDurationMillis", 30_000),
                Map.entry("maxWidth", 1280),
                Map.entry("maxHeight", 720),
                Map.entry("maxPixels", 921_600),
                Map.entry("maxPngBytes", 4_194_304))));
        values.put("ui_typography_diagnose", List.of(Map.ofEntries(
                Map.entry("sessionId", "SESSION"),
                Map.entry("referenceId", "REFERENCE"),
                Map.entry("viewportId", "1280x720"),
                Map.entry("maxDurationMillis", 30_000),
                Map.entry("maxResults", 16),
                Map.entry("maxWidth", 1280),
                Map.entry("maxHeight", 720),
                Map.entry("maxPixels", 921_600),
                Map.entry("maxPngBytes", 4_194_304))));
        values.put("ui_layout_diagnose", List.of(Map.ofEntries(
                Map.entry("sessionId", "SESSION"),
                Map.entry("referenceId", "REFERENCE"),
                Map.entry("viewportId", "1280x720"),
                Map.entry("maxDurationMillis", 2_000),
                Map.entry("maxResults", 16),
                Map.entry("maxWidth", 1280),
                Map.entry("maxHeight", 720),
                Map.entry("maxPixels", 921_600),
                Map.entry("maxPngBytes", 4_194_304))));
        values.put("ui_trace_start", List.of(Map.of(
                "sessionId", "SESSION",
                "maxDurationMillis", 30_000,
                "maxBytes", 1_048_576)));
        values.put("ui_trace_stop", List.of(session));
        Map<String, Object> navigationSpec = Map.ofEntries(
                Map.entry("scenarioId", "navigation"),
                Map.entry("seed", 7),
                Map.entry("configuration", Map.of("locale", "en")),
                Map.entry("profileId", "desktop"),
                Map.entry("applicationId", "app"),
                Map.entry("processId", "process"),
                Map.entry("sessionId", "SESSION"),
                Map.entry("inputs", List.of("tab", "tab")),
                Map.entry("controllerSupported", true),
                Map.entry("maxSteps", 16),
                Map.entry("maxActors", 16),
                Map.entry("maxResultBytes", 262144),
                Map.entry("maxEvidenceBytes", 262144),
                Map.entry("maxDurationMillis", 5000));
        values.put("ui_navigation_inspect", List.of(Map.of(
                "sessionId", "SESSION", "spec", navigationSpec)));
        values.put("ui_navigation_validate", List.of(Map.of(
                "sessionId", "SESSION", "spec", navigationSpec)));
        Map<String, Object> layoutSpec = Map.ofEntries(
                Map.entry("targetMode", "stage"),
                Map.entry("enabledChecks", List.of("zero-size", "duplicate-test-id")),
                Map.entry("minTargetWidth", 64.0),
                Map.entry("minTargetHeight", 64.0),
                Map.entry("maxAlignmentDelta", 1.0),
                Map.entry("minSpacing", 1.0),
                Map.entry("failOn", "error"),
                Map.entry("maxFindings", 256),
                Map.entry("maxNodes", 10000),
                Map.entry("maxDurationMillis", 2000));
        values.put("ui_runtime_compare", List.of(Map.of(
                "sessionId", "SESSION",
                "locator", roleButton,
                "maxDurationMillis", 2000)));
        values.put("ui_trace_query", List.of(Map.of(
                "sessionId", "SESSION",
                "spec", Map.ofEntries(
                        Map.entry("traceId", "trace-1"),
                        Map.entry("kinds", List.of("appeared", "disabled")),
                        Map.entry("propertyPaths", List.of()),
                        Map.entry("maxTransitions", 128),
                        Map.entry("maxEvidenceBytes", 65536),
                        Map.entry("maxDurationMillis", 2000)))));
        values.put("ui_semantic_compare", List.of(Map.of(
                "sessionId", "SESSION",
                "spec", Map.ofEntries(
                        Map.entry("baselineId", "save-golden"),
                        Map.entry("strictNodes", false),
                        Map.entry("tolerances", List.of()),
                        Map.entry("excludedProperties", List.of()),
                        Map.entry("maxDifferences", 256),
                        Map.entry("maxDurationMillis", 2000)))));
        values.put("ui_matrix_run", List.of(Map.of(
                "sessionId", "SESSION",
                "spec", Map.ofEntries(
                        Map.entry("scenarioId", "matrix"),
                        Map.entry("windows", List.of(Map.of("width", 1280, "height", 720))),
                        Map.entry("uiScales", List.of(1.0)),
                        Map.entry("devicePixelRatios", List.of(1.0)),
                        Map.entry("hiDpiModes", List.of("LOGICAL")),
                        Map.entry("locales", List.of("en")),
                        Map.entry("fontSetIds", List.of()),
                        Map.entry("assertions", List.of()),
                        Map.entry("maxCases", 10000),
                        Map.entry("maxDurationMillis", 2000)))));
        values.put("ui_matrix_results", List.of(Map.of(
                "sessionId", "SESSION", "runId", "matrix-000000000000")));
        values.put("ui_validate_layout", List.of(Map.of(
                "sessionId", "SESSION", "spec", layoutSpec)));
        values.put("ui_scenarios", List.of(session));
        values.put("ui_scenario_start", List.of(Map.of(
                "sessionId", "SESSION",
                "scenarioId", "SCENARIO",
                "seed", 0,
                "configuration", Map.of(),
                "profileId", "PROFILE",
                "deadlineMillis", 600_000)));
        values.put("ui_capabilities", List.of(session));
        return Map.copyOf(values);
    }

    private static List<Map<String, Object>> assertionExamples(
            Map<String, Object> locator) {
        List<Map<String, Object>> assertions = List.of(
                Map.of("kind", "visible"),
                Map.of("kind", "hidden"),
                Map.of("kind", "enabled"),
                Map.of("kind", "disabled"),
                Map.of("kind", "focused"),
                Map.of("kind", "checked"),
                Map.of("kind", "text-equals", "expected", "Ready"),
                Map.of("kind", "text-contains", "expected", "ead"),
                Map.of("kind", "count-equals", "expected", 1),
                Map.of("kind", "bounds-inside-viewport", "viewport",
                        Map.of("x", 0, "y", 0, "width", 1280, "height", 720)),
                Map.of("kind", "does-not-overlap", "other",
                        Map.of("kind", "test-id", "testId", "dialog")),
                Map.of("kind", "stable-for-frames", "frames", 3,
                        "properties", List.of("bounds")),
                Map.of("kind", "accessible-name-exists"));
        return assertions.stream().map(assertion -> Map.<String, Object>of(
                "sessionId", "SESSION",
                "schemaVersion", 1,
                "deadlineMillis", 30_000,
                "locator", locator,
                "assertion", assertion)).toList();
    }

    private static ToolDefinition tool(AccessMode mode, String name, String description,
            Map<String, Object> input, Map<String, Object> output) {
        return new ToolDefinition(
                McpSchema.Tool.builder(name, input)
                        .description(description)
                        .outputSchema(output)
                        .build(),
                mode);
    }

    private static Map<String, Object> sessionsInput() {
        return envelope(Map.of(), List.of(), false, ignored -> {});
    }

    private static Map<String, Object> sessionInput(
            Map<String, Object> additions, List<String> required) {
        return envelope(additions, required, true, ignored -> {});
    }

    private static Map<String, Object> layoutValidationInput(
            Map<String, Object> additions, List<String> required) {
        return envelope(additions, required, true,
                schema -> schema.put("$defs", locatorDefinitions()));
    }

    private static Map<String, Object> locatorInput(
            Map<String, Object> additions, List<String> required) {
        return locatorInput(additions, required, true);
    }

    private static Map<String, Object> locatorInput(
            Map<String, Object> additions, List<String> required, boolean locatorRequired) {
        List<String> allRequired = new ArrayList<>(required);
        if (locatorRequired) {
            allRequired.add("locator");
        }
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>(additions);
        properties.put("locator", Map.of("$ref", "#/$defs/locator"));
        return envelope(properties, allRequired, true,
                schema -> schema.put("$defs", locatorDefinitions()));
    }

    private static Map<String, Object> envelope(Map<String, Object> additions,
            List<String> required, boolean sessionRequired,
            Consumer<LinkedHashMap<String, Object>> customizer) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        if (sessionRequired) {
            properties.put("sessionId", string(1, MAX_IDENTIFIER));
        }
        properties.put("deadlineMillis", integer(1, HarnessRequest.MAX_DEADLINE_MILLIS));
        properties.putAll(additions);
        List<String> allRequired = new ArrayList<>();
        if (sessionRequired) {
            allRequired.add("sessionId");
        }
        allRequired.addAll(required);
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>(object(properties, allRequired));
        customizer.accept(schema);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> assertionInput() {
        LinkedHashMap<String, Object> definitions = new LinkedHashMap<>(locatorDefinitions());
        definitions.put("assertion", assertionSchema());
        return envelope(Map.of(
                "schemaVersion", Map.of("type", "integer", "const", 1),
                "locator", Map.of("$ref", "#/$defs/locator"),
                "assertion", Map.of("$ref", "#/$defs/assertion")),
                List.of("schemaVersion", "locator", "assertion", "deadlineMillis"),
                true,
                schema -> schema.put("$defs", Map.copyOf(definitions)));
    }

    private static Map<String, Object> assertionOutput() {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>(output(
                "assertion-result",
                Map.ofEntries(
                        Map.entry("schemaVersion", Map.of("type", "integer", "const", 1)),
                        Map.entry("outcome", enumString("passed", "failed")),
                        Map.entry("locator", Map.of("$ref", "#/$defs/locator")),
                        Map.entry("assertion", Map.of("$ref", "#/$defs/assertion")),
                        Map.entry("nodeId", string(0, MAX_IDENTIFIER)),
                        Map.entry("expected", string(0, ProtocolJson.MAX_STRING_LENGTH)),
                        Map.entry("lastObserved", string(0, ProtocolJson.MAX_STRING_LENGTH)),
                        Map.entry("actionability", enumString("satisfied", "retryable")),
                        Map.entry("revision", integer(0, Long.MAX_VALUE)),
                        Map.entry("frame", integer(0, Long.MAX_VALUE)),
                        Map.entry("elapsedMillis", integer(0, HarnessRequest.MAX_DEADLINE_MILLIS)),
                        Map.entry("candidates", array(evidenceSchema(), 1_000)),
                        Map.entry("truncated", Map.of("type", "boolean")),
                        Map.entry("traceId", nullableString())),
                List.of("schemaVersion", "outcome", "locator", "assertion", "nodeId",
                        "expected", "lastObserved", "actionability", "revision", "frame",
                        "elapsedMillis", "candidates", "truncated")));
        LinkedHashMap<String, Object> definitions = new LinkedHashMap<>(locatorDefinitions());
        definitions.put("assertion", assertionSchema());
        schema.put("$defs", Map.copyOf(definitions));
        return Map.copyOf(schema);
    }

    private static Map<String, Object> assertionSchema() {
        Map<String, Object> locatorRef = Map.of("$ref", "#/$defs/locator");
        Map<String, Object> viewport = object(Map.of(
                "x", number(-Double.MAX_VALUE, Double.MAX_VALUE),
                "y", number(-Double.MAX_VALUE, Double.MAX_VALUE),
                "width", number(0, Double.MAX_VALUE),
                "height", number(0, Double.MAX_VALUE)),
                List.of("x", "y", "width", "height"));
        return Map.of("oneOf", List.of(
                tagged("visible", Map.of(), List.of()),
                tagged("hidden", Map.of(), List.of()),
                tagged("enabled", Map.of(), List.of()),
                tagged("disabled", Map.of(), List.of()),
                tagged("focused", Map.of(), List.of()),
                tagged("checked", Map.of(), List.of()),
                tagged("text-equals", Map.of(
                        "expected", string(0, ProtocolJson.MAX_STRING_LENGTH)),
                        List.of("expected")),
                tagged("text-contains", Map.of(
                        "expected", string(0, ProtocolJson.MAX_STRING_LENGTH)),
                        List.of("expected")),
                tagged("count-equals", Map.of(
                        "expected", integer(0, Integer.MAX_VALUE)), List.of("expected")),
                tagged("bounds-inside-viewport", Map.of("viewport", viewport),
                        List.of("viewport")),
                tagged("does-not-overlap", Map.of("other", locatorRef), List.of("other")),
                tagged("stable-for-frames", Map.of(
                        "frames", integer(1, 10_000),
                        "properties", Map.of(
                                "type", "array",
                                "items", enumString("bounds", "text", "accessible-name",
                                        "visible", "enabled", "checked", "focused"),
                                "minItems", 1,
                                "maxItems", 7,
                                "uniqueItems", true)),
                        List.of("frames", "properties")),
                tagged("accessible-name-exists", Map.of(), List.of())));
    }

    private static Map<String, Object> locatorDefinitions() {
        Map<String, Object> locatorRef = Map.of("$ref", "#/$defs/locator");
        Map<String, Object> matchRef = Map.of("$ref", "#/$defs/textMatch");
        Map<String, Object> filterRef = Map.of("$ref", "#/$defs/filter");
        LinkedHashMap<String, Object> definitions = new LinkedHashMap<>();
        definitions.put("textMatch", object(Map.of(
                "mode", enumString("exact", "case-insensitive-exact", "substring", "regex"),
                "source", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                List.of("mode", "source")));
        definitions.put("filter", Map.of("oneOf", List.of(
                tagged("name", Map.of("match", matchRef), List.of("match")),
                tagged("has", Map.of("locator", locatorRef), List.of("locator")),
                tagged("has-text", Map.of("match", matchRef), List.of("match")),
                tagged("state", Map.of(
                        "state", enumString("visible", "touchable", "enabled", "checked",
                                "selected", "expanded", "editable", "focused", "focusable",
                                "clipped", "viewport-intersecting", "hit-target"),
                        "expected", Map.of("type", "boolean")),
                        List.of("state", "expected")))));
        definitions.put("locator", Map.of("oneOf", List.of(
                tagged("role", Map.of("role", semanticRoleSchema()), List.of("role")),
                tagged("text", Map.of(
                        "field", enumString("text", "label"), "match", matchRef),
                        List.of("field", "match")),
                tagged("test-id", Map.of(
                        "testId", string(1, ProtocolJson.MAX_STRING_LENGTH)), List.of("testId")),
                tagged("actor", Map.of(
                        "field", enumString("name", "type"), "match", matchRef),
                        List.of("field", "match")),
                tagged("relation", Map.of(
                        "anchor", locatorRef, "target", locatorRef,
                        "relation", enumString("child", "descendant", "parent", "sibling")),
                        List.of("anchor", "target", "relation")),
                tagged("filter", Map.of("locator", locatorRef, "filter", filterRef),
                        List.of("locator", "filter")),
                tagged("index", Map.of("locator", locatorRef,
                        "index", integer(0, Integer.MAX_VALUE)), List.of("locator", "index")))));
        return Map.copyOf(definitions);
    }

    private static Map<String, Object> actionSchema() {
        return Map.of("oneOf", List.of(
                tagged("click", Map.of(
                        "pointer", integer(0, Integer.MAX_VALUE),
                        "button", integer(Integer.MIN_VALUE, Integer.MAX_VALUE),
                        "force", Map.of("type", "boolean")),
                        List.of("pointer", "button", "force")),
                tagged("hover", Map.of("force", Map.of("type", "boolean")), List.of("force")),
                tagged("focus", Map.of("force", Map.of("type", "boolean")), List.of("force")),
                tagged("fill", Map.of(
                        "value", string(0, ProtocolJson.MAX_STRING_LENGTH),
                        "force", Map.of("type", "boolean")), List.of("value", "force")),
                tagged("press", Map.of(
                        "keycode", integer(0, Integer.MAX_VALUE),
                        "force", Map.of("type", "boolean")), List.of("keycode", "force")),
                tagged("scroll", Map.of(
                        "amountX", number(-Float.MAX_VALUE, Float.MAX_VALUE),
                        "amountY", number(-Float.MAX_VALUE, Float.MAX_VALUE),
                        "force", Map.of("type", "boolean")),
                        List.of("amountX", "amountY", "force")),
                tagged("drag", Map.of(
                        "deltaX", number(-Float.MAX_VALUE, Float.MAX_VALUE),
                        "deltaY", number(-Float.MAX_VALUE, Float.MAX_VALUE),
                        "pointer", integer(0, Integer.MAX_VALUE),
                        "button", integer(Integer.MIN_VALUE, Integer.MAX_VALUE),
                        "force", Map.of("type", "boolean")),
                        List.of("deltaX", "deltaY", "pointer", "button", "force")),
                tagged("pointer", Map.of(
                        "phase", enumString("down", "move", "up"),
                        "offsetX", number(-Float.MAX_VALUE, Float.MAX_VALUE),
                        "offsetY", number(-Float.MAX_VALUE, Float.MAX_VALUE),
                        "pointer", integer(0, Integer.MAX_VALUE),
                        "button", integer(Integer.MIN_VALUE, Integer.MAX_VALUE),
                        "force", Map.of("type", "boolean")),
                        List.of("phase", "offsetX", "offsetY", "pointer", "button", "force"))));
    }

    private static Map<String, Object> output(String kind, Map<String, Object> additions,
            List<String> required) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", Map.of("const", kind, "type", "string"));
        properties.put("progress", progressSchema());
        properties.put("recovery", recoverySnapshotSchema());
        properties.putAll(additions);
        ArrayList<String> allRequired = new ArrayList<>();
        allRequired.add("kind");
        allRequired.add("progress");
        allRequired.add("recovery");
        allRequired.addAll(required);
        return object(properties, allRequired);
    }

    private static Map<String, Object> progressSchema() {
        return object(Map.of(
                "status", enumString("available", "unavailable"),
                "dimensions", Map.of(
                        "type", "object",
                        "additionalProperties", enumString(
                                "changed", "unchanged", "unavailable"),
                        "maxProperties", 32),
                "ruleId", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                List.of("status", "dimensions", "ruleId"));
    }

    private static Map<String, Object> recoverySnapshotSchema() {
        return object(Map.of(
                "policyVersion", string(1, MAX_IDENTIFIER),
                "consumedBefore", integer(0, Long.MAX_VALUE),
                "consumed", integer(0, Long.MAX_VALUE),
                "limit", integer(1, Long.MAX_VALUE),
                "remainingBefore", integer(0, Long.MAX_VALUE),
                "remaining", integer(0, Long.MAX_VALUE),
                "elapsedMillis", integer(0, Long.MAX_VALUE),
                "maxWallTimeMillis", integer(1, Long.MAX_VALUE),
                "terminatingRule", string(1, MAX_IDENTIFIER)),
                List.of(
                        "policyVersion", "consumedBefore", "consumed", "limit",
                        "remainingBefore", "remaining",
                        "elapsedMillis", "maxWallTimeMillis", "terminatingRule"));
    }

    private static Map<String, Object> sessionSchema() {
        return object(Map.of(
                "sessionId", string(1, MAX_IDENTIFIER),
                "capabilities", array(string(1, MAX_IDENTIFIER), 256)),
                List.of("sessionId", "capabilities"));
    }

    private static Map<String, Object> comparisonMetricsSchema() {
        return object(Map.of(
                "differingPixels", integer(0, 33_554_432L),
                "meanAbsoluteError", number(0, 255),
                "maximumChannelDelta", integer(0, 255)),
                List.of(
                        "differingPixels", "meanAbsoluteError",
                        "maximumChannelDelta"));
    }

    private static Map<String, Object> typographyReportSchema() {
        return object(Map.ofEntries(
                Map.entry("controlId", string(1, MAX_IDENTIFIER)),
                Map.entry("actorId", string(1, MAX_IDENTIFIER)),
                Map.entry("status", enumString(
                        "pixel-sharp", "not-pixel-sharp", "incomplete",
                        "not-diagnosable", "stale", "not-stable")),
                Map.entry("text", string(0, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("textStart", integer(0, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("textEnd", integer(0, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("glyphRuns", array(glyphRunSchema(), 1_024)),
                Map.entry("revision", integer(0, Long.MAX_VALUE)),
                Map.entry("frame", integer(0, Long.MAX_VALUE)),
                Map.entry("currentArtifactId", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                Map.entry("captureSha256", string(64, 64)),
                Map.entry("transformSha256", string(64, 64)),
                Map.entry("font", fontObservationSchema()),
                Map.entry("display", displayObservationSchema()),
                Map.entry("transforms", array(affineTransformSchema(), 4)),
                Map.entry("origins", array(coordinatePointSchema(), 4)),
                Map.entry("baselines", array(coordinatePointSchema(), 4)),
                Map.entry("layoutBounds", array(coordinateBoundsSchema(), 4)),
                Map.entry("inkBounds", array(coordinateBoundsSchema(), 4)),
                Map.entry("fractionalTranslationX", number(-1, 1)),
                Map.entry("fractionalTranslationY", number(-1, 1)),
                Map.entry("rasterResidual", number(0, 255)),
                Map.entry("diagnostics", array(typographyDifferenceSchema(), 256)),
                Map.entry("sourceMechanisms", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 256)),
                Map.entry("controlledResults", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 256)),
                Map.entry("unresolvedHypotheses", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 256))),
                List.of(
                        "controlId", "actorId", "status", "text", "textStart", "textEnd",
                        "glyphRuns", "revision", "frame", "currentArtifactId",
                        "captureSha256", "transformSha256", "font", "display", "transforms",
                        "origins", "baselines", "layoutBounds", "inkBounds",
                        "fractionalTranslationX", "fractionalTranslationY",
                        "rasterResidual", "diagnostics", "sourceMechanisms",
                        "controlledResults", "unresolvedHypotheses"));
    }

    private static Map<String, Object> glyphRunSchema() {
        return object(Map.of(
                "textStart", integer(0, ProtocolJson.MAX_STRING_LENGTH),
                "textEnd", integer(0, ProtocolJson.MAX_STRING_LENGTH),
                "text", string(0, ProtocolJson.MAX_STRING_LENGTH),
                "origin", coordinatePointSchema(),
                "baseline", coordinatePointSchema(),
                "inkBounds", coordinateBoundsSchema()),
                List.of("textStart", "textEnd", "text", "origin", "baseline", "inkBounds"));
    }

    private static Map<String, Object> fontObservationSchema() {
        return object(Map.ofEntries(
                Map.entry("sourceId", evidenceValueSchema(
                        string(1, ProtocolJson.MAX_STRING_LENGTH))),
                Map.entry("atlasPageIds", array(
                        string(1, ProtocolJson.MAX_STRING_LENGTH), 256)),
                Map.entry("nominalSize", evidenceValueSchema(
                        positiveNumber(Double.MAX_VALUE))),
                Map.entry("generatedGlyphSize", evidenceValueSchema(
                        positiveNumber(Double.MAX_VALUE))),
                Map.entry("effectiveSizeX", positiveNumber(Double.MAX_VALUE)),
                Map.entry("effectiveSizeY", positiveNumber(Double.MAX_VALUE)),
                Map.entry("bitmapScaleX", positiveNumber(Double.MAX_VALUE)),
                Map.entry("bitmapScaleY", positiveNumber(Double.MAX_VALUE)),
                Map.entry("minificationFilter", evidenceValueSchema(
                        string(1, 256))),
                Map.entry("magnificationFilter", evidenceValueSchema(
                        string(1, 256))),
                Map.entry("distanceField", evidenceValueSchema(
                        string(1, ProtocolJson.MAX_STRING_LENGTH))),
                Map.entry("weight", evidenceValueSchema(number(-Double.MAX_VALUE,
                        Double.MAX_VALUE))),
                Map.entry("letterSpacing", evidenceValueSchema(number(
                        -Double.MAX_VALUE, Double.MAX_VALUE)))),
                List.of(
                        "sourceId", "atlasPageIds", "nominalSize", "generatedGlyphSize",
                        "effectiveSizeX", "effectiveSizeY", "bitmapScaleX", "bitmapScaleY",
                        "minificationFilter", "magnificationFilter", "distanceField",
                        "weight", "letterSpacing"));
    }

    private static Map<String, Object> evidenceValueSchema(Map<String, Object> valueSchema) {
        return Map.of("oneOf", List.of(
                object(Map.of(
                        "availability", Map.of("const", "available", "type", "string"),
                        "value", valueSchema),
                        List.of("availability", "value")),
                object(Map.of(
                        "availability", Map.of("const", "unavailable", "type", "string"),
                        "reason", enumString(
                                "unsupported", "not-registered", "not-exposed",
                                "missing", "unknown", "non-invertible"),
                        "detail", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                        List.of("availability", "reason", "detail"))));
    }

    private static Map<String, Object> displayObservationSchema() {
        return object(Map.ofEntries(
                Map.entry("applicationId", string(1, MAX_IDENTIFIER)),
                Map.entry("viewportId", string(1, MAX_IDENTIFIER)),
                Map.entry("windowWidth", integer(1, 8_192)),
                Map.entry("windowHeight", integer(1, 8_192)),
                Map.entry("logicalViewportWidth", integer(1, 8_192)),
                Map.entry("logicalViewportHeight", integer(1, 8_192)),
                Map.entry("framebufferWidth", integer(1, 8_192)),
                Map.entry("framebufferHeight", integer(1, 8_192)),
                Map.entry("deviceScaleX", positiveNumber(Double.MAX_VALUE)),
                Map.entry("deviceScaleY", positiveNumber(Double.MAX_VALUE))),
                List.of(
                        "applicationId", "viewportId", "windowWidth", "windowHeight",
                        "logicalViewportWidth", "logicalViewportHeight",
                        "framebufferWidth", "framebufferHeight",
                        "deviceScaleX", "deviceScaleY"));
    }

    private static Map<String, Object> affineTransformSchema() {
        return object(Map.ofEntries(
                Map.entry("source", coordinateSpaceSchema()),
                Map.entry("target", coordinateSpaceSchema()),
                Map.entry("m00", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("m01", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("translateX", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("m10", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("m11", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("translateY", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("effectiveScaleX", number(0, Double.MAX_VALUE)),
                Map.entry("effectiveScaleY", number(0, Double.MAX_VALUE)),
                Map.entry("rotationDegrees", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("shear", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                Map.entry("fractionalTranslationX", number(-1, 1)),
                Map.entry("fractionalTranslationY", number(-1, 1)),
                Map.entry("invertible", Map.of("type", "boolean"))),
                List.of(
                        "source", "target", "m00", "m01", "translateX",
                        "m10", "m11", "translateY", "effectiveScaleX", "effectiveScaleY",
                        "rotationDegrees", "shear", "fractionalTranslationX",
                        "fractionalTranslationY", "invertible"));
    }

    private static Map<String, Object> coordinatePointSchema() {
        return object(Map.of(
                "space", coordinateSpaceSchema(),
                "x", number(-Double.MAX_VALUE, Double.MAX_VALUE),
                "y", number(-Double.MAX_VALUE, Double.MAX_VALUE)),
                List.of("space", "x", "y"));
    }

    private static Map<String, Object> coordinateBoundsSchema() {
        return object(Map.of(
                "space", coordinateSpaceSchema(),
                "x", number(-Double.MAX_VALUE, Double.MAX_VALUE),
                "y", number(-Double.MAX_VALUE, Double.MAX_VALUE),
                "width", number(0, Double.MAX_VALUE),
                "height", number(0, Double.MAX_VALUE)),
                List.of("space", "x", "y", "width", "height"));
    }

    private static Map<String, Object> coordinateSpaceSchema() {
        return enumString("local", "parent", "stage", "screen", "framebuffer");
    }

    private static Map<String, Object> typographyDifferenceSchema() {
        return object(Map.of(
                "controlId", string(1, MAX_IDENTIFIER),
                "path", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "expected", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "observed", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "units", string(1, 256),
                "coordinateSpace", nullableString(),
                "referenceArtifactId", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "currentArtifactId", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                List.of(
                        "controlId", "path", "expected", "observed", "units",
                        "referenceArtifactId", "currentArtifactId"));
    }

    private static Map<String, Object> visualDifferenceSchema() {
        return object(Map.of(
                "category", enumString(
                        "text", "value", "bounds", "padding", "visibility",
                        "clipping", "raster-residual"),
                "controlId", nullableString(),
                "path", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "expected", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "observed", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "blocking", Map.of("type", "boolean")),
                List.of(
                        "category", "path",
                        "expected", "observed", "blocking"));
    }

    private static Map<String, Object> visualRegionSchema() {
        return object(Map.of(
                "category", enumString(
                        "text", "value", "bounds", "padding", "visibility",
                        "clipping", "raster-residual"),
                "controlId", nullableString(),
                "x", integer(0, 8_191),
                "y", integer(0, 8_191),
                "width", integer(1, 8_192),
                "height", integer(1, 8_192),
                "differingPixels", integer(0, 33_554_432L),
                "meanAbsoluteError", number(0, 255)),
                List.of(
                        "category", "x", "y", "width", "height",
                        "differingPixels", "meanAbsoluteError"));
    }

    private static Map<String, Object> comparisonDiagnosticSchema() {
        return object(Map.of(
                "code", string(1, MAX_IDENTIFIER),
                "path", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "expected", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "observed", string(1, ProtocolJson.MAX_STRING_LENGTH)),
                List.of("code", "path", "expected", "observed"));
    }

    private static Map<String, Object> nodeSummarySchema() {
        return object(Map.of(
                "id", string(1, MAX_IDENTIFIER),
                "role", semanticRoleSchema(),
                "accessibleName", nullableString(),
                "text", nullableString(),
                "testId", nullableString()), List.of("id", "role"));
    }

    private static Map<String, Object> evidenceSchema() {
        return Map.of("type", "object", "additionalProperties",
                string(0, ProtocolJson.MAX_STRING_LENGTH), "maxProperties", 256);
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"),
                "maxLength", ProtocolJson.MAX_STRING_LENGTH);
    }

    private static Map<String, Object> tagged(String tag, Map<String, Object> additions,
            List<String> required) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", Map.of("const", tag, "type", "string"));
        properties.putAll(additions);
        ArrayList<String> allRequired = new ArrayList<>();
        allRequired.add("kind");
        allRequired.addAll(required);
        return object(properties, allRequired);
    }

    private static Map<String, Object> object(
            Map<String, Object> properties, List<String> required) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.copyOf(properties));
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> array(Map<String, Object> items, int maximum) {
        return Map.of("type", "array", "items", items, "maxItems", maximum);
    }

    private static Map<String, Object> string(int minimum, int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }

    private static Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> integer(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> semanticRoleSchema() {
        return enumString("generic", "group", "button", "checkbox", "radio-button",
                "text-field", "text-area", "label", "image", "list", "list-item",
                "select", "slider", "progress-bar", "scroll-pane", "window", "dialog",
                "menu", "menu-item", "tooltip");
    }

    private static Map<String, Object> positiveNumber(double maximum) {
        return Map.of("type", "number", "exclusiveMinimum", 0, "maximum", maximum);
    }

    private static Map<String, Object> number(double minimum, double maximum) {
        return Map.of("type", "number", "minimum", minimum, "maximum", maximum);
    }
}
