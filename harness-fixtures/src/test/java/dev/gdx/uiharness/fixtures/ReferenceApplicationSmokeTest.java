package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.trace.TraceReplay;
import dev.gdx.uiharness.core.trace.TraceReplayer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
        int[] deterministicScreenshot = null;

        for (int run = 0; run < 5; run++) {
            Path processRoot;
            try (ReferenceProcess app = ReferenceProcess.launch()) {
                processRoot = app.root();
                try (HarnessMcpClient agent = HarnessMcpClient.connect(app)) {
                    assertEquals(List.of(SESSION_ID), agent.sessions());
                    assertEquals(List.of("action", "query", "screenshot", "snapshot", "trace", "wait"),
                            agent.capabilities(SESSION_ID));
                    agent.startTrace(SESSION_ID);
                    HarnessMcpClient.Snapshot snapshot = agent.snapshot(SESSION_ID);
                    assertTrue(snapshot.nodeCount() >= 20);
                    assertTrue(snapshot.revision() > 0);
                    assertTrue(snapshot.frame() > 0);

                    assertEquals(expectedSemantics, agent.semanticFixture(SESSION_ID));
                    agent.fillByLabel(SESSION_ID, "Username", "Ada");
                    agent.fillByLabel(SESSION_ID, "Password", "correct horse");
                    agent.clickByRoleAndName(SESSION_ID, "button", "Sign in");
                    HarnessMcpClient.Wait welcome =
                            agent.waitVisible(SESSION_ID, "Welcome, Ada");
                    assertEquals("Welcome, Ada", welcome.text());
                    assertTrue(welcome.revision() > snapshot.revision());

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
                    int[] actualPixels =
                            assertPngPixelsMatchGolden(expectedScreenshot, actualScreenshot);
                    if (deterministicScreenshot == null) {
                        deterministicScreenshot = actualPixels;
                    } else {
                        assertArrayEquals(deterministicScreenshot, actualPixels,
                                "fixed-step captures must be identical across fresh processes");
                    }

                    HarnessMcpClient.Trace trace = agent.stopTrace(SESSION_ID);
                    assertTrue(trace.events() >= 6);
                    assertTrue(trace.bytes() > 100);
                    assertFalse(trace.reference().contains("/"));
                    assertNotEquals(screenshot.artifact().reference(), trace.reference());
                    assertThrows(IllegalArgumentException.class, () ->
                            app.readArtifact("artifact:" + "0".repeat(32), "application/zip"));
                    byte[] traceBytes = app.readArtifact(trace.reference(), "application/zip");
                    assertEquals('P', traceBytes[0]);
                    assertEquals('K', traceBytes[1]);
                    HarnessMcpClient.TraceEvidence evidence =
                            HarnessMcpClient.traceEvidence(traceBytes);
                    assertEquals(2, evidence.completedCausalChains("Fill"));
                    assertEquals(2, evidence.completedCausalChains("Click"));
                    assertEquals(1, evidence.completedCausalChains("Scroll"));
                    assertEquals(1, evidence.completedCausalChains("screenshot"));
                    assertTrue(evidence.requestIds("Fill").stream()
                            .allMatch(id -> id.startsWith("fixture-fill-")));
                    assertTrue(evidence.requestIds("screenshot").stream()
                            .allMatch(id -> id.startsWith("fixture-screenshot-")));
                    assertTrue(evidence.hasSnapshotOperation("snapshot-or-query"));
                    assertTrue(evidence.hasSnapshotOperation("wait"));
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

    @Test
    @Timeout(120)
    void failedTracedActionRetainsOriginalFailureAndProducesReplayableCausalChain()
            throws Exception {
        Path processRoot;
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            processRoot = app.root();
            try (HarnessMcpClient agent = HarnessMcpClient.connect(app)) {
                agent.startTrace(SESSION_ID);

                IllegalStateException actionFailure = assertThrows(IllegalStateException.class,
                        () -> agent.clickMissing(SESSION_ID, "missing-target", 64));
                assertTrue(actionFailure.getMessage().contains("\"code\":\"timeout\""),
                        actionFailure::getMessage);

                HarnessMcpClient.Trace trace = agent.stopTrace(SESSION_ID);
                byte[] traceBytes = app.readArtifact(trace.reference(), "application/zip");
                HarnessMcpClient.TraceEvidence evidence =
                        HarnessMcpClient.traceEvidence(traceBytes);
                assertEquals(List.of("COMMAND_STARTED", "COMMAND_FAILED"),
                        evidence.lifecycle("Click"));
                assertEquals(1, evidence.failedCausalChains("Click"));

                Path replayArchive = Files.createTempFile("failed-reference-trace-", ".zip");
                try {
                    Files.write(replayArchive, traceBytes);
                    TraceReplay replay = new TraceReplayer().load(replayArchive);
                    assertTrue(replay.manifest().complete());
                    assertTrue(replay.causality().isValid(),
                            () -> replay.causality().errors().toString());
                    assertFalse(replay.partial());
                } finally {
                    Files.deleteIfExists(replayArchive);
                }
            }
            app.awaitCleanExit();
            assertTrue(app.lifecycleClosed());
        }
        assertFalse(Files.exists(processRoot), "process resources must leave no temp directory");
    }

    private static int[] assertPngPixelsMatchGolden(byte[] expected, byte[] actual)
            throws Exception {
        BufferedImage expectedImage =
                javax.imageio.ImageIO.read(new ByteArrayInputStream(expected));
        BufferedImage actualImage =
                javax.imageio.ImageIO.read(new ByteArrayInputStream(actual));
        assertTrue(expectedImage != null, "golden screenshot must be a valid PNG");
        assertTrue(actualImage != null, "captured screenshot must be a valid PNG");
        assertEquals(expectedImage.getWidth(), actualImage.getWidth());
        assertEquals(expectedImage.getHeight(), actualImage.getHeight());
        int width = expectedImage.getWidth();
        int height = expectedImage.getHeight();
        int[] expectedPixels = expectedImage.getRGB(0, 0, width, height, null, 0, width);
        int[] actualPixels = actualImage.getRGB(0, 0, width, height, null, 0, width);
        int differingPixels = 0;
        long totalChannelDelta = 0;
        for (int index = 0; index < expectedPixels.length; index++) {
            int expectedPixel = expectedPixels[index];
            int actualPixel = actualPixels[index];
            boolean differs = false;
            for (int shift = 0; shift <= 24; shift += 8) {
                int expectedChannel = (expectedPixel >>> shift) & 0xff;
                int actualChannel = (actualPixel >>> shift) & 0xff;
                int delta = Math.abs(expectedChannel - actualChannel);
                totalChannelDelta += delta;
                differs |= delta > 2;
            }
            differingPixels += differs ? 1 : 0;
        }
        assertTrue(differingPixels <= expectedPixels.length / 100,
                "golden screenshot differing pixels were " + differingPixels);
        assertTrue(totalChannelDelta <= expectedPixels.length / 2,
                "golden screenshot total channel delta was " + totalChannelDelta);
        return actualPixels;
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
