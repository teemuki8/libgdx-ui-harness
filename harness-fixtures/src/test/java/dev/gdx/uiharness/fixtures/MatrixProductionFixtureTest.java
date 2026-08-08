package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 display-matrix fixture: the reference process serves the production MCP server;
 * {@code ui_matrix_run} applies each case to the real window before its assertions run and
 * {@code ui_matrix_results} returns the compact retained report with exact observed settings.
 */
final class MatrixProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";
    private static final String RESTART_PROFILE = "desktop-restart-1280x720";

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
                assertEquals(1280, data.path("results").get(0)
                        .path("observedWindow").path("width").asInt());
                assertEquals(720, data.path("results").get(0)
                        .path("observedWindow").path("height").asInt());
                assertEquals("en-US", data.path("results").get(0)
                        .path("observedLocale").asText());
                assertEquals(RESTART_PROFILE, data.path("results").get(0)
                        .path("observedRestartProfileId").asText());
            }
        }
    }

    @Test
    @Timeout(120)
    void matrixAppliesAndObservesTwoMateriallyDifferentCases() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                HarnessMcpClient.MatrixSpec spec = new HarnessMcpClient.MatrixSpec(
                        List.of(Map.of("width", 1280, "height", 720),
                                Map.of("width", 1920, "height", 1080)),
                        List.of(1.0), List.of(1.0), List.of("PIXELS"),
                        List.of("en-US"), List.of(), 2);

                String runId = client.runMatrix(SESSION_ID, spec, 20_000);
                JsonNode data = client.matrixResults(SESSION_ID, runId, 5_000)
                        .path("report");
                assertEquals(2, data.path("results").size());
                assertEquals("PASSED", data.path("results").get(0).path("status").asText());
                assertEquals("PASSED", data.path("results").get(1).path("status").asText());
                assertEquals(1280, data.path("results").get(0)
                        .path("observedWindow").path("width").asInt());
                assertEquals(1920, data.path("results").get(1)
                        .path("observedWindow").path("width").asInt());
                assertEquals(1080, data.path("results").get(1)
                        .path("observedWindow").path("height").asInt());
                assertEquals("en-US", data.path("results").get(0)
                        .path("observedLocale").asText());
                assertEquals(RESTART_PROFILE, data.path("results").get(0)
                        .path("observedRestartProfileId").asText());
                assertTrue(data.path("results").get(0).path("passedAssertions").isArray());
            }
        }
    }

    @Test
    @Timeout(120)
    void unsupportedCaseIsATypedSkipThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                HarnessMcpClient.MatrixSpec spec = new HarnessMcpClient.MatrixSpec(
                        List.of(Map.of("width", 1280, "height", 720)),
                        List.of(1.0), List.of(2.0), List.of("PIXELS"),
                        List.of("en-US"), List.of(), 1);

                String runId = client.runMatrix(SESSION_ID, spec, 20_000);
                JsonNode result = client.matrixResults(SESSION_ID, runId, 5_000)
                        .path("report").path("results").get(0);
                assertEquals("UNSUPPORTED", result.path("status").asText());
                assertTrue(result.path("evidence").asText().contains("devicePixelRatio"));
                assertEquals(0, result.path("passedAssertions").size());
            }
        }
    }
}
