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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Launches and owns one fresh hidden LWJGL3 process used only by the smoke test. */
final class ReferenceProcess implements AutoCloseable {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
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
        ProcessBuilder builder = new ProcessBuilder(ReferenceJvmCommand.build(
                java,
                classpath,
                System.getProperty("os.name"),
                root.toString()));
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
        ArtifactProof proof = proof(artifact.reference());
        assertEquals(artifact.mediaType(), proof.mediaType());
        assertEquals(artifact.byteLength(), proof.byteLength());
        assertEquals(artifact.sha256(), proof.sha256());
        return findUniqueStoredArtifact(proof);
    }

    byte[] readArtifact(String reference, String mediaType) throws Exception {
        ArtifactProof proof = proof(reference);
        assertEquals(mediaType, proof.mediaType());
        return findUniqueStoredArtifact(proof);
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

    private ArtifactProof proof(String reference) throws IOException {
        if (reference == null || !reference.matches("artifact:[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid opaque artifact reference");
        }
        String id = reference.substring("artifact:".length());
        Path receipt = root.resolve("proofs").resolve(id + ".receipt");
        if (!Files.isRegularFile(receipt)) {
            throw new IllegalArgumentException("Unknown opaque artifact reference");
        }
        List<String> fields = Files.readAllLines(receipt, StandardCharsets.UTF_8);
        if (fields.size() != 4 || !reference.equals(fields.get(0))) {
            throw new IllegalStateException("Malformed artifact proof receipt");
        }
        long byteLength;
        try {
            byteLength = Long.parseLong(fields.get(2));
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Malformed artifact proof byte length", failure);
        }
        return new ArtifactProof(reference, fields.get(1), byteLength, fields.get(3));
    }

    private byte[] findUniqueStoredArtifact(ArtifactProof proof) throws Exception {
        List<byte[]> matches;
        try (var paths = Files.walk(root.resolve("artifacts"))) {
            matches = paths.filter(Files::isRegularFile)
                    .map(this::readUnchecked)
                    .filter(bytes -> proof.sha256().equals(sha256(bytes)))
                    .toList();
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected one stored blob for " + proof.reference()
                            + ", found " + matches.size());
        }
        byte[] bytes = matches.getFirst();
        assertEquals(proof.byteLength(), bytes.length);
        return bytes;
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

    private record ArtifactProof(
            String reference, String mediaType, long byteLength, String sha256) {}

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
