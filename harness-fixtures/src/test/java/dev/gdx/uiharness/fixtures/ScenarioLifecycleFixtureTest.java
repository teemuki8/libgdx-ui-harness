package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class ScenarioLifecycleFixtureTest {
    private static final String SESSION_ID = "reference-ui";
    private static final String PROFILE_ID = "desktop-restart-1280x720";

    @Test
    @Timeout(120)
    void registeredLifecycleRunsThroughProductionMcpAndCleansEveryTerminalPath() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            assertTrue(client.capabilities(SESSION_ID).containsAll(
                    List.of("scenario-list", "scenario-start")));
            JsonNode catalog = client.scenarios(SESSION_ID);
            assertTrue(catalog.path("available").asBoolean());
            assertEquals(
                    List.of("incompatible-reference", "navigation", "never-ready", "reference-reset"),
                    ids(catalog.path("scenarios")));
            assertEquals(
                    List.of(PROFILE_ID),
                    texts(catalog.path("scenarios").get(2).path("supportedProfileIds")));

            client.fillByLabel(SESSION_ID, "Username", "mutable value");
            JsonNode first = client.startScenario(
                    SESSION_ID,
                    "reference-reset",
                    42,
                    Map.of("mode", "clean"),
                    PROFILE_ID,
                    5_000);
            assertEquals("completed", first.path("kind").asText());
            JsonNode firstResult = first.path("scenario");
            assertFalse(firstResult.hasNonNull("failure"));
            assertTrue(firstResult.path("cleanupCompleted").asBoolean());
            assertTrue(firstResult.path("readyFrame").asLong()
                    > firstResult.path("startFrame").asLong());
            assertTrue(firstResult.path("readyRevision").asLong()
                    > firstResult.path("startRevision").asLong());
            assertEquals("reference-reset:42:clean",
                    firstResult.path("startStateIdentity").asText());
            assertTrue(firstResult.path("processId").asText().startsWith("replacement-process-"));
            assertTrue(firstResult.path("sessionId").asText().startsWith("replacement-session-"));
            assertFalse(firstResult.path("processId").asText()
                    .equals(firstResult.path("sessionId").asText()));
            assertTrue(first.path("reconnectIdentity").asText()
                    .startsWith("replacement-reconnect-"));
            assertEquals("mutable value", client.singleTextByTestId(SESSION_ID, "username"));

            client.fillByLabel(SESSION_ID, "Username", "changed again");
            JsonNode repeated = client.startScenario(
                    SESSION_ID,
                    "reference-reset",
                    42,
                    Map.of("mode", "clean"),
                    PROFILE_ID,
                    5_000);
            assertEquals("reference-reset:42:clean",
                    repeated.at("/scenario/startStateIdentity").asText());
            assertEquals("changed again", client.singleTextByTestId(SESSION_ID, "username"));

            assertRejected(client.startScenario(
                    SESSION_ID, "missing", 0, Map.of(), PROFILE_ID, 5_000),
                    "unknown-scenario");
            assertRejected(client.startScenario(
                    SESSION_ID, "incompatible-reference", 0, Map.of(), PROFILE_ID, 5_000),
                    "incompatible-scenario");
            assertRejected(client.startScenario(
                    SESSION_ID, "reference-reset", 0, Map.of(), "missing-profile", 5_000),
                    "unsupported-profile");

            long frameBeforeDeadline = client.snapshot(SESSION_ID).frame();
            JsonNode deadline = client.startScenario(
                    SESSION_ID,
                    "never-ready",
                    0,
                    Map.of("withholdCompletedFrames", "true"),
                    PROFILE_ID,
                    5_000);
            assertEquals("readiness-deadline", deadline.at("/scenario/failure").asText());
            assertTrue(deadline.at("/scenario/cleanupCompleted").asBoolean());
            assertEquals(0, deadline.at("/scenario/readyFrame").asLong());
            assertTrue(client.snapshot(SESSION_ID).frame() > frameBeforeDeadline);

            client.cancelScenario(SESSION_ID, "never-ready", PROFILE_ID, 5_000);
            assertEquals("",
                    client.singleTextByTestId(SESSION_ID, "password"));
            JsonNode afterCancellation = client.startScenario(
                    SESSION_ID, "reference-reset", 7, Map.of(), PROFILE_ID, 5_000);
            assertEquals("completed", afterCancellation.path("kind").asText());
            assertTrue(afterCancellation.at("/scenario/cleanupCompleted").asBoolean());
            }
            app.awaitCleanExit();
            assertTrue(app.lifecycleClosed());
        }
    }

    private static void assertRejected(JsonNode outcome, String reason) {
        assertEquals("rejected", outcome.path("kind").asText());
        assertEquals(reason, outcome.path("reason").asText());
    }

    private static List<String> ids(JsonNode values) {
        return values.valueStream().map(value -> value.path("id").asText()).toList();
    }

    private static List<String> texts(JsonNode values) {
        return values.valueStream().map(JsonNode::asText).toList();
    }
}
