package benchmark.palisade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

final class TemplateContractTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesTheCandidateContract() throws Exception {
        assertMethod(CandidateUi.class, "stage", Stage.class);
        assertMethod(CandidateUi.class, "showInitial", void.class);
        assertMethod(CandidateUi.class, "snapshotState", CandidateState.class);
        assertMethod(CandidateUi.class, "dispose", void.class);
        assertNotNull(CandidateState.empty());
    }

    @Test
    void candidateStateSupportsBoundedNestedJsonDataAndDefensiveCopies() {
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("map", "fixture");
        confirmation.put("seed", 305419896L);
        CandidateState state = new CandidateState(Map.of("confirmation", confirmation));

        confirmation.put("seed", 1L);

        assertEquals(305419896L,
                ((Map<?, ?>) state.values().get("confirmation")).get("seed"));
        assertThrows(UnsupportedOperationException.class,
                () -> state.values().put("other", true));
    }

    @Test
    void malformedKeyCharacterIsRejectedBeforeAnyInputDispatch() throws Exception {
        Path evidence = temporaryDirectory.resolve("malformed-key-evidence");
        BenchmarkControl control = openControl(
                "{\"command\":\"key\",\"action\":\"press\",\"key\":\"A\","
                        + "\"character\":\"ab\",\"shift\":true,\"control\":true}",
                evidence);
        RecordingStage stage = new RecordingStage();

        control.beforeFrame(stage);
        control.afterCompletedFrame(CandidateState.empty());

        assertTrue(stage.events.isEmpty());
        assertTrue(stage.keysDown.isEmpty());
        JsonValue result = new JsonReader().parse(Files.readString(
                evidence.resolve("results.ndjson"), StandardCharsets.UTF_8));
        assertFalse(result.getBoolean("ok"));
        assertEquals("INVALID_KEY", result.getString("error"));
    }

    @Test
    void keyCallbackFailureReleasesPressedKeyAndModifiersWithoutReplacingFailure()
            throws Exception {
        BenchmarkControl control = openControl(
                "{\"command\":\"key\",\"action\":\"press\",\"key\":\"A\","
                        + "\"character\":\"a\",\"shift\":true,\"control\":true}",
                temporaryDirectory.resolve("callback-key-evidence"));
        RecordingStage stage = new RecordingStage();
        CallbackFailure callbackFailure = new CallbackFailure();
        stage.typedFailure = callbackFailure;

        CallbackFailure thrown = assertThrows(
                CallbackFailure.class, () -> control.beforeFrame(stage));

        assertSame(callbackFailure, thrown);
        assertEquals(List.of(
                "down:" + Input.Keys.SHIFT_LEFT,
                "down:" + Input.Keys.CONTROL_LEFT,
                "down:" + Input.Keys.A,
                "typed:a",
                "up:" + Input.Keys.A,
                "up:" + Input.Keys.CONTROL_LEFT,
                "up:" + Input.Keys.SHIFT_LEFT), stage.events);
        assertTrue(stage.keysDown.isEmpty());
    }

    @Test
    @Timeout(40)
    void launchesCapturesBothViewportsRejectsUnsafeCommandsAndExitsCleanly()
            throws Exception {
        Path evidence = temporaryDirectory.resolve("evidence");
        Path commands = temporaryDirectory.resolve("commands.ndjson");
        Files.writeString(commands, String.join("\n", List.of(
                "{\"command\":\"unknown\"}",
                "{\"command\":\"capture\",\"id\":\"../escape\"}",
                "{\"command\":\"resize\",\"width\":1280,\"height\":720}",
                "{\"command\":\"capture\",\"id\":\"initial-1280x720\"}",
                "{\"command\":\"resize\",\"width\":1920,\"height\":1080}",
                "{\"command\":\"capture\",\"id\":\"initial-1920x1080\"}",
                "{\"command\":\"close\"}"
        )) + "\n", StandardCharsets.UTF_8);

        ProcessResult process = launch(commands, evidence);

        assertEquals(0, process.exitCode(), process.output());
        assertValidPng(evidence.resolve("captures/initial-1280x720.png"), 1280, 720);
        assertValidPng(evidence.resolve("captures/initial-1920x1080.png"), 1920, 1080);
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.png")));

        List<String> resultLines = Files.readAllLines(
                evidence.resolve("results.ndjson"), StandardCharsets.UTF_8);
        assertEquals(7, resultLines.size());
        List<JsonValue> results = resultLines.stream()
                .map(line -> new JsonReader().parse(line))
                .toList();
        assertEquals("UNKNOWN_COMMAND", results.get(0).getString("error"));
        assertFalse(results.get(0).getBoolean("ok"));
        assertEquals("INVALID_CAPTURE_ID", results.get(1).getString("error"));
        assertFalse(results.get(1).getBoolean("ok"));
        assertEquals("captures/initial-1280x720.png",
                results.get(3).getString("artifact"));
        assertEquals("captures/initial-1920x1080.png",
                results.get(5).getString("artifact"));
        assertTrue(results.get(6).getBoolean("ok"));

        try (var paths = Files.walk(evidence)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".tmp-")),
                    "atomic evidence writes must not leave temporary files");
        }
    }

    private BenchmarkControl openControl(String command, Path evidence) throws IOException {
        Path commands = temporaryDirectory.resolve(
                "unit-commands-" + evidence.getFileName() + ".ndjson");
        Files.writeString(commands, command + "\n", StandardCharsets.UTF_8);
        return BenchmarkControl.open(commands, evidence);
    }

    private ProcessResult launch(Path commands, Path evidence) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        String classpath = System.getProperty("template.runtimeClasspath");
        Path output = temporaryDirectory.resolve("candidate-process.log");
        ProcessBuilder builder = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            builder.command(javaExecutable, "-XstartOnFirstThread", "-cp", classpath,
                    CandidateLauncher.class.getName(), "--commands", commands.toString(),
                    "--evidence", evidence.toString());
        } else {
            builder.command(javaExecutable, "-cp", classpath,
                    CandidateLauncher.class.getName(), "--commands", commands.toString(),
                    "--evidence", evidence.toString());
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        boolean exited = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor();
        }
        String processOutput = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(exited, "candidate process timed out:\n" + processOutput);
        return new ProcessResult(process.exitValue(), processOutput);
    }

    private static void assertMethod(
            Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        assertEquals(returnType, method.getReturnType());
    }

    private static void assertValidPng(Path path, int width, int height) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        assertTrue(bytes.length > 8);
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 'P', bytes[1]);
        assertEquals((byte) 'N', bytes[2]);
        assertEquals((byte) 'G', bytes[3]);
        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image);
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }

    private static final class RecordingStage extends Stage {
        private final List<String> events = new ArrayList<>();
        private final Set<Integer> keysDown = new HashSet<>();
        private RuntimeException typedFailure;

        private RecordingStage() {
            super(testViewport(), noOpBatch());
        }

        @Override public boolean keyDown(int keyCode) {
            events.add("down:" + keyCode);
            keysDown.add(keyCode);
            return false;
        }

        @Override public boolean keyUp(int keyCode) {
            events.add("up:" + keyCode);
            keysDown.remove(keyCode);
            return false;
        }

        @Override public boolean keyTyped(char character) {
            events.add("typed:" + character);
            if (typedFailure != null) {
                throw typedFailure;
            }
            return false;
        }
    }

    private static ScreenViewport testViewport() {
        GdxNativesLoader.load();
        Gdx.graphics = (Graphics) Proxy.newProxyInstance(
                Graphics.class.getClassLoader(),
                new Class<?>[] {Graphics.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWidth", "getHeight", "getBackBufferWidth",
                            "getBackBufferHeight" -> 1;
                    default -> primitiveDefault(method.getReturnType());
                });
        GL20 gl = (GL20) Proxy.newProxyInstance(
                GL20.class.getClassLoader(),
                new Class<?>[] {GL20.class},
                (proxy, method, args) -> primitiveDefault(method.getReturnType()));
        Gdx.gl = gl;
        Gdx.gl20 = gl;
        return new ScreenViewport();
    }

    private static Batch noOpBatch() {
        return (Batch) Proxy.newProxyInstance(
                Batch.class.getClassLoader(),
                new Class<?>[] {Batch.class},
                (proxy, method, args) -> primitiveDefault(method.getReturnType()));
    }

    private static Object primitiveDefault(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        return 0d;
    }

    private static final class CallbackFailure extends RuntimeException {
        private CallbackFailure() {
            super("synthetic callback failure");
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
