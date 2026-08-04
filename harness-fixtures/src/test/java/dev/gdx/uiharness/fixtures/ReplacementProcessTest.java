package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class ReplacementProcessTest {
    @Test @Timeout(30) void childOwnsRealProcessSessionAndScenarioResult() throws Exception {
        ScenarioRequest request = new ScenarioRequest(
                ScenarioDefinition.SCHEMA_VERSION, "reference-reset", 9,
                Map.of("mode", "child"), "desktop-restart-1280x720",
                Deadline.after(System::nanoTime, Duration.ofSeconds(5)));
        try (ReplacementProcess process = ReplacementProcess.launch(request)) {
            var outcome = process.result().get(10, TimeUnit.SECONDS);
            var result = (RegisteredLaunchCoordinator.HandoffResult) outcome;
            assertTrue(result.scenario().processId().startsWith("replacement-process-"));
            assertTrue(result.scenario().sessionId().startsWith("replacement-session-"));
            assertNotEquals(result.scenario().processId(), result.scenario().sessionId());
            assertTrue(result.scenario().cleanupCompleted());
        }
    }
}
