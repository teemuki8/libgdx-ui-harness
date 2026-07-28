package dev.gdx.uiharness.fixtures;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minimal synchronous MCP client that sends real SDK JSON-RPC messages over process stdio. */
final class HarnessMcpClient implements Closeable {
    private static final ObjectMapper JSON = ProtocolJson.mapper().copy();
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
        if (listed.path("tools").size() != 9) {
            client.close();
            throw new IllegalStateException("Expected the nine production tools: " + listed);
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

    Trace stopTrace(String sessionId) throws Exception {
        JsonNode content = call("ui_trace_stop", Map.of("sessionId", sessionId));
        requireKind(content, "trace-stopped");
        return new Trace(content.path("traceReference").asText(),
                content.path("eventCount").asLong(), content.path("bytes").asLong());
    }

    @Override public void close() throws java.io.IOException {
        if (closed) {
            return;
        }
        closed = true;
        output.close();
        input.close();
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
        JsonNode message;
        do {
            String line = input.readLine();
            if (line == null) {
                throw new IllegalStateException("MCP stdout closed while awaiting " + method);
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

    record Artifact(String reference, String mediaType, long byteLength, String sha256) {}

    record Screenshot(int width, int height, Artifact artifact) {}

    record Trace(String reference, long events, long bytes) {}
}
