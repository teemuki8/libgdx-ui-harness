package dev.gdx.uiharness.fixtures;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal synchronous MCP client that sends real SDK JSON-RPC messages over process stdio. */
final class HarnessMcpClient implements Closeable {
    private static final ObjectMapper JSON = ProtocolJson.mapper();
    private static final String PROTOCOL_VERSION = "2025-11-25";

    private final BufferedReader input;
    private final BufferedWriter output;
    private long requestId;
    private boolean closed;

    private HarnessMcpClient(ReferenceProcess process) {
        input = new BufferedReader(new InputStreamReader(
                process.mcpInput(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(
                process.mcpOutput(), StandardCharsets.UTF_8));
    }

    static HarnessMcpClient connect(ReferenceProcess process) throws Exception {
        HarnessMcpClient client = new HarnessMcpClient(process);
        JsonNode initialized = client.request("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "reference-smoke", "version", "1.0")));
        if (!"libgdx-ui-harness".equals(initialized.at("/serverInfo/name").asText())) {
            client.close();
            throw new IllegalStateException("Unexpected MCP server identity: " + initialized);
        }
        client.notify("notifications/initialized", Map.of());
        JsonNode listed = client.request("tools/list", Map.of());
        if (listed.path("tools").size() != 23) {
            client.close();
            throw new IllegalStateException("Expected the twenty-three production tools: " + listed);
        }
        return client;
    }

    List<String> sessions() throws Exception {
        JsonNode content = call("ui_sessions", Map.of());
        ArrayList<String> sessions = new ArrayList<>();
        content.path("sessions").forEach(item -> sessions.add(item.path("sessionId").asText()));
        return List.copyOf(sessions);
    }

    List<String> capabilities(String sessionId) throws Exception {
        JsonNode content = call("ui_capabilities", Map.of("sessionId", sessionId));
        ArrayList<String> capabilities = new ArrayList<>();
        content.path("capabilities").forEach(item -> capabilities.add(item.asText()));
        return List.copyOf(capabilities);
    }

    JsonNode assertThat(
            String sessionId,
            Map<String, Object> locator,
            Map<String, Object> assertion,
            long deadlineMillis) throws Exception {
        JsonNode content = call("ui_assert", assertionArguments(
                sessionId, locator, assertion, deadlineMillis));
        requireKind(content, "assertion-result");
        return content;
    }

    JsonNode assertFailure(
            String sessionId,
            Map<String, Object> locator,
            Map<String, Object> assertion,
            long deadlineMillis) throws Exception {
        JsonNode result = request("tools/call", Map.of(
                "name", "ui_assert",
                "arguments", assertionArguments(
                        sessionId, locator, assertion, deadlineMillis)));
        if (!result.path("isError").asBoolean()) {
            throw new IllegalStateException("Expected MCP assertion failure: " + result);
        }
        JsonNode content = result.path("structuredContent");
        requireKind(content, "error");
        return content;
    }

    private static Map<String, Object> assertionArguments(
            String sessionId,
            Map<String, Object> locator,
            Map<String, Object> assertion,
            long deadlineMillis) {
        return Map.of(
                "sessionId", sessionId,
                "schemaVersion", 1,
                "locator", locator,
                "assertion", assertion,
                "deadlineMillis", deadlineMillis);
    }

    JsonNode scenarios(String sessionId) throws Exception {
        JsonNode content = call("ui_scenarios", Map.of("sessionId", sessionId));
        requireKind(content, "scenarios-result");
        return content;
    }

    JsonNode startScenario(
            String sessionId,
            String scenarioId,
            long seed,
            Map<String, String> configuration,
            String profileId,
            long deadlineMillis) throws Exception {
        JsonNode content = call("ui_scenario_start", Map.of(
                "sessionId", sessionId,
                "scenarioId", scenarioId,
                "seed", seed,
                "configuration", configuration,
                "profileId", profileId,
                "deadlineMillis", deadlineMillis));
        requireKind(content, "scenario-start-result");
        return content.path("outcome");
    }

    JsonNode cancelScenario(
            String sessionId, String scenarioId, String profileId, long deadlineMillis)
            throws Exception {
        long cancelledId = ++requestId;
        send(Map.of(
                "jsonrpc", "2.0",
                "id", cancelledId,
                "method", "tools/call",
                "params", Map.of(
                        "name", "ui_scenario_start",
                        "arguments", Map.of(
                                "sessionId", sessionId,
                                "scenarioId", scenarioId,
                                "seed", 0,
                                "configuration", Map.of(),
                                "profileId", profileId,
                                "deadlineMillis", deadlineMillis))));
        notify("notifications/cancelled", Map.of(
                "requestId", cancelledId, "reason", "fixture cancellation"));
        JsonNode pong = request("ping", Map.of());
        if (!pong.isObject()) {
            throw new IllegalStateException("MCP ping failed after cancellation");
        }
        return pong;
    }

    Snapshot snapshot(String sessionId) throws Exception {
        JsonNode content = call("ui_snapshot", Map.of("sessionId", sessionId));
        requireKind(content, "snapshot-summary");
        return new Snapshot(
                content.path("revision").asLong(),
                content.path("frame").asLong(),
                content.path("rootId").asText(),
                content.path("nodeCount").asInt());
    }

    void startTrace(String sessionId) throws Exception {
        JsonNode content = call("ui_trace_start", Map.of(
                "sessionId", sessionId,
                "maxDurationMillis", 30_000,
                "maxBytes", 4L * 1_024 * 1_024));
        requireKind(content, "trace-started");
    }

    String semanticFixture(String sessionId) throws Exception {
        LinkedHashMap<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("sessionId", sessionId);
        LinkedHashMap<String, Object> actors = new LinkedHashMap<>();
        for (String testId : List.of("username", "password", "sign-in", "settings-list",
                "settings-scroll", "open-dialog", "rotated-card", "overlap-card")) {
            JsonNode match = singleMatch(call("ui_query", Map.of(
                    "sessionId", sessionId,
                    "locator", testIdLocator(testId))));
            LinkedHashMap<String, Object> semantic = new LinkedHashMap<>();
            copyText(match, semantic, "role");
            copyText(match, semantic, "accessibleName");
            copyText(match, semantic, "text");
            copyText(match, semantic, "testId");
            actors.put(testId, semantic);
        }
        fixture.put("actors", actors);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(fixture).strip();
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to encode semantic fixture", failure);
        }
    }

    Wait waitVisible(String sessionId, String text) throws Exception {
        JsonNode content = call("ui_wait", Map.of(
                "sessionId", sessionId,
                "locator", textLocator("text", text),
                "condition", "visible"));
        requireKind(content, "wait-result");
        JsonNode match = singleMatch(content);
        return new Wait(content.path("revision").asLong(), content.path("frame").asLong(),
                match.path("text").asText());
    }

    void fillByLabel(String sessionId, String label, String value) throws Exception {
        call("ui_action", Map.of(
                "sessionId", sessionId,
                "locator", textLocator("label", label),
                "action", Map.of("kind", "fill", "value", value, "force", false)));
    }

    void clickByRoleAndName(String sessionId, String role, String name) throws Exception {
        Map<String, Object> locator = Map.of(
                "kind", "filter",
                "locator", Map.of("kind", "role", "role", role),
                "filter", Map.of("kind", "name", "match", exact(name)));
        call("ui_action", Map.of(
                "sessionId", sessionId,
                "locator", locator,
                "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                        "force", false)));
    }

    void clickMissing(String sessionId, String testId, long deadlineMillis) throws Exception {
        call("ui_action", Map.of(
                "sessionId", sessionId,
                "deadlineMillis", deadlineMillis,
                "locator", testIdLocator(testId),
                "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                        "force", false)));
    }

    void scrollByTestId(
            String sessionId, String testId, float amountX, float amountY) throws Exception {
        call("ui_action", Map.of(
                "sessionId", sessionId,
                "locator", testIdLocator(testId),
                "action", Map.of("kind", "scroll", "amountX", amountX,
                        "amountY", amountY, "force", false)));
    }

    List<String> queryText(String sessionId, String text) throws Exception {
        JsonNode content = call("ui_query", Map.of(
                "sessionId", sessionId,
                "locator", textLocator("text", text)));
        ArrayList<String> matches = new ArrayList<>();
        content.path("matches").forEach(match -> matches.add(match.path("text").asText()));
        return List.copyOf(matches);
    }

    String singleText(String sessionId, String text) throws Exception {
        JsonNode match = singleMatch(call("ui_query", Map.of(
                "sessionId", sessionId,
                "locator", textLocator("text", text))));
        return match.path("text").asText();
    }

    String singleTextByTestId(String sessionId, String testId) throws Exception {
        JsonNode match = singleMatch(call("ui_query", Map.of(
                "sessionId", sessionId,
                "locator", testIdLocator(testId))));
        return match.path("text").asText();
    }

    QueryEvidence singleEvidenceByTestId(String sessionId, String testId) throws Exception {
        JsonNode content = call("ui_query", Map.of(
                "sessionId", sessionId,
                "locator", testIdLocator(testId)));
        JsonNode match = singleMatch(content);
        return new QueryEvidence(
                match.path("id").asText(),
                match.path("text").asText());
    }

    Screenshot screenshot(String sessionId) throws Exception {
        JsonNode content = call("ui_screenshot", Map.of(
                "sessionId", sessionId,
                "maxWidth", 1280,
                "maxHeight", 720,
                "maxPixels", 1280L * 720,
                "maxPngBytes", 4 * 1_024 * 1_024));
        requireKind(content, "screenshot-result");
        JsonNode artifact = content.path("artifact");
        return new Screenshot(content.path("width").asInt(), content.path("height").asInt(),
                new Artifact(artifact.path("reference").asText(),
                        artifact.path("mediaType").asText(),
                        artifact.path("byteLength").asLong(),
                        artifact.path("sha256").asText()));
    }

    Comparison inspectCompare(String sessionId) throws Exception {
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("sessionId", sessionId);
        arguments.put("referenceId", "reference-screen");
        arguments.put("policyId", "reference-smoke");
        arguments.put("policyVersion", 1);
        arguments.put("viewportId", "main");
        arguments.put("maxIterations", 1);
        arguments.put("maxDurationMillis", 30_000);
        arguments.put("maxWidth", 1280);
        arguments.put("maxHeight", 720);
        arguments.put("maxPixels", 1280L * 720);
        arguments.put("maxPngBytes", 4 * 1_024 * 1_024);
        JsonNode content = call("ui_inspect_compare", arguments);
        requireKind(content, "inspect-compare-result");
        return new Comparison(
                content.path("status").asText(),
                content.path("revision").asLong(),
                content.path("frame").asLong(),
                content.path("sha256").asText(),
                artifact(content.path("currentArtifact")),
                artifact(content.path("evidenceArtifact")),
                content.path("differences").toString(),
                content.path("metrics").toString());
    }

    Typography typography(String sessionId) throws Exception {
        JsonNode content = call("ui_typography_diagnose", Map.of(
                "sessionId", sessionId,
                "referenceId", "reference-typography",
                "viewportId", "main",
                "maxDurationMillis", 30_000,
                "maxResults", 16,
                "maxWidth", 1280,
                "maxHeight", 720,
                "maxPixels", 1280L * 720,
                "maxPngBytes", 4 * 1024 * 1024));
        requireKind(content, "typography-diagnostic-result");
        java.util.ArrayList<String> controlIds = new java.util.ArrayList<>();
        content.path("reports").forEach(
                report -> controlIds.add(report.path("controlId").asText()));
        return new Typography(
                content.path("status").asText(),
                content.path("revision").asLong(),
                content.path("frame").asLong(),
                content.path("sha256").asText(),
                List.copyOf(controlIds),
                artifact(content.path("currentArtifact")),
                artifact(content.path("evidenceArtifact")),
                content.path("reports").toString());
    }

    Layout layout(String sessionId) throws Exception {
        JsonNode content = call("ui_layout_diagnose", Map.of(
                "sessionId", sessionId,
                "referenceId", "reference-layout",
                "viewportId", "main",
                "maxDurationMillis", 2_000,
                "maxResults", 16,
                "maxWidth", 1280,
                "maxHeight", 720,
                "maxPixels", 1280L * 720,
                "maxPngBytes", 4 * 1024 * 1024));
        requireKind(content, "layout-diagnostic-result");
        java.util.ArrayList<String> controlIds = new java.util.ArrayList<>();
        content.path("reports").forEach(
                report -> controlIds.add(report.path("controlId").asText()));
        return new Layout(
                content.path("status").asText(),
                content.path("revision").asLong(),
                content.path("frame").asLong(),
                content.path("sha256").asText(),
                List.copyOf(controlIds),
                content.path("quiescence").path("settled").asBoolean(),
                artifact(content.path("currentArtifact")),
                artifact(content.path("evidenceArtifact")),
                content.path("reports").toString());
    }

    Trace stopTrace(String sessionId) throws Exception {
        JsonNode content = call("ui_trace_stop", Map.of("sessionId", sessionId));
        requireKind(content, "trace-stopped");
        return new Trace(content.path("traceReference").asText(),
                content.path("eventCount").asLong(), content.path("bytes").asLong());
    }

    static TraceEvidence traceEvidence(byte[] archive) throws Exception {
        byte[] events = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("events.ndjson".equals(entry.getName())) {
                    events = zip.readAllBytes();
                    break;
                }
            }
        }
        if (events == null) {
            throw new IllegalStateException("Trace archive omitted events.ndjson");
        }
        ArrayList<TraceEventData> decoded = new ArrayList<>();
        for (String line : new String(events, StandardCharsets.UTF_8).split("\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            JsonNode event = JSON.readTree(line);
            decoded.add(new TraceEventData(
                    event.path("sequence").asLong(),
                    event.path("kind").asText(),
                    event.path("requestId").isNull()
                            ? null : event.path("requestId").asText(),
                    event.path("parentSequence").isNull()
                            ? null : event.path("parentSequence").asLong(),
                    event.at("/evidence/operation").isMissingNode()
                            ? null : event.at("/evidence/operation").asText()));
        }
        return new TraceEvidence(decoded);
    }

    @Override public void close() throws java.io.IOException {
        if (closed) {
            return;
        }
        closed = true;
        output.close();
        input.close();
    }

    JsonNode navigate(String sessionId, List<String> inputs, long deadlineMillis)
            throws Exception {
        Map<String, Object> spec = Map.ofEntries(
                Map.entry("scenarioId", "navigation"),
                Map.entry("seed", 7),
                Map.entry("configuration", Map.of()),
                Map.entry("profileId", "desktop-restart-1280x720"),
                Map.entry("applicationId", "reference-ui-app"),
                Map.entry("processId", "reference-ui-process"),
                Map.entry("sessionId", "reference-ui"),
                Map.entry("inputs", inputs),
                Map.entry("controllerSupported", true),
                Map.entry("maxSteps", 16),
                Map.entry("maxActors", 16),
                Map.entry("maxResultBytes", 262144),
                Map.entry("maxEvidenceBytes", 262144),
                Map.entry("maxDurationMillis", 5000));
        JsonNode content = call("ui_navigation_inspect", Map.of(
                "sessionId", sessionId, "spec", spec, "deadlineMillis", deadlineMillis));
        requireKind(content, "navigation-result");
        return content;
    }

    String startTrace(String sessionId, long deadlineMillis) throws Exception {
        JsonNode content = call("ui_trace_start", Map.of(
                "sessionId", sessionId,
                "maxDurationMillis", 30_000,
                "maxBytes", 64L * 1_024 * 1_024,
                "deadlineMillis", deadlineMillis));
        requireKind(content, "trace-started");
        return content.path("traceId").asText();
    }

    JsonNode stopTrace(String sessionId, long deadlineMillis) throws Exception {
        JsonNode content = call("ui_trace_stop", Map.of(
                "sessionId", sessionId, "deadlineMillis", deadlineMillis));
        requireKind(content, "trace-stopped");
        return content;
    }

    JsonNode queryTrace(String sessionId, String traceId, long deadlineMillis)
            throws Exception {
        Map<String, Object> spec = Map.of(
                "traceId", traceId,
                "propertyPaths", List.of(),
                "kinds", List.of(
                        "appeared", "disappeared", "enabled", "disabled", "text-changed",
                        "bounds-changed", "focus-changed", "modal-changed",
                        "obscuration-changed", "z-order-changed", "identity-ambiguous"),
                "maxTransitions", 4096,
                "maxEvidenceBytes", 262144,
                "maxDurationMillis", 5000);
        JsonNode content = call("ui_trace_query", Map.of(
                "sessionId", sessionId, "spec", spec, "deadlineMillis", deadlineMillis));
        requireKind(content, "trace-query-result");
        return content;
    }

    String runMatrix(String sessionId, long deadlineMillis) throws Exception {
        Map<String, Object> spec = Map.ofEntries(
                Map.entry("scenarioId", "navigation"),
                Map.entry("windows", List.of(Map.of("width", 1280, "height", 720))),
                Map.entry("uiScales", List.of(1.0)),
                Map.entry("devicePixelRatios", List.of(1.0)),
                Map.entry("hiDpiModes", List.of("LOGICAL")),
                Map.entry("locales", List.of("en-US")),
                Map.entry("fontSetIds", List.of()),
                Map.entry("assertions", List.of()),
                Map.entry("maxCases", 1),
                Map.entry("maxDurationMillis", 5000));
        JsonNode content = call("ui_matrix_run", Map.of(
                "sessionId", sessionId, "spec", spec, "deadlineMillis", deadlineMillis));
        requireKind(content, "matrix-run-started");
        return content.path("runId").asText();
    }

    JsonNode matrixResults(String sessionId, String runId, long deadlineMillis)
            throws Exception {
        JsonNode content = call("ui_matrix_results", Map.of(
                "sessionId", sessionId, "runId", runId, "deadlineMillis", deadlineMillis));
        requireKind(content, "matrix-report");
        return content;
    }

    JsonNode runtimeCompare(String sessionId, long deadlineMillis) throws Exception {
        JsonNode content = call("ui_runtime_compare", Map.of(
                "sessionId", sessionId,
                "locator", testIdLocator("username"),
                "maxDurationMillis", 5000,
                "deadlineMillis", deadlineMillis));
        requireKind(content, "runtime-compare-result");
        return content;
    }

    JsonNode validateLayout(String sessionId, long deadlineMillis) throws Exception {
        Map<String, Object> spec = Map.of(
                "targetMode", "stage",
                "enabledChecks", List.of("zero-size", "duplicate-test-id"),
                "minTargetWidth", 64.0,
                "minTargetHeight", 64.0,
                "maxAlignmentDelta", 1.0,
                "minSpacing", 1.0,
                "failOn", "error",
                "maxFindings", 256,
                "maxNodes", 10000,
                "maxDurationMillis", 2000);
        JsonNode content = call("ui_validate_layout", Map.of(
                "sessionId", sessionId, "spec", spec, "deadlineMillis", deadlineMillis));
        requireKind(content, "layout-validation-result");
        return content;
    }

    private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
        JsonNode result = request("tools/call", Map.of("name", tool, "arguments", arguments));
        if (result.path("isError").asBoolean()) {
            throw new IllegalStateException("MCP tool failed: " + result);
        }
        JsonNode content = result.path("structuredContent");
        if (!content.isObject()) {
            throw new IllegalStateException("MCP tool omitted structured content: " + result);
        }
        return content;
    }

    private JsonNode request(String method, Map<String, Object> params) throws Exception {
        long id = ++requestId;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        return response(id, method);
    }

    private JsonNode response(long id, String operation) throws Exception {
        JsonNode message;
        do {
            String line = input.readLine();
            if (line == null) {
                throw new IllegalStateException("MCP stdout closed while awaiting " + operation);
            }
            message = JSON.readTree(line);
        } while (!message.has("id"));
        if (message.path("id").asLong() != id) {
            throw new IllegalStateException("Out-of-order MCP response: " + message);
        }
        if (message.has("error")) {
            throw new IllegalStateException("MCP request failed: " + message.path("error"));
        }
        return message.path("result");
    }

    private void notify(String method, Map<String, Object> params) throws Exception {
        send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
    }

    private void send(Map<String, Object> message) throws Exception {
        output.write(JSON.writeValueAsString(message));
        output.newLine();
        output.flush();
    }

    private static JsonNode singleMatch(JsonNode content) {
        JsonNode matches = content.path("matches");
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one semantic match: " + content);
        }
        return matches.get(0);
    }

    private static void copyText(JsonNode source, Map<String, Object> target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            target.put(field, value.asText());
        }
    }

    private static Map<String, Object> testIdLocator(String testId) {
        return Map.of("kind", "test-id", "testId", testId);
    }

    private static Map<String, Object> textLocator(String field, String source) {
        return Map.of("kind", "text", "field", field, "match", exact(source));
    }

    private static Map<String, Object> exact(String source) {
        return Map.of("mode", "exact", "source", source);
    }

    private static void requireKind(JsonNode content, String expected) {
        if (!Objects.equals(expected, content.path("kind").asText())) {
            throw new IllegalStateException("Expected " + expected + ": " + content);
        }
    }

    private static Artifact artifact(JsonNode node) {
        return new Artifact(
                node.path("reference").asText(),
                node.path("mediaType").asText(),
                node.path("byteLength").asLong(),
                node.path("sha256").asText());
    }

    static final class TraceEvidence {
        private final List<TraceEventData> events;

        TraceEvidence(List<TraceEventData> events) {
            this.events = List.copyOf(events);
        }

        int completedCausalChains(String operation) {
            Map<String, TraceEventData> starts = new HashMap<>();
            for (TraceEventData event : events) {
                if ("COMMAND_STARTED".equals(event.kind())
                        && operation.equals(event.operation())) {
                    starts.put(event.requestId(), event);
                }
            }
            int completed = 0;
            for (TraceEventData event : events) {
                TraceEventData start = starts.get(event.requestId());
                if ("COMMAND_COMPLETED".equals(event.kind())
                        && operation.equals(event.operation())
                        && start != null
                        && Objects.equals(event.parentSequence(), start.sequence())) {
                    completed++;
                }
            }
            return completed;
        }

        int failedCausalChains(String operation) {
            Map<String, TraceEventData> starts = new HashMap<>();
            for (TraceEventData event : events) {
                if ("COMMAND_STARTED".equals(event.kind())
                        && operation.equals(event.operation())) {
                    starts.put(event.requestId(), event);
                }
            }
            int failed = 0;
            for (TraceEventData event : events) {
                TraceEventData start = starts.get(event.requestId());
                if ("COMMAND_FAILED".equals(event.kind())
                        && operation.equals(event.operation())
                        && start != null
                        && Objects.equals(event.parentSequence(), start.sequence())) {
                    failed++;
                }
            }
            return failed;
        }

        List<String> lifecycle(String operation) {
            List<String> requestIds = requestIds(operation);
            if (requestIds.size() != 1) {
                throw new IllegalStateException(
                        "Expected one started " + operation + " request: " + requestIds);
            }
            String requestId = requestIds.getFirst();
            return events.stream()
                    .filter(event -> Objects.equals(requestId, event.requestId()))
                    .map(TraceEventData::kind)
                    .toList();
        }

        List<String> requestIds(String operation) {
            return events.stream()
                    .filter(event -> "COMMAND_STARTED".equals(event.kind()))
                    .filter(event -> operation.equals(event.operation()))
                    .map(TraceEventData::requestId)
                    .toList();
        }

        boolean hasSnapshotOperation(String operation) {
            return events.stream().anyMatch(event ->
                    "SNAPSHOT".equals(event.kind()) && operation.equals(event.operation()));
        }
    }

    private record TraceEventData(
            long sequence,
            String kind,
            String requestId,
            Long parentSequence,
            String operation) {}

    record Artifact(String reference, String mediaType, long byteLength, String sha256) {}

    record Screenshot(int width, int height, Artifact artifact) {}

    record Comparison(
            String status,
            long revision,
            long frame,
            String sha256,
            Artifact current,
            Artifact evidence,
            String differences,
            String metrics) {}

    record Typography(
            String status,
            long revision,
            long frame,
            String sha256,
            List<String> controlIds,
            Artifact current,
            Artifact evidence,
            String reports) {}

    record Layout(
            String status,
            long revision,
            long frame,
            String sha256,
            List<String> controlIds,
            boolean settled,
            Artifact current,
            Artifact evidence,
            String reports) {}

    record QueryEvidence(String nodeId, String text) {}

    record Snapshot(long revision, long frame, String rootId, int nodeCount) {}

    record Wait(long revision, long frame, String text) {}

    record Trace(String reference, long events, long bytes) {}
}
