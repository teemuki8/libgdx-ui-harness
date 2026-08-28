package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real LWJGL3 proof for render-frame and controlled-tick keyboard gestures. */
final class KeyboardGestureProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";
    private static final int KEY_A = 29;

    @Test
    @Timeout(120)
    void realInputHoldsKeyAcrossCompletedFramesAndReleasesIt() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch();
                HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            assertTrue(client.capabilities(SESSION_ID).contains("ui_keyboard_gesture"));

            JsonNode result = client.keyboardGesture(SESSION_ID, List.of(
                    key("key-down"), waitFor("wait-frames", 3), key("key-up")), 10_000);

            assertCompleted(result, "key-down", "wait-frames", "key-up");
            JsonNode wait = result.path("steps").get(1);
            assertTrue(wait.path("afterFrame").asLong()
                    >= wait.path("beforeFrame").asLong() + 3);
            assertEquals(0, result.path("heldKeys").size());
            assertEquals("not-required", result.path("cleanupStatus").asText());
        }
    }

    @Test
    @Timeout(120)
    void thirtyControlledTicksRunWhileScene2dCallbackOwnsHeldKeyState() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch();
                HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            assertTrue(client.capabilities(SESSION_ID).contains("ui_keyboard_gesture_ticks"));

            JsonNode result = client.keyboardGesture(SESSION_ID, List.of(
                    key("key-down"), waitFor("wait-ticks", 30), key("key-up")), 10_000);

            assertCompleted(result, "key-down", "wait-ticks", "key-up");
            JsonNode tick = result.path("steps").get(1).path("tick");
            assertEquals(30, tick.path("requestedTicks").asInt());
            assertEquals(30, tick.path("completedTicks").asInt());
            assertEquals(tick.path("startTick").asLong() + 30,
                    tick.path("finalTick").asLong());
            assertEquals(Duration.ofMillis(16).toNanos(),
                    tick.path("configuredDeltaNanos").asLong());
            assertTrue(tick.path("executionEpoch").asLong() >= 0);
            assertTrue(tick.path("finalRuntimeFrame").asLong()
                    >= tick.path("firstRuntimeFrame").asLong());
        }
    }
    @Test
    @Timeout(120)
    void v2MaximumExactTickTimelineHasNoUncontrolledInterStepTicks() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch();
                HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            assertTrue(client.capabilities(SESSION_ID).contains("ui_keyboard_gesture_v2"));
            ArrayList<Map<String, Object>> steps = new ArrayList<>(256);
            steps.add(key("key-down"));
            for (int index = 0; index < 254; index++) {
                steps.add(waitFor("wait-ticks", 1));
            }
            steps.add(key("key-up"));

            JsonNode result = client.keyboardGesture(
                    SESSION_ID, 2, steps, 10_000);

            assertEquals("completed", result.path("outcome").asText());
            assertEquals(2, result.path("schemaVersion").asInt());
            assertEquals(256, result.path("completedSteps").asInt());
            long previousFinalTick = -1;
            long executionEpoch = -1;
            for (int index = 1; index < 255; index++) {
                JsonNode tick = result.path("steps").get(index).path("tick");
                assertEquals(1, tick.path("requestedTicks").asInt());
                assertEquals(1, tick.path("completedTicks").asInt());
                if (previousFinalTick >= 0) {
                    assertEquals(previousFinalTick, tick.path("startTick").asLong(),
                            "controlled tick steps must be contiguous at index " + index);
                    assertEquals(executionEpoch, tick.path("executionEpoch").asLong());
                }
                previousFinalTick = tick.path("finalTick").asLong();
                executionEpoch = tick.path("executionEpoch").asLong();
            }
            assertEquals(0, result.path("heldKeys").size());
        }
    }


    @Test
    @Timeout(120)
    void gestureLifecycleIsRetainedInProductionTrace() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch();
                HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            client.startTrace(SESSION_ID);
            client.keyboardGesture(SESSION_ID, List.of(
                    key("key-down"), waitFor("wait-frames", 1), key("key-up")), 10_000);

            HarnessMcpClient.Trace stopped = client.stopTrace(SESSION_ID);
            byte[] archive = app.readArtifact(stopped.reference(), "application/zip");
            HarnessMcpClient.TraceEvidence evidence =
                    HarnessMcpClient.traceEvidence(archive);
            assertEquals(List.of(
                            "gesture-accepted",
                            "gesture-step",
                            "gesture-step",
                            "gesture-step",
                            "gesture-completed"),
                    evidence.gestureEvents());
            assertEquals(1, evidence.gestureRequestIds().size());
        }
    }

    @Test
    @Timeout(120)
    void transportCancellationReleasesHeldKeyBeforeCleanShutdown() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                client.startTrace(SESSION_ID);
                long gestureId = client.beginKeyboardGesture(SESSION_ID, List.of(
                        key("key-down"), waitFor("wait-frames", 10_000), key("key-up")),
                        60_000);
                client.waitVisible(SESSION_ID, "gesture-key-held");
                client.cancelKeyboardGesture(gestureId);
                client.fillByLabel(SESSION_ID, "Username", "cleanup-complete");
                HarnessMcpClient.Trace stopped = client.stopTrace(SESSION_ID);
                HarnessMcpClient.TraceEvidence evidence = HarnessMcpClient.traceEvidence(
                        app.readArtifact(stopped.reference(), "application/zip"));
                List<String> events = evidence.gestureEvents();
                assertEquals("gesture-accepted", events.getFirst());
                assertEquals("gesture-completed", events.getLast());
                int failure = events.indexOf("gesture-failed");
                int cleanup = events.indexOf("gesture-cleanup");
                assertTrue(failure > 0);
                assertTrue(cleanup > failure);
                int completedStep = events.indexOf("gesture-step");
                assertTrue(completedStep < 0 || completedStep < failure,
                        "a concurrently completed key-down step must precede cancellation");
                assertEquals(1, evidence.gestureRequestIds().size());
            }
            app.awaitCleanExit();
            assertTrue(app.lifecycleClosed());
        }
    }

    private static void assertCompleted(JsonNode result, String... kinds) {
        assertEquals("completed", result.path("outcome").asText());
        assertEquals(kinds.length, result.path("startedSteps").asInt());
        assertEquals(kinds.length, result.path("completedSteps").asInt());
        assertEquals(kinds.length, result.path("steps").size());
        assertEquals(0, result.path("heldKeys").size());
        for (int index = 0; index < kinds.length; index++) {
            assertEquals(index, result.path("steps").get(index).path("index").asInt());
            assertEquals(kinds[index],
                    result.path("steps").get(index).path("kind").asText());
            assertEquals("completed",
                    result.path("steps").get(index).path("status").asText());
        }
    }

    private static Map<String, Object> key(String kind) {
        return Map.of("kind", kind, "keycode", KEY_A);
    }

    private static Map<String, Object> waitFor(String kind, int count) {
        return Map.of("kind", kind, "count", count);
    }
}
