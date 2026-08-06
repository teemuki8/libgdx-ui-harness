package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
