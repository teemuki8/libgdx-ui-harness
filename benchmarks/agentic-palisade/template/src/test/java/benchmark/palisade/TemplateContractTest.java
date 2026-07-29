package benchmark.palisade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private record ProcessResult(int exitCode, String output) {
    }
}
