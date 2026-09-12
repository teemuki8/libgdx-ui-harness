package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.badlogic.gdx.Input;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 layout validation fixture: the reference process serves the production MCP server,
 * and {@code ui_validate_layout} validates the whole stage from one atomic observation.
 */
final class LayoutValidationProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void intrinsicTextCoverageSupportsStandardFieldsAndRejectsUnknownWidgetsThroughMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch();
                HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            client.fillByLabel(SESSION_ID, "Username", "Layout coverage");
            JsonNode result = client.validateLayout(SESSION_ID, Map.of(
                    "targetMode", "stage",
                    "enabledChecks", List.of("clipped-text", "text-collision"),
                    "minTargetWidth", 64.0, "minTargetHeight", 64.0,
                    "maxAlignmentDelta", 1.0, "minSpacing", 1.0,
                    "failOn", "error", "maxFindings", 256, "maxNodes", 10000,
                    "maxDurationMillis", 2000), 5_000).path("result");

            assertEquals("FAIL", result.path("status").asText(), result.toPrettyString());
            String usernameNode = client.singleEvidenceByTestId(SESSION_ID, "username").nodeId();
            assertEquals(0, result.path("findings").valueStream().filter(finding ->
                    "CHECK_UNAVAILABLE".equals(finding.path("reason").asText())
                            && finding.path("nodeId").asText().equals(usernameNode)).count(),
                    result.toPrettyString());
            String listNode = client.singleEvidenceByTestId(SESSION_ID, "settings-list").nodeId();
            assertEquals(2, result.path("findings").valueStream().filter(finding ->
                    "CHECK_UNAVAILABLE".equals(finding.path("reason").asText())
                            && finding.path("nodeId").asText().equals(listNode)).count(),
                    result.toPrettyString());

            var spec = new LinkedHashMap<String, Object>(Map.of(
                    "targetMode", "subtree", "enabledChecks", List.of("clipped-text", "text-collision"),
                    "minTargetWidth", 64.0, "minTargetHeight", 64.0,
                    "maxAlignmentDelta", 1.0, "minSpacing", 1.0,
                    "failOn", "error", "maxFindings", 256, "maxNodes", 10000,
                    "maxDurationMillis", 2000));
            spec.put("locator", Map.of("kind", "test-id", "testId", "username"));
            Path evidence = Path.of("build", "text-field-evidence");
            Files.createDirectories(evidence);
            int frame = 0;
            for (String input : List.of("Layout coverage", "Scrolled input [RED] [] ".repeat(5))) {
                client.fillByLabel(SESSION_ID, "Username", input);
                JsonNode fieldResult = client.validateLayout(SESSION_ID, spec, 5_000).path("result");
                assertFieldIsObserved(fieldResult);
                if (frame == 0) {
                    // This reference style has zero inset against a one-pixel font pad.
                    // Preserve and observe the real clipping defect instead of hiding it.
                    assertEquals("FAIL", fieldResult.path("status").asText(), fieldResult.toPrettyString());
                    assertTrue(fieldResult.path("findings").get(0).path("evidence").asText()
                            .contains("left=1.0"), fieldResult.toPrettyString());
                }
                assertFalse(fieldResult.path("truncated").asBoolean(), fieldResult.toPrettyString());
                var screenshot = client.screenshot(SESSION_ID);
                Files.write(evidence.resolve("field-" + frame + ".png"),
                        client.readArtifact(SESSION_ID, screenshot.artifact()));
                Files.writeString(evidence.resolve("field-" + frame++ + ".json"),
                        fieldResult.toPrettyString());
            }
            for (int key : List.of(Input.Keys.HOME, Input.Keys.END)) {
                client.keyboardGesture(SESSION_ID, List.of(
                        Map.of("kind", "key-down", "keycode", key),
                        Map.of("kind", "wait-frames", "count", 1),
                        Map.of("kind", "key-up", "keycode", key)), 5_000);
                JsonNode moved = client.validateLayout(SESSION_ID, spec, 5_000).path("result");
                assertFieldIsObserved(moved);
            }
        }
    }

    private static void assertFieldIsObserved(JsonNode result) {
        assertTrue(List.of("PASS", "FAIL").contains(result.path("status").asText()), result.toPrettyString());
        assertEquals(1, result.path("examinedNodes").asInt(), result.toPrettyString());
        assertTrue(result.path("findings").size() <= 1, result.toPrettyString());
        assertTrue(result.path("findings").valueStream().allMatch(finding ->
                "CLIPPED_TEXT".equals(finding.path("reason").asText())), result.toPrettyString());
    }

    @Test
    @Timeout(120)
    void layoutValidationRunsThroughProductionMcpDeterministically() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                assertTrue(client.capabilities(SESSION_ID).contains("ui_validate_layout"));

                JsonNode first = client.validateLayout(SESSION_ID, 5_000);
                JsonNode data = first.path("result");
                assertTrue(data.has("status"));
                assertTrue(data.path("examinedNodes").asInt() > 0,
                        "validation must examine the real stage");
                assertTrue(data.has("findings"));
                assertTrue(data.has("truncated"));

                JsonNode second = client.validateLayout(SESSION_ID, 5_000);
                assertEquals(data.path("findings").toString(),
                        second.path("result").path("findings").toString(),
                        "identical observations must produce identical findings");
            }
        }
    }
}
