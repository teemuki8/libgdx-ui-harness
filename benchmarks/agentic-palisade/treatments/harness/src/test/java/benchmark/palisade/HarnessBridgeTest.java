package benchmark.palisade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HarnessBridgeTest {
    @TempDir Path temporary;

    @Test void locatesThePublicReferenceInsideAnIsolatedCandidateWorkspace()
            throws Exception {
        Path reference = temporary.resolve(
                "corpus/reference/initial-1280x720.png");
        Files.createDirectories(reference.getParent());
        Files.write(reference, new byte[] {1});

        assertEquals(
                reference.toAbsolutePath().normalize(),
                HarnessBridge.locateInitialReference(temporary));
    }

    @Test void canonicalReferencesAndLaunchViewportsAreClosedAndBounded() throws Exception {
        Path references = temporary.resolve("corpus/reference");
        Files.createDirectories(references);
        for (String referenceId : List.of(
                "initial-1920x1080", "bottom-1920x1080", "initial-1280x720")) {
            Path reference = references.resolve(referenceId + ".png");
            Files.write(reference, new byte[] {1});
            assertEquals(reference.toAbsolutePath().normalize(),
                    HarnessBridge.locateReference(temporary, referenceId));
        }
        assertEquals(1920, HarnessCli.launchViewport(
                Map.of("PALISADE_VIEWPORT", "desktop-1920x1080")).width());
        assertEquals(720, HarnessCli.launchViewport(Map.of()).height());
        assertThrows(IllegalArgumentException.class, () ->
                HarnessCli.launchViewport(Map.of("PALISADE_VIEWPORT", "desktop-4096x4096")));
        assertThrows(IllegalArgumentException.class, () ->
                HarnessBridge.locateReference(temporary, "private-reference"));
    }

    @Test void comparisonCatalogContainsEveryDigestBoundCanonicalReference() {
        var references = HarnessBridge.visualReferences(Path.of("."));

        assertEquals(Set.of(
                "initial-1920x1080", "bottom-1920x1080", "initial-1280x720"),
                references.keySet());
        assertEquals("desktop-1920x1080",
                references.get("bottom-1920x1080").viewportId());
        assertEquals(1280, references.get("initial-1280x720").width());
    }

    @Test
    void closeDoesNotAwaitAnOutstandingLongDelayedDeadline() throws Exception {
        Path ownedArtifacts = temporary.resolve("close-ordering-artifacts");
        CloseOrderingApplication application = new CloseOrderingApplication(ownedArtifacts);

        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Harness bridge close ordering");
        configuration.setWindowedMode(1280, 720);
        configuration.setInitialVisible(false);
        configuration.setHdpiMode(HdpiMode.Pixels);
        configuration.disableAudio(true);
        configuration.useVsync(false);
        configuration.setForegroundFPS(120);
        configuration.setIdleFPS(120);
        new Lwjgl3Application(application, configuration);

        if (application.failure.get() != null) {
            throw new AssertionError("close-ordering fixture failed", application.failure.get());
        }
        assertTrue(application.closeCompleted,
                "close must complete despite an outstanding delayed deadline");
        assertTrue(application.screenshotReleasedByClose,
                "closing must release the pending capture");
        assertTrue(application.noDeadlineThreadAfterClose,
                "no live deadline worker thread may remain after close");
    }

    @Test void fixedJsonCliExercisesOneApplicationOwnedSessionAndArtifacts() throws Exception {
        Path ownedArtifacts = temporary.resolve("owned-harness-artifacts");
        Path applicationFile = temporary.resolve("application-owned.txt");
        Files.writeString(applicationFile, "keep", StandardCharsets.UTF_8);
        FixtureApplication application = new FixtureApplication(ownedArtifacts);

        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Harness bridge contract");
        configuration.setWindowedMode(1280, 720);
        configuration.setInitialVisible(false);
        configuration.setHdpiMode(HdpiMode.Pixels);
        configuration.disableAudio(true);
        configuration.useVsync(false);
        configuration.setForegroundFPS(120);
        configuration.setIdleFPS(120);
        new Lwjgl3Application(application, configuration);

        if (application.failure.get() != null) {
            throw new AssertionError("fixture application failed", application.failure.get());
        }
        assertEquals(1, application.clicks.get(), "the semantic click action must reach Stage input");
        assertFalse(application.candidateDisposed,
                "HarnessBridge must not dispose the application-owned CandidateUi");
        assertTrue(Files.isDirectory(ownedArtifacts),
                "clean close must retain bounded benchmark artifacts for inspection");
        Path published = ownedArtifacts.resolve("published");
        try (var artifacts = Files.walk(ownedArtifacts)) {
            List<Path> retained = artifacts.filter(Files::isRegularFile).toList();
            assertTrue(retained.size() >= 2,
                    "screenshot and trace evidence must survive clean close");
            assertTrue(retained.stream().allMatch(path -> path.startsWith(published)),
                    "only published evidence may survive clean close");
        }
        assertFalse(Files.exists(ownedArtifacts.resolve("artifacts")));
        assertFalse(Files.exists(ownedArtifacts.resolve("traces")));
        assertTrue(Files.isRegularFile(applicationFile),
                "closing the bridge must leave application-owned files alone");

        List<Map<String, Object>> responses = parseLines(application.output.get());
        assertEquals(16, responses.size());
        assertSuccessKind(responses.get(0), "sessions-result");
        Map<String, Object> sessions = result(responses.get(0));
        List<?> catalog = (List<?>) sessions.get("sessions");
        assertEquals(1, catalog.size(), "exactly one Scene2dSession must be discoverable");
        assertEquals(HarnessBridge.SESSION_ID, ((Map<?, ?>) catalog.getFirst()).get("sessionId"));

        assertSuccessKind(responses.get(1), "snapshot-summary");
        Map<String, Object> snapshot = result(responses.get(1));
        assertTrue(((Number) snapshot.get("nodeCount")).intValue() >= 2);
        assertEquals("present", snapshot.get("candidateContractStatus"));
        assertEquals("fixture-state",
                ((Map<?, ?>) snapshot.get("candidateContract")).get("stateId"));

        assertSuccessKind(responses.get(2), "query-result");
        Map<String, Object> query = result(responses.get(2));
        assertEquals(1, ((Number) query.get("matchCount")).intValue());
        Map<?, ?> match = (Map<?, ?>) ((List<?>) query.get("matches")).getFirst();
        assertEquals("button", match.get("role"));
        assertEquals("START BATTLE", match.get("accessibleName"));

        assertSuccessKind(responses.get(3), "action-result");
        assertSuccessKind(responses.get(4), "wait-result");
        assertSuccessKind(responses.get(5), "trace-started");
        assertSuccessKind(responses.get(6), "screenshot-result");
        Map<String, Object> screenshot = result(responses.get(6));
        assertEquals(1280, ((Number) screenshot.get("width")).intValue());
        assertEquals(720, ((Number) screenshot.get("height")).intValue());
        assertOpaqueArtifact((Map<?, ?>) screenshot.get("artifact"), "image/png");

        assertSuccessKind(responses.get(7), "inspect-compare-result");
        Map<String, Object> comparison = result(responses.get(7));
        assertEquals("not-converged", comparison.get("status"));
        assertOpaqueArtifact((Map<?, ?>) comparison.get("currentArtifact"), "image/png");
        assertOpaqueArtifact(
                (Map<?, ?>) comparison.get("evidenceArtifact"), "application/json");
        assertSuccessKind(responses.get(8), "trace-stopped");
        assertTrue(((Number) result(responses.get(8)).get("eventCount")).longValue() >= 2);
        assertSuccessKind(responses.get(9), "capabilities-result");
        assertRejected(responses.get(10), "LIMIT_EXCEEDED");
        assertSuccessKind(responses.get(11), "trace-started");
        assertSuccessKind(responses.get(12), "trace-stopped");
        assertRejected(responses.get(13), "unknown-operation");
        assertRejected(responses.get(14), "invalid-request");
        assertRejected(responses.get(15), "limit-exceeded");
        assertTrue(application.artifactsObservedBeforeClose,
                "screenshot and trace artifacts must be stored below the bridge-owned root");
    }

    private static List<Map<String, Object>> parseLines(byte[] output) throws Exception {
        return Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\\R"))
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> value = ProtocolJson.mapper().readValue(line, Map.class);
                        return value;
                    } catch (Exception failure) {
                        throw new IllegalArgumentException("invalid CLI JSON: " + line, failure);
                    }
                })
                .toList();
    }

    private static void assertSuccessKind(Map<String, Object> response, String kind) {
        assertEquals(Boolean.TRUE, response.get("ok"), response.toString());
        assertEquals(kind, result(response).get("kind"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    private static void assertOpaqueArtifact(Map<?, ?> artifact, String mediaType) {
        assertNotNull(artifact);
        String reference = (String) artifact.get("reference");
        assertTrue(reference.startsWith("artifact:"));
        assertFalse(reference.contains("/"));
        assertEquals(mediaType, artifact.get("mediaType"));
        assertEquals(64, ((String) artifact.get("sha256")).length());
        assertTrue(((Number) artifact.get("byteLength")).longValue() > 0);
    }

    private static void assertRejected(Map<String, Object> response, String code) {
        assertEquals(Boolean.FALSE, response.get("ok"), response.toString());
        assertEquals(code, ((Map<?, ?>) response.get("error")).get("code"));
    }

    private static String commands() {
        String session = "\"sessionId\":\"" + HarnessBridge.SESSION_ID + "\"";
        String locator = "{\"kind\":\"filter\",\"locator\":{\"kind\":\"role\","
                + "\"role\":\"button\"},\"filter\":{\"kind\":\"name\","
                + "\"match\":{\"mode\":\"exact\",\"source\":\"START BATTLE\"}}}";
        return String.join("\n",
                "{\"operation\":\"ui_sessions\",\"arguments\":{}}",
                "{\"operation\":\"ui_snapshot\",\"arguments\":{" + session + "}}",
                "{\"operation\":\"ui_query\",\"arguments\":{" + session
                        + ",\"locator\":" + locator + "}}",
                "{\"operation\":\"ui_action\",\"arguments\":{" + session
                        + ",\"locator\":" + locator
                        + ",\"action\":{\"kind\":\"click\",\"pointer\":0,"
                        + "\"button\":0,\"force\":false}}}",
                "{\"operation\":\"ui_wait\",\"arguments\":{" + session
                        + ",\"locator\":" + locator + ",\"condition\":\"visible\"}}",
                "{\"operation\":\"ui_trace_start\",\"arguments\":{" + session
                        + ",\"maxDurationMillis\":30000,\"maxBytes\":1048576}}",
                "{\"operation\":\"ui_screenshot\",\"arguments\":{" + session
                        + ",\"maxWidth\":1280,\"maxHeight\":720,\"maxPixels\":921600,"
                        + "\"maxPngBytes\":1048576}}",
                "{\"operation\":\"ui_inspect_compare\",\"arguments\":{" + session
                        + ",\"referenceId\":\"initial-1280x720\","
                        + "\"policyId\":\"pixel-exact\",\"policyVersion\":1,"
                        + "\"viewportId\":\"desktop-1280x720\",\"maxIterations\":1,"
                        + "\"maxDurationMillis\":30000,\"maxWidth\":1280,"
                        + "\"maxHeight\":720,\"maxPixels\":921600,"
                        + "\"maxPngBytes\":1048576}}",
                "{\"operation\":\"ui_trace_stop\",\"arguments\":{" + session + "}}",
                "{\"operation\":\"ui_capabilities\",\"arguments\":{" + session + "}}",
                "{\"operation\":\"ui_trace_start\",\"arguments\":{" + session
                        + ",\"maxDurationMillis\":30000,\"maxBytes\":1}}",
                "{\"operation\":\"ui_trace_start\",\"arguments\":{" + session
                        + ",\"maxDurationMillis\":30000,\"maxBytes\":1048576}}",
                "{\"operation\":\"ui_trace_stop\",\"arguments\":{" + session + "}}",
                "{\"operation\":\"exec\",\"arguments\":{\"command\":\"sh\"}}",
                "{\"operation\":\"ui_snapshot\",\"arguments\":{" + session
                        + "},\"path\":\"/tmp/escape\"}") + "\n";
    }
    private static java.io.InputStream boundedAttackInput() {
        byte[] prefix = commands().getBytes(StandardCharsets.UTF_8);
        return new java.io.InputStream() {
            private int position;

            @Override public int read() {
                if (position < prefix.length) return prefix[position++] & 0xff;
                if (position++ <= prefix.length + ProtocolJson.MAX_REQUEST_BYTES) return 'x';
                throw new AssertionError("CLI read beyond its maximum JSON command size");
            }
        };
    }


    private static final class FixtureApplication extends ApplicationAdapter {
        private final Path artifactRoot;
        private final AtomicReference<byte[]> output = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger clicks = new AtomicInteger();
        private Stage stage;
        private HarnessBridge bridge;
        private CompletableFuture<Void> cli;
        private boolean candidateDisposed;
        private boolean artifactsObservedBeforeClose;

        FixtureApplication(Path artifactRoot) {
            this.artifactRoot = artifactRoot;
        }

        @Override public void create() {
            try {
                CandidateUi untouched = HarnessCli.loadCandidate();
                assertNotNull(untouched.stage());
                assertEquals(CandidateState.empty(), untouched.snapshotState());
                untouched.dispose();
                stage = new Stage(new ScreenViewport());
                stage.getViewport().update(1280, 720, true);
                Actor button = new Actor();
                button.setBounds(80, 80, 160, 80);
                button.addListener(new InputListener() {
                    @Override public boolean touchDown(
                            InputEvent event, float x, float y, int pointer, int mouseButton) {
                        return mouseButton == Input.Buttons.LEFT;
                    }

                    @Override public void touchUp(
                            InputEvent event, float x, float y, int pointer, int mouseButton) {
                        clicks.incrementAndGet();
                    }
                });
                stage.addActor(button);
                CandidateUi candidate = new CandidateUi() {
                    @Override public Stage stage() {
                        return stage;
                    }

                    @Override public void showInitial() {
                    }

                    @Override public CandidateState snapshotState() {
                        return new CandidateState(Map.of(
                                "stateAction", Map.of(
                                        "schemaVersion", "state-action/v1.0",
                                        "stateId", "fixture-state",
                                        "revision", 1L,
                                        "frame", 1L,
                                        "controls", List.of(),
                                        "focusOrder", List.of(),
                                        "conditions", List.of(),
                                        "viewports", List.of())));
                    }

                    @Override public void dispose() {
                        candidateDisposed = true;
                    }
                };
                Gdx.input.setInputProcessor(stage);
                bridge = HarnessBridge.open(candidate, artifactRoot);
                bridge.semantics().setRole(button, Role.BUTTON);
                bridge.semantics().setAccessibleName(button, "START BATTLE");
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                cli = CompletableFuture.runAsync(
                        () -> HarnessCli.run(bridge, boundedAttackInput(), sink));
                cli.whenComplete((ignored, thrown) -> {
                    output.set(sink.toByteArray());
                    if (thrown != null) failure.compareAndSet(null, thrown);
                    Gdx.app.postRunnable(Gdx.app::exit);
                });
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
                Gdx.app.exit();
            }
        }

        @Override public void render() {
            if (failure.get() != null || bridge == null) return;
            try {
                bridge.beforeRender();
                stage.act(1f / 60f);
                Gdx.gl.glClearColor(0.1f, 0.2f, 0.3f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                stage.draw();
                bridge.afterRender();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
                Gdx.app.exit();
            }
        }

        @Override public void dispose() {
            try {
                if (cli != null) cli.get(5, TimeUnit.SECONDS);
                try (var artifacts = Files.walk(artifactRoot)) {
                    artifactsObservedBeforeClose = artifacts.anyMatch(Files::isRegularFile);
                }
                if (bridge != null) bridge.close();
                assertFalse(candidateDisposed);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                if (stage != null) stage.dispose();
            }
        }
    }

    /**
     * Queues one real capture with a 120s deadline, stops completing frames, and closes the
     * bridge through a bounded future while the deadline signal is still armed. Arming is
     * observed purely through the public JVM lifecycle: the named
     * {@code palisade-harness-deadlines} worker thread only exists once the real executor has
     * started a scheduled signal, and the capture future must still be pending (no frame has
     * claimed it, so the signal has neither fired nor been cancelled).
     */
    private static final class CloseOrderingApplication extends ApplicationAdapter {
        private static final String DEADLINE_THREAD_NAME = "palisade-harness-deadlines";
        private static final long ARM_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
        private static final long CLOSE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
        private final Path artifactRoot;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private Stage stage;
        private HarnessBridge bridge;
        private CompletableFuture<?> screenshot;
        private boolean warmupDone;
        private int warmupFrames;
        private long armedAtNanos;
        private boolean closeStarted;
        private Thread closeThread;
        private CompletableFuture<Void> closeOutcome;
        private boolean closeCompleted;
        private boolean screenshotReleasedByClose;
        private boolean noDeadlineThreadAfterClose;

        CloseOrderingApplication(Path artifactRoot) {
            this.artifactRoot = artifactRoot;
        }

        @Override public void create() {
            try {
                stage = new Stage(new ScreenViewport());
                stage.getViewport().update(1280, 720, true);
                CandidateUi candidate = new CandidateUi() {
                    @Override public Stage stage() {
                        return stage;
                    }

                    @Override public void showInitial() {
                    }

                    @Override public CandidateState snapshotState() {
                        return new CandidateState(Map.of(
                                "stateAction", Map.of(
                                        "schemaVersion", "state-action/v1.0",
                                        "stateId", "fixture-state",
                                        "revision", 1L,
                                        "frame", 1L,
                                        "controls", List.of(),
                                        "focusOrder", List.of(),
                                        "conditions", List.of(),
                                        "viewports", List.of())));
                    }

                    @Override public void dispose() {
                    }
                };
                Gdx.input.setInputProcessor(stage);
                bridge = HarnessBridge.open(candidate, artifactRoot);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
                Gdx.app.exit();
            }
        }

        @Override public void render() {
            if (failure.get() != null || bridge == null) return;
            try {
                if (!warmupDone) {
                    bridge.beforeRender();
                    stage.act(1f / 60f);
                    Gdx.gl.glClearColor(0.1f, 0.2f, 0.3f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    stage.draw();
                    bridge.afterRender();
                    if (++warmupFrames >= 3) {
                        warmupDone = true;
                        armedAtNanos = System.nanoTime();
                        screenshot = bridge.call("ui_screenshot", Map.of(
                                "sessionId", HarnessBridge.SESSION_ID,
                                "maxWidth", 1280,
                                "maxHeight", 720,
                                "maxPixels", 921600,
                                "maxPngBytes", 1048576,
                                "deadlineMillis", 120_000L)).toCompletableFuture();
                    }
                    return;
                }
                // No more frames complete: the queued capture stays pending and its 120s
                // deadline signal stays armed until close releases it.
                if (!closeCompleted && deadlineThreadAlive() && !screenshot.isDone()) {
                    closeBridge();
                    Gdx.app.exit();
                    return;
                }
                if (screenshot.isDone()) {
                    failure.compareAndSet(null, new AssertionError(
                            "capture completed before close released it"));
                    Gdx.app.exit();
                    return;
                }
                if (System.nanoTime() - armedAtNanos > ARM_TIMEOUT_NANOS) {
                    failure.compareAndSet(null, new AssertionError(
                            "the real deadline worker thread never started within "
                                    + ARM_TIMEOUT_NANOS / 1_000_000 + "ms for the pending capture"));
                    Gdx.app.exit();
                    return;
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
                Gdx.app.exit();
            }
        }

        private void closeBridge() {
            if (!awaitCloseBounded()) {
                failure.compareAndSet(null, new AssertionError(
                        "close must not await an outstanding delayed deadline",
                        new TimeoutException("close exceeded its bounded await")));
            }
        }

        /**
         * Awaits the single fixture-owned close attempt for at most {@link #CLOSE_TIMEOUT_NANOS}.
         * On timeout the same close thread is interrupted and boundedly joined, so a slow close
         * can never linger and no second close is ever submitted.
         */
        private boolean awaitCloseBounded() {
            startCloseOnce();
            Thread closer = closeThread;
            try {
                closeOutcome.get(CLOSE_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
                return true;
            } catch (TimeoutException timedOut) {
                closer.interrupt();
                awaitCloseThreadTermination(closer);
                return false;
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                closer.interrupt();
                awaitCloseThreadTermination(closer);
                return false;
            } catch (java.util.concurrent.ExecutionException closeFailure) {
                failure.compareAndSet(null, closeFailure.getCause());
                return true;
            }
        }

        /** Starts the one and only close attempt on a fixture-owned daemon thread. */
        private synchronized void startCloseOnce() {
            if (closeStarted) {
                return;
            }
            closeStarted = true;
            closeOutcome = new CompletableFuture<>();
            Thread thread = Thread.ofPlatform()
                    .name("harness-bridge-close-fixture").daemon()
                    .unstarted(this::runClose);
            closeThread = thread;
            thread.start();
        }

        /** Runs the single close attempt and publishes observations before the outcome. */
        private void runClose() {
            try {
                bridge.close();
                // Publish observations on this thread before completing the outcome so the
                // awaiter's get() establishes a happens-before edge over them.
                closeCompleted = true;
                screenshotReleasedByClose = screenshot.isDone();
                // close() only returns after its bounded awaitTermination, so the worker is
                // gone; the loop absorbs any thread-map bookkeeping lag without sleeping.
                for (int retries = 0; deadlineThreadAlive() && retries < 1_000; retries++) {
                    // re-check the live-thread event
                }
                noDeadlineThreadAfterClose = !deadlineThreadAlive();
                closeOutcome.complete(null);
            } catch (Throwable thrown) {
                closeOutcome.completeExceptionally(thrown);
            }
        }

        /**
         * Boundedly joins the close thread and explicitly verifies termination: a join timeout
         * is never silently equated with the thread having stopped.
         */
        private void awaitCloseThreadTermination(Thread closer) {
            try {
                closer.join(CLOSE_TIMEOUT_NANOS / 1_000_000,
                        (int) (CLOSE_TIMEOUT_NANOS % 1_000_000));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
            if (closer.isAlive()) {
                failure.compareAndSet(null, new AssertionError(
                        "close thread stayed alive after interrupt and bounded join"));
            }
        }

        private static boolean deadlineThreadAlive() {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (DEADLINE_THREAD_NAME.equals(thread.getName()) && thread.isAlive()) {
                    return true;
                }
            }
            return false;
        }

        @Override public void dispose() {
            try {
                if (bridge != null && !closeCompleted && !awaitCloseBounded()) {
                    failure.compareAndSet(null, new AssertionError(
                            "bridge cleanup must not hang after a fixture failure",
                            new TimeoutException("close cleanup exceeded its bounded await")));
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                if (stage != null) stage.dispose();
            }
        }
    }
}
