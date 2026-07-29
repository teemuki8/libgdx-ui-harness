package benchmark.palisade;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.mcp.HarnessToolCatalog;
import dev.gdx.uiharness.protocol.ProtocolJson;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** NDJSON front end for exactly the nine bounded V1 MCP-equivalent operations. */
public final class HarnessCli {
    private static final ObjectMapper JSON = ProtocolJson.mapper();
    private static final Set<String> OPERATIONS = new HarnessToolCatalog().toolNames();
    private static final int MAX_COMMANDS = 10_000;
    private static final String CANDIDATE_CLASS =
            "benchmark.palisade.SkirmishConfigurationUi";
    private static final Path ARTIFACT_ROOT = Path.of("build", "harness-artifacts");

    private HarnessCli() {
    }

    /** Runs the treatment launcher. It accepts no class, script, command, or path arguments. */
    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("HarnessCli accepts no process arguments");
        }
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Candidate UI with harness");
        configuration.setWindowedMode(1280, 720);
        configuration.setWindowSizeLimits(1, 1, 4096, 4096);
        configuration.setResizable(false);
        configuration.setHdpiMode(HdpiMode.Pixels);
        configuration.setBackBufferConfig(8, 8, 8, 8, 24, 8, 0);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.setIdleFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new HarnessApplication(), configuration);
    }

    /** Consumes bounded NDJSON until EOF and emits one bounded JSON response per input line. */
    public static void run(HarnessBridge bridge, InputStream input, OutputStream output) {
        Objects.requireNonNull(bridge, "bridge");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        int commandCount = 0;
        try {
            while (true) {
                byte[] line = readBoundedLine(input);
                if (line == null) return;
                if (++commandCount > MAX_COMMANDS) {
                    write(output, error("limit-exceeded", "CLI command limit exceeded"));
                    return;
                }
                if (line.length > ProtocolJson.MAX_REQUEST_BYTES) {
                    write(output, error("limit-exceeded", "JSON command exceeds byte limit"));
                    return;
                }
                if (line.length == 0) {
                    write(output, error("invalid-request", "Empty JSON command"));
                    continue;
                }
                write(output, execute(bridge, line));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Harness CLI I/O failed", failure);
        }
    }

    private static Map<String, Object> execute(HarnessBridge bridge, byte[] line) {
        if (line.length > ProtocolJson.MAX_REQUEST_BYTES) {
            return error("limit-exceeded", "JSON command exceeds byte limit");
        }
        Map<?, ?> envelope;
        try {
            envelope = JSON.readValue(line, Map.class);
        } catch (IOException | RuntimeException failure) {
            return error("invalid-request", "Malformed JSON command");
        }
        if (!envelope.keySet().equals(Set.of("operation", "arguments"))) {
            return error("invalid-request", "Expected only operation and arguments");
        }
        Object operationValue = envelope.get("operation");
        Object argumentsValue = envelope.get("arguments");
        if (!(operationValue instanceof String operation) || !OPERATIONS.contains(operation)) {
            return error("unknown-operation", "Operation is not allowlisted");
        }
        if (!(argumentsValue instanceof Map<?, ?> rawArguments)) {
            return error("invalid-request", "arguments must be a JSON object");
        }
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawArguments.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return error("invalid-request", "argument names must be strings");
            }
            arguments.put(key, entry.getValue());
        }
        try {
            McpSchema.CallToolResult result = bridge.call(operation, arguments)
                    .toCompletableFuture().join();
            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("ok", !result.isError());
            if (result.isError()) {
                Object structured = result.structuredContent();
                response.put("error", structured == null
                        ? Map.of("code", "operation-failed", "message", "Harness operation failed")
                        : structured);
            } else {
                response.put("result", result.structuredContent());
            }
            return Map.copyOf(response);
        } catch (RuntimeException failure) {
            return error("operation-failed", "Harness operation failed");
        }
    }

    private static byte[] readBoundedLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean sawInput = false;
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (!sawInput) return null;
                break;
            }
            if (value == '\n') break;
            if (value == '\r') continue;
            sawInput = true;
            if (line.size() >= ProtocolJson.MAX_REQUEST_BYTES) {
                return new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];
            }
            line.write(value);
        }
        return line.toByteArray();
    }

    private static void write(OutputStream output, Map<String, Object> response)
            throws IOException {
        byte[] encoded = JSON.writeValueAsBytes(response);
        if (encoded.length > ProtocolJson.MAX_RESPONSE_BYTES) {
            encoded = JSON.writeValueAsBytes(
                    error("limit-exceeded", "JSON response exceeds byte limit"));
        }
        output.write(encoded);
        output.write('\n');
        output.flush();
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("ok", false, "error", Map.of("code", code, "message", message));
    }

    private static final class HarnessApplication extends ApplicationAdapter {
        private static final float FIXED_STEP_SECONDS = 1f / 60f;
        private CandidateUi candidate;
        private HarnessBridge bridge;
        private Thread cliThread;

        @Override public void create() {
            candidate = loadCandidate();
            Stage stage = requireStage();
            stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            candidate.showInitial();
            Gdx.input.setInputProcessor(stage);
            bridge = HarnessBridge.open(candidate, ARTIFACT_ROOT);
            cliThread = Thread.ofVirtual().name("palisade-harness-cli").start(() -> {
                try {
                    run(bridge, System.in, System.out);
                } finally {
                    Gdx.app.postRunnable(Gdx.app::exit);
                }
            });
        }

        @Override public void render() {
            Stage stage = requireStage();
            bridge.beforeRender();
            stage.act(FIXED_STEP_SECONDS);
            Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            stage.draw();
            bridge.afterRender();
        }

        @Override public void resize(int width, int height) {
            if (candidate != null) requireStage().getViewport().update(width, height, true);
        }

        @Override public void dispose() {
            RuntimeException failure = null;
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                }
            }
            if (candidate != null) {
                try {
                    candidate.dispose();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (cliThread != null && cliThread.isAlive()) cliThread.interrupt();
            if (failure != null) throw failure;
        }

        private Stage requireStage() {
            return Objects.requireNonNull(candidate.stage(), "candidate.stage()");
        }

        private static CandidateUi loadCandidate() {
            Class<?> type;
            try {
                type = Class.forName(CANDIDATE_CLASS);
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("Candidate class is missing", failure);
            }
            if (!CandidateUi.class.isAssignableFrom(type)) {
                throw new IllegalStateException(CANDIDATE_CLASS + " must implement CandidateUi");
            }
            try {
                return (CandidateUi) type.getConstructor().newInstance();
            } catch (NoSuchMethodException failure) {
                throw new IllegalStateException(
                        CANDIDATE_CLASS + " must have a public no-argument constructor", failure);
            } catch (InstantiationException | IllegalAccessException
                    | InvocationTargetException failure) {
                throw new IllegalStateException("Could not construct candidate", failure);
            }
        }
    }
}
