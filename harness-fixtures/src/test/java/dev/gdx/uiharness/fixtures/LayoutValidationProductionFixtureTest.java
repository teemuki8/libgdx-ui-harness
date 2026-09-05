package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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
    void intrinsicTextCoverageCannotPassUnobservableWidgetsThroughMcp() throws Exception {
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
            assertEquals(2, result.path("findings").valueStream().filter(finding ->
                    "CHECK_UNAVAILABLE".equals(finding.path("reason").asText())
                            && finding.path("nodeId").asText().equals(usernameNode)).count(),
                    result.toPrettyString());
        }
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
