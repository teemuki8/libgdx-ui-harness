package benchmark.palisade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HarnessBridgeTest {
    @TempDir Path temporary;

    @Test void fixedJsonCliExercisesOneApplicationOwnedSessionAndArtifacts() throws Exception {
        Path ownedArtifacts = temporary.resolve("owned-harness-artifacts");
        Path applicationFile = temporary.resolve("application-owned.txt");
        Files.writeString(applicationFile, "keep", StandardCharsets.UTF_8);
        FixtureApplication application = new FixtureApplication(ownedArtifacts);

        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Harness bridge contract");
        configuration.setWindowedMode(320, 240);
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
        assertEquals(15, responses.size());
        assertSuccessKind(responses.get(0), "sessions-result");
        Map<String, Object> sessions = result(responses.get(0));
        List<?> catalog = (List<?>) sessions.get("sessions");
        assertEquals(1, catalog.size(), "exactly one Scene2dSession must be discoverable");
        assertEquals(HarnessBridge.SESSION_ID, ((Map<?, ?>) catalog.getFirst()).get("sessionId"));

        assertSuccessKind(responses.get(1), "snapshot-summary");
        assertTrue(((Number) result(responses.get(1)).get("nodeCount")).intValue() >= 2);

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
        assertEquals(320, ((Number) screenshot.get("width")).intValue());
        assertEquals(240, ((Number) screenshot.get("height")).intValue());
        assertOpaqueArtifact((Map<?, ?>) screenshot.get("artifact"), "image/png");

        assertSuccessKind(responses.get(7), "trace-stopped");
        assertTrue(((Number) result(responses.get(7)).get("eventCount")).longValue() >= 2);
        assertSuccessKind(responses.get(8), "capabilities-result");
        assertRejected(responses.get(9), "limit-exceeded");
        assertSuccessKind(responses.get(10), "trace-started");
        assertSuccessKind(responses.get(11), "trace-stopped");
        assertRejected(responses.get(12), "unknown-operation");
        assertRejected(responses.get(13), "invalid-request");
        assertRejected(responses.get(14), "limit-exceeded");
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
                        + ",\"maxWidth\":320,\"maxHeight\":240,\"maxPixels\":76800,"
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
                stage = new Stage(new ScreenViewport());
                stage.getViewport().update(320, 240, true);
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
                        return new CandidateState(Map.of());
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
}
