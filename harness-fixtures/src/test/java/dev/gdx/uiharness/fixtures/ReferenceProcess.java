package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Launches and owns one fresh hidden LWJGL3 process used only by the smoke test. */
final class ReferenceProcess implements AutoCloseable {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(15);

    private final Path root;
    private final Process process;
    private final StringBuilder stderr = new StringBuilder();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Thread errorPump;
    private boolean cleanExit;
    private boolean closed;

    private ReferenceProcess(Path root, Process process) {
        this.root = root;
        this.process = process;
        errorPump = Thread.ofVirtual().name("reference-process-stderr").start(this::pumpErrors);
    }

    static ReferenceProcess launch() throws Exception {
        String classpath = System.getProperty("reference.app.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Gradle did not provide the reference app classpath");
        }
        Path root = Files.createTempDirectory("gdx-ui-reference-");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath,
                ReferenceUiApplication.class.getName(),
                root.toString());
        Process process = builder.start();
        ReferenceProcess reference = new ReferenceProcess(root, process);
        try {
            reference.ready.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(process.isAlive(), reference::diagnostics);
            return reference;
        } catch (Throwable failure) {
            reference.close();
            throw failure;
        }
    }

    Path root() {
        return root;
    }

    InputStream mcpInput() {
        return process.getInputStream();
    }

    OutputStream mcpOutput() {
        return process.getOutputStream();
    }

    byte[] readArtifact(HarnessMcpClient.Artifact artifact) throws Exception {
        byte[] bytes = findStoredArtifact(artifact.sha256());
        assertEquals(artifact.byteLength(), bytes.length);
        return bytes;
    }

    byte[] readArtifact(String reference, String mediaType) throws Exception {
        assertTrue(reference.startsWith("artifact:"));
        if (!"application/zip".equals(mediaType)) {
            throw new IllegalArgumentException("Unsupported fixture media type " + mediaType);
        }
        try (var paths = Files.walk(root.resolve("artifacts"))) {
            return paths.filter(Files::isRegularFile)
                    .map(this::readUnchecked)
                    .filter(bytes -> bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K')
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No persisted ZIP artifact below the process root"));
        }
    }

    void awaitCleanExit() throws Exception {
        int exit = process.onExit().get(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).exitValue();
        errorPump.join(EXIT_TIMEOUT);
        assertEquals(0, exit, diagnostics());
        assertTrue(stderr.toString().contains("REFERENCE_UI_CLOSED"), diagnostics());
        assertFalse(stderr.toString().contains("REFERENCE_UI_CLOSE_FAILED"), diagnostics());
        cleanExit = true;
    }

    boolean lifecycleClosed() {
        return cleanExit && !process.isAlive();
    }

    @Override public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try {
            process.getOutputStream().close();
            if (process.isAlive() && !process.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                throw new IllegalStateException("Reference process required forced termination\n"
                        + diagnostics());
            }
        } finally {
            errorPump.join(EXIT_TIMEOUT);
            if (Files.exists(root)) {
                deleteTree(root);
            }
        }
    }

    private byte[] findStoredArtifact(String expectedSha256) throws Exception {
        try (var paths = Files.walk(root.resolve("artifacts"))) {
            return paths.filter(Files::isRegularFile)
                    .map(this::readUnchecked)
                    .filter(bytes -> expectedSha256.equals(sha256(bytes)))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No persisted artifact with SHA-256 " + expectedSha256));
        }
    }

    private byte[] readUnchecked(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read persisted artifact", failure);
        }
    }

    private void pumpErrors() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderr) {
                    stderr.append(line).append('\n');
                }
                if (line.equals("REFERENCE_UI_READY")) {
                    ready.complete(null);
                }
            }
            if (!ready.isDone()) {
                ready.completeExceptionally(new IllegalStateException(diagnostics()));
            }
        } catch (IOException failure) {
            ready.completeExceptionally(failure);
        }
    }

    private String diagnostics() {
        synchronized (stderr) {
            return "Reference process stderr:\n" + stderr;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
