package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 runtime-binding fixture: the reference process serves the production MCP server;
 * the username field is bound to {@code reference-ui-user/value}, and {@code ui_runtime_compare}
 * correlates its displayed text against the app's actual runtime value after one fill.
 */
final class RuntimeProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void boundNodeComparesDisplayedValueAgainstRuntimeThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                client.fillByLabel(SESSION_ID, "Username", "Ada");

                JsonNode comparison = client.runtimeCompare(SESSION_ID, 5_000);
                assertEquals("EQUAL", comparison.path("status").asText());
                assertEquals("reference-ui-user", comparison.path("entityId").asText());
                assertEquals("value", comparison.path("propertyId").asText());
                assertEquals("Ada", comparison.path("displayedValue").asText());
                assertEquals("Ada", comparison.path("runtimeValue").asText());
            }
        }
    }
    @Test
    @Timeout(120)
    void unboundRegisteredValueIsObservedWithoutAnySemanticNode() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                JsonNode observation = client.runtimeObserve(
                        SESSION_ID, "reference-simulation", "angle",
                        "reference-ui-frame", 5_000);

                assertEquals("AVAILABLE", observation.path("status").asText());
                assertEquals("reference-simulation", observation.path("entityId").asText());
                assertEquals("angle", observation.path("propertyId").asText());
                assertEquals("1.25", observation.path("value").asText());
                assertEquals("decimal", observation.path("valueFormatId").asText());
                assertEquals(observation.path("runtimeFrame").asLong(),
                        observation.path("runtimeRevision").asLong());
                assertEquals(0, client.queryText(SESSION_ID, "1.25").size(),
                        "the unbound runtime value must not have a semantic node");
            }
        }
    }


    @Test
    @Timeout(120)
    void desynchronizedModelAndUiReportMismatchThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                JsonNode comparison = client.runtimeCompare(SESSION_ID, 5_000);
                assertEquals("MISMATCH", comparison.path("status").asText());
                assertEquals("reference-ui-user", comparison.path("entityId").asText());
                assertEquals("value", comparison.path("propertyId").asText());
                assertEquals("", comparison.path("displayedValue").asText());
                assertEquals("Ada", comparison.path("runtimeValue").asText());
                assertEquals(comparison.path("displayedFrame").asLong(),
                        comparison.path("runtimeFrame").asLong(),
                        "the mismatch must carry bounded same-frame correlation evidence");
            }
        }
    }
}
