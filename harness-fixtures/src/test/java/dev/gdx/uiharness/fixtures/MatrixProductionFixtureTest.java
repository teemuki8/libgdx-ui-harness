package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 display-matrix fixture: the reference process serves the production MCP server;
 * {@code ui_matrix_run} executes one bounded case of the registered navigation scenario and
 * {@code ui_matrix_results} returns its compact retained report.
 */
final class MatrixProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void matrixRunCompletesAndRetainsReportThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                String runId = client.runMatrix(SESSION_ID, 5_000);
                JsonNode report = client.matrixResults(SESSION_ID, runId, 5_000);
                JsonNode data = report.path("report");
                assertEquals(runId, data.path("runId").asText());
                assertEquals("navigation", data.path("scenarioId").asText());
                assertTrue(data.path("results").size() >= 1);
                assertEquals("PASSED", data.path("results").get(0).path("status").asText());
                assertTrue(data.path("results").get(0).path("caseSummary").has("window"));
            }
        }
    }
}
