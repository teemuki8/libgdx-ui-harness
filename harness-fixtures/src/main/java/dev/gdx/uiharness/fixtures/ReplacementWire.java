package dev.gdx.uiharness.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Single-line, bounded fixture-private request/result transport. */
final class ReplacementWire {
    static final int MAX_LINE_CHARS = 64 * 1024;
    private static final ObjectMapper JSON = ProtocolJson.mapper();

    private ReplacementWire() {}

    static String request(ScenarioRequest request) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("schemaVersion", request.schemaVersion());
        node.put("scenarioId", request.scenarioId());
        node.put("seed", request.seed());
        node.set("configuration", JSON.valueToTree(request.configuration()));
        node.put("profileId", request.profileId());
        node.put("remainingNanos", request.deadline().remaining().toNanos());
        return bounded(JSON.writeValueAsString(node));
    }

    static ScenarioRequest request(String line) throws IOException {
        JsonNode node = JSON.readTree(bounded(line));
        @SuppressWarnings("unchecked")
        Map<String, String> configuration = JSON.convertValue(node.path("configuration"), Map.class);
        return new ScenarioRequest(
                node.path("schemaVersion").asInt(), node.path("scenarioId").asText(),
                node.path("seed").asLong(), configuration, node.path("profileId").asText(),
                Deadline.after(System::nanoTime, Duration.ofNanos(node.path("remainingNanos").asLong())));
    }

    static String result(ScenarioResult result, String reconnectIdentity) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("reconnectIdentity", reconnectIdentity);
        ObjectNode node = root.putObject("scenario");
        node.put("schemaVersion", result.schemaVersion());
        node.put("scenarioId", result.scenarioId());
        node.put("definitionVersion", result.definitionVersion());
        node.put("configurationDigest", result.configurationDigest());
        node.put("seed", result.seed());
        node.put("applicationId", result.applicationId());
        node.put("processId", result.processId());
        node.put("sessionId", result.sessionId());
        node.put("startFrame", result.startFrame());
        node.put("startRevision", result.startRevision());
        node.put("readyFrame", result.readyFrame());
        node.put("readyRevision", result.readyRevision());
        node.put("profileId", result.profileId());
        node.put("startStateIdentity", result.startStateIdentity());
        node.put("elapsed", result.elapsed().toString());
        node.put("setupAttempts", result.setupAttempts());
        node.put("cleanupCompleted", result.cleanupCompleted());
        if (result.failure().isPresent()) {
            node.put("failure", result.failure().orElseThrow().name());
        } else {
            node.putNull("failure");
        }
        return bounded(JSON.writeValueAsString(root));
    }

    static dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator.HandoffResult result(String line)
            throws IOException {
        JsonNode root = JSON.readTree(bounded(line));
        JsonNode node = root.path("scenario");
        ScenarioResult result = new ScenarioResult(
                node.path("schemaVersion").asInt(), node.path("scenarioId").asText(),
                node.path("definitionVersion").asText(), node.path("configurationDigest").asText(),
                node.path("seed").asLong(), node.path("applicationId").asText(),
                node.path("processId").asText(), node.path("sessionId").asText(),
                node.path("startFrame").asLong(), node.path("startRevision").asLong(),
                node.path("readyFrame").asLong(), node.path("readyRevision").asLong(),
                node.path("profileId").asText(), node.path("startStateIdentity").asText(),
                Duration.parse(node.path("elapsed").asText()), node.path("setupAttempts").asInt(),
                node.path("cleanupCompleted").asBoolean(),
                node.path("failure").isNull()
                        ? Optional.empty()
                        : Optional.of(ScenarioFailure.valueOf(node.path("failure").asText())));
        return new dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator.HandoffResult(
                result, root.path("reconnectIdentity").asText());
    }

    private static String bounded(String line) throws IOException {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE_CHARS || line.indexOf('\n') >= 0) {
            throw new IOException("invalid replacement message");
        }
        return line;
    }
}
