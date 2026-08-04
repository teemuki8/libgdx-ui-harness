package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator;
import java.io.IOException;
import java.io.Reader;
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

    @Test void oversizedResultWithoutNewlineIsRejectedAtBound() {
        Reader oversized = new Reader() {
            private int reads;

            @Override public int read(char[] target, int offset, int length) {
                if (reads >= ReplacementWire.MAX_LINE_CHARS + 1) {
                    throw new AssertionError("reader consumed beyond framing bound");
                }
                target[offset] = 'x';
                reads++;
                return 1;
            }

            @Override public void close() {}
        };

        IOException failure = assertThrows(
                IOException.class, () -> ReplacementProcess.readBoundedLine(oversized));

        assertEquals("replacement result exceeds message bound", failure.getMessage());
    }
}
