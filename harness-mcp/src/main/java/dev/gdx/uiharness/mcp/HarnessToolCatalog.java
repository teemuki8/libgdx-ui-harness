package dev.gdx.uiharness.mcp;

import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolJson;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Immutable catalog of the twelve allowlisted MCP tools and their bounded JSON schemas. */
public final class HarnessToolCatalog {
    private static final int MAX_IDENTIFIER = 256;
    private static final Map<String, Object> ARTIFACT_SCHEMA = object(Map.of(
            "reference", string(1, ProtocolJson.MAX_STRING_LENGTH),
            "mediaType", string(1, 256),
            "byteLength", integer(0, ProtocolJson.MAX_RESPONSE_BYTES),
            "sha256", string(64, 64)),
            List.of("reference", "mediaType", "byteLength", "sha256"));

    private final List<McpSchema.Tool> tools;
    private final Map<String, McpSchema.Tool> byName;

    /** Builds the fixed V1 catalog. */
    public HarnessToolCatalog() {
        List<McpSchema.Tool> definitions = List.of(
                tool("ui_sessions", "List active harness sessions", sessionsInput(),
                        output("sessions-result", Map.of(
                                "sessions", array(sessionSchema(), 65_536),
                                "artifact", ARTIFACT_SCHEMA), List.of())),
                tool("ui_snapshot", "Capture a compact semantic snapshot summary",
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
                tool("ui_query", "Query a semantic locator", locatorInput(Map.of(), List.of()),
                        output("query-result", Map.of(
                                "matchCount", integer(0, Integer.MAX_VALUE),
                                "matches", array(nodeSummarySchema(), 65_536),
                                "evidence", array(evidenceSchema(), 65_536),
                                "artifact", ARTIFACT_SCHEMA), List.of("matchCount"))),
                tool("ui_action", "Perform one allowlisted UI action",
                        locatorInput(Map.of("action", actionSchema()), List.of("action")),
                        output("action-result", Map.of(
                                "beforeRevision", integer(0, Long.MAX_VALUE),
                                "afterRevision", integer(1, Long.MAX_VALUE),
                                "observedState", string(1, ProtocolJson.MAX_STRING_LENGTH),
                                "evidence", evidenceSchema(),
                                "artifact", ARTIFACT_SCHEMA),
                                List.of("beforeRevision", "afterRevision", "observedState"))),
                tool("ui_wait", "Wait for a bounded semantic condition",
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
                tool("ui_screenshot", "Capture a bounded screenshot as an opaque artifact",
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
                tool("ui_inspect_compare",
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
                                Map.entry("diagnostics", array(
                                        comparisonDiagnosticSchema(), 256))),
                                List.of(
                                        "status", "policy", "iterations",
                                        "elapsedMillis", "differences", "diagnostics"))),
                tool("ui_typography_diagnose",
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
                tool("ui_layout_diagnose",
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
                tool("ui_trace_start", "Start bounded trace collection",
                        sessionInput(Map.of(
                                "maxDurationMillis", integer(1, 3_600_000),
                                "maxBytes", integer(1, 64L * 1_024 * 1_024)),
                                List.of("maxDurationMillis", "maxBytes")),
                        output("trace-started", Map.of(
                                "traceId", string(1, MAX_IDENTIFIER)), List.of("traceId"))),
                tool("ui_trace_stop", "Stop trace collection and return its opaque reference",
                        sessionInput(Map.of(), List.of()),
                        output("trace-stopped", Map.of(
                                "traceId", string(1, MAX_IDENTIFIER),
                                "traceReference", string(1, ProtocolJson.MAX_STRING_LENGTH),
                                "eventCount", integer(0, Long.MAX_VALUE),
                                "bytes", integer(0, 64L * 1_024 * 1_024)),
                                List.of("traceId", "traceReference", "eventCount", "bytes"))),
                tool("ui_capabilities", "Discover capabilities for one harness session",
                        sessionInput(Map.of(), List.of()),
                        output("capabilities-result", Map.of(
                                "capabilities", array(string(1, MAX_IDENTIFIER), 256)),
                                List.of("capabilities"))));

        LinkedHashMap<String, McpSchema.Tool> index = new LinkedHashMap<>();
        definitions.forEach(tool -> index.put(tool.name(), tool));
        tools = List.copyOf(definitions);
        byName = Map.copyOf(index);
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

    private static McpSchema.Tool tool(String name, String description,
            Map<String, Object> input, Map<String, Object> output) {
        return McpSchema.Tool.builder(name, input)
                .description(description)
                .outputSchema(output)
                .build();
    }

    private static Map<String, Object> sessionsInput() {
        return envelope(Map.of(), List.of(), false, ignored -> {});
    }

    private static Map<String, Object> sessionInput(
            Map<String, Object> additions, List<String> required) {
        return envelope(additions, required, true, ignored -> {});
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
        properties.putAll(additions);
        ArrayList<String> allRequired = new ArrayList<>();
        allRequired.add("kind");
        allRequired.addAll(required);
        return object(properties, allRequired);
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
                        "raster-residual"),
                "controlId", nullableString(),
                "path", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "expected", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "observed", string(1, ProtocolJson.MAX_STRING_LENGTH),
                "blocking", Map.of("type", "boolean")),
                List.of(
                        "category", "path",
                        "expected", "observed", "blocking"));
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
