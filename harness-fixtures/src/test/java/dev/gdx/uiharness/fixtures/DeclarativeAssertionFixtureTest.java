package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class DeclarativeAssertionFixtureTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void assertionsObserveRenderedReplacementFramesAndBoundedStrictEvidence() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                assertTrue(client.capabilities(SESSION_ID).contains("ui_assert"));

                JsonNode initial = client.assertThat(SESSION_ID, testId("assertion-state"),
                        Map.of("kind", "text-equals", "expected", "initial"), 1_000);
                String initialNode = initial.path("nodeId").asText();
                long initialFrame = initial.path("frame").asLong();

                client.clickByRoleAndName(SESSION_ID, "button", "Sign in");
                JsonNode stable = client.assertThat(SESSION_ID, testId("assertion-state"),
                        Map.of("kind", "stable-for-frames", "frames", 3,
                                "properties", List.of("text", "bounds", "visible")),
                        2_000);
                assertEquals("passed", stable.path("outcome").asText());
                assertTrue(stable.path("frame").asLong() >= initialFrame + 3,
                        "three ordered completed rendered-frame identities must be observed");
                assertTrue(stable.path("lastObserved").asText().contains("3/3"));
                HarnessMcpClient.QueryEvidence reconstructed =
                        client.singleEvidenceByTestId(SESSION_ID, "assertion-state");
                assertEquals(stable.path("revision").asLong(), stable.path("frame").asLong(),
                        "completed fence revision and frame must identify the same rendered step");
                assertTrue(client.snapshot(SESSION_ID).frame() >= stable.path("frame").asLong());
                assertEquals("ready", reconstructed.text());
                assertFalse(reconstructed.nodeId().isBlank(),
                        "the reconstructed actor must remain queryable through the MCP path");
                assertNotEquals(initialNode, stable.path("nodeId").asText(),
                        "the assertion must resolve the reconstructed actor rather than cache identity");

                client.clickByRoleAndName(SESSION_ID, "button", "Open dialog");
                long frameBeforeDeadline = client.snapshot(SESSION_ID).frame();
                JsonNode deadline = client.assertThat(SESSION_ID, testId("assertion-state"),
                        Map.of("kind", "text-equals", "expected", "never"), 64);
                assertEquals("failed", deadline.path("outcome").asText());
                assertEquals("never", deadline.path("expected").asText());
                assertEquals("ready", deadline.path("lastObserved").asText());
                assertTrue(deadline.path("elapsedMillis").asLong() >= 64);
                assertTrue(deadline.path("frame").asLong() >= frameBeforeDeadline);
                assertTrue(client.snapshot(SESSION_ID).frame() > deadline.path("frame").asLong(),
                        "the deadline must fire without another assertion frame signal");

                JsonNode count = client.assertThat(SESSION_ID, testId("assertion-candidate"),
                        Map.of("kind", "count-equals", "expected", 12), 1_000);
                assertPassed(count, "12");
                assertEquals("", count.path("nodeId").asText());
                assertTrue(count.path("candidates").isArray());
                assertFalse(count.path("truncated").asBoolean());

                JsonNode missing = client.assertFailure(SESSION_ID, testId("missing-assertion-node"),
                        Map.of("kind", "visible"), 64);
                assertEquals("LOCATOR_NOT_FOUND", missing.path("code").asText());
                assertEquals("0", missing.at("/details/matchCount").asText());
                assertTrue(missing.path("candidates").size() <= 10);

                JsonNode multiple = client.assertFailure(SESSION_ID, testId("assertion-candidate"),
                        Map.of("kind", "visible"), 64);
                assertEquals("LOCATOR_AMBIGUOUS", multiple.path("code").asText());
                assertEquals("[redacted] 2", multiple.at("/details/matchCount").asText());
                assertEquals(2, multiple.path("candidates").size());
            }
            app.awaitCleanExit();
        }
    }

    private static void assertPassed(JsonNode result, String observed) {
        assertEquals("assertion-result", result.path("kind").asText());
        assertEquals(1, result.path("schemaVersion").asInt());
        assertEquals("passed", result.path("outcome").asText());
        assertEquals(observed, result.path("lastObserved").asText());
    }

    private static Map<String, Object> testId(String testId) {
        return Map.of("kind", "test-id", "testId", testId);
    }

    private static Map<String, Object> role(String role) {
        return Map.of("kind", "role", "role", role);
    }
}
