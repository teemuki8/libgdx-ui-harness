package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 transition-tracing fixture: the reference process serves the production MCP server;
 * a bounded trace is started, one fill action is recorded against it, and
 * {@code ui_trace_query} projects the resulting text change as a compact state transition.
 */
final class TraceProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void recordedActionProjectsCompactTransitionsThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                String traceId = client.startTrace(SESSION_ID, 5_000);

                client.fillByLabel(SESSION_ID, "Username", "Ada");

                JsonNode stopped = client.stopTrace(SESSION_ID, 5_000);
                assertTrue(stopped.path("eventCount").asInt() > 0,
                        "the fill action must be recorded as trace events");

                JsonNode query = client.queryTrace(SESSION_ID, traceId, 5_000);
                assertEquals(traceId, query.path("traceId").asText());
                assertTrue(query.path("transitions").size() >= 1,
                        "the fill must project at least one transition");
                assertTrue(query.path("transitions").get(0).has("kind"));
                assertTrue(query.path("transitions").get(0).has("actorIdentity"));
                assertFalse(query.path("truncated").asBoolean());
                assertTrue(query.path("transitions").get(0).path("afterFrame").asLong()
                        >= query.path("transitions").get(0).path("beforeFrame").asLong());
            }
        }
    }
}
