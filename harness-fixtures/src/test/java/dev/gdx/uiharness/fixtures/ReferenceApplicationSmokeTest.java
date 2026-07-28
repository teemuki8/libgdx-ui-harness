package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.trace.TraceReplay;
import dev.gdx.uiharness.core.trace.TraceReplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class ReferenceApplicationSmokeTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void agentCanCompleteReferenceWorkflowThroughMcpFiveConsecutiveTimes() throws Exception {
        byte[] expectedScreenshot = resourceBytes("golden/reference-screen.png");
        String expectedSemantics = resourceText("golden/reference-semantic.json");

        for (int run = 0; run < 5; run++) {
            Path processRoot;
            try (ReferenceProcess app = ReferenceProcess.launch()) {
                processRoot = app.root();
                try (HarnessMcpClient agent = HarnessMcpClient.connect(app)) {
                    assertEquals(List.of(SESSION_ID), agent.sessions());
                    assertEquals(List.of("action", "query", "screenshot", "snapshot", "trace", "wait"),
                            agent.capabilities(SESSION_ID));
                    agent.startTrace(SESSION_ID);

                    assertEquals(expectedSemantics, agent.semanticFixture(SESSION_ID));
                    agent.fillByLabel(SESSION_ID, "Username", "Ada");
                    agent.fillByLabel(SESSION_ID, "Password", "correct horse");
                    agent.clickByRoleAndName(SESSION_ID, "button", "Sign in");

                    assertEquals("Welcome, Ada", agent.singleText(SESSION_ID, "Welcome, Ada"));
                    assertEquals(0, agent.queryText(SESSION_ID, "Sign in").size(),
                            "the sign-in subtree must be dynamically replaced");

                    agent.scrollByTestId(SESSION_ID, "settings-scroll", 0f, 4f);
                    agent.clickByRoleAndName(SESSION_ID, "button", "Open dialog");
                    assertEquals("Reference dialog",
                            agent.singleTextByTestId(SESSION_ID, "reference-dialog"));

                    HarnessMcpClient.Screenshot screenshot = agent.screenshot(SESSION_ID);
                    assertEquals(1280, screenshot.width());
                    assertEquals(720, screenshot.height());
                    assertEquals("image/png", screenshot.artifact().mediaType());
                    assertTrue(screenshot.artifact().byteLength() > 100);
                    assertFalse(screenshot.artifact().reference().contains("/"));
                    byte[] actualScreenshot = app.readArtifact(screenshot.artifact());
                    assertArrayEquals(expectedScreenshot, actualScreenshot,
                            "the fixed-step hidden LWJGL3 framebuffer must be deterministic");

                    HarnessMcpClient.Trace trace = agent.stopTrace(SESSION_ID);
                    assertTrue(trace.events() >= 6);
                    assertTrue(trace.bytes() > 100);
                    assertFalse(trace.reference().contains("/"));
                    byte[] traceBytes = app.readArtifact(trace.reference(), "application/zip");
                    assertEquals('P', traceBytes[0]);
                    assertEquals('K', traceBytes[1]);
                    Path replayArchive = Files.createTempFile("reference-trace-", ".zip");
                    try {
                        Files.write(replayArchive, traceBytes);
                        TraceReplay replay = new TraceReplayer().load(replayArchive);
                        assertTrue(replay.causality().isValid(),
                                () -> replay.causality().errors().toString());
                        assertEquals(trace.events(), replay.manifest().eventCount());
                    } finally {
                        Files.deleteIfExists(replayArchive);
                    }
                }
                app.awaitCleanExit();
                assertTrue(app.lifecycleClosed());
            }
            assertFalse(Files.exists(processRoot), "process resources must leave no temp directory");
        }
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (var input = ReferenceApplicationSmokeTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource " + name);
            }
            return input.readAllBytes();
        }
    }

    private static String resourceText(String name) throws Exception {
        return new String(resourceBytes(name), java.nio.charset.StandardCharsets.UTF_8).strip();
    }
}
