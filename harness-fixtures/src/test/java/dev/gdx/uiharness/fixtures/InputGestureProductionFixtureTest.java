package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class InputGestureProductionFixtureTest {
    @Test @Timeout(120)
    void realMcpCombinesMoveAimFireAndExactTicks() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch();
                HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            assertTrue(client.capabilities("reference-ui").contains("ui_input_gesture"));
            var result = client.inputGesture("reference-ui", 1, List.of(
                    Map.of("kind", "key-down", "keycode", 29),
                    Map.of("kind", "mouse-move", "deltaX", 20, "deltaY", -10),
                    Map.of("kind", "mouse-down", "button", 0),
                    Map.of("kind", "wait-ticks", "count", 30),
                    Map.of("kind", "mouse-up", "button", 0),
                    Map.of("kind", "key-up", "keycode", 29)), 10_000);
            assertEquals("completed", result.path("outcome").asText());
            assertEquals(30, result.path("steps").get(3).path("tick").path("completedTicks").asInt());
            assertEquals(0, result.path("heldInputs").size());
            var observed = client.runtimeObserve("reference-ui", "reference-input", "state",
                    "reference-ui-frame", 5_000);
            assertEquals("AVAILABLE", observed.path("status").asText());
            assertEquals("20:-10:30:false", observed.path("value").asText());
        }
    }
    @Test @Timeout(120)
    void invalidLateButtonFailsBeforeRelativeMovementIsDispatched() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch();
                HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            assertThrows(IllegalStateException.class, () -> client.inputGesture("reference-ui", 1,
                    List.of(Map.of("kind", "mouse-move", "deltaX", 50, "deltaY", 20),
                            Map.of("kind", "mouse-up", "button", 0)), 10_000));
            var observed = client.runtimeObserve("reference-ui", "reference-input", "state",
                    "reference-ui-frame", 5_000);
            assertEquals("0:0:0:false", observed.path("value").asText());
        }
    }

    @Test @Timeout(120)
    void cancellationReleasesMouseBeforeNextMutatingRequestAndCleanShutdown() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                long request = client.beginInputGesture("reference-ui", List.of(
                        Map.of("kind", "mouse-down", "button", 0),
                        Map.of("kind", "key-down", "keycode", 29),
                        Map.of("kind", "wait-frames", "count", 10_000),
                        Map.of("kind", "key-up", "keycode", 29),
                        Map.of("kind", "mouse-up", "button", 0)), 60_000);
                client.waitVisible("reference-ui", "gesture-key-held");
                client.cancelKeyboardGesture(request);
                client.fillByLabel("reference-ui", "Username", "cleanup-complete");
                var observed = client.runtimeObserve("reference-ui", "reference-input", "state",
                        "reference-ui-frame", 5_000);
                assertEquals("0:0:0:false", observed.path("value").asText());
            }
            app.awaitCleanExit();
            assertTrue(app.lifecycleClosed());
        }
    }

}
