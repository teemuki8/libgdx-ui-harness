package dev.gdx.uiharness.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TraceRecorderTest {
    @TempDir Path temporaryDirectory;

    @Test void recordsCausalEvidenceAndDeduplicatesStreamedArtifacts() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, clock);
        TraceRecorder.Limits limits = new TraceRecorder.Limits(
                1_000_000, 100, Duration.ofMinutes(1));
        recorder.start("session-a", limits);
        SemanticSnapshot before = snapshot(7, 40);
        SemanticSnapshot after = snapshot(8, 41);

        long started = recorder.record(TraceEvent.commandStarted(
                "session-a", "request-1", 100, before, Map.of("command", "click")));
        long input = recorder.record(TraceEvent.inputDispatched(
                "session-a", "request-1", 101, 40, 7, started,
                Map.of("button", "left")));
        recorder.record(TraceEvent.commandCompleted(
                "session-a", "request-1", 102, after, input,
                Map.of("result", "ok")));
        byte[] png = "small-png-evidence".getBytes(StandardCharsets.UTF_8);
        String firstHash = recorder.addArtifact(
                "image/png", new ByteArrayInputStream(png));
        String secondHash = recorder.addArtifact(
                "image/png", new ByteArrayInputStream(png));

        TraceManifest manifest = recorder.stop();
        TraceReplay replay = new TraceReplayer().load(manifest.archive());

        assertTrue(manifest.complete());
        assertEquals(3, manifest.eventCount());
        assertEquals(1, manifest.artifactCount());
        assertEquals(firstHash, secondHash);
        assertEquals(List.of(7L, 8L), replay.semanticRevisions());
        assertTrue(replay.causality().isValid(), replay.causality().errors().toString());
        assertFalse(replay.partial());
        assertEquals(1, countZipEntries(manifest.archive(), "artifacts/" + firstHash));
    }

    @Test void redactsCallerPathsBeforeWritingEvents() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-redacted", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted("session-redacted", "request-redacted", 1,
                snapshot(1, 1), Map.of(
                        "unix", "file:///home/private/screen.png",
                        "windows", "C:\\Users\\private\\screen.png",
                        "stack", "at game.Actor.run(Actor.java:42)")));
        Path archive = recorder.stop().archive();

        String events = readZipEntry(archive, "events.ndjson");
        assertFalse(events.contains("/home/private"));
        assertFalse(events.contains("C:\\Users"));
        assertFalse(events.contains("Actor.java"));
        assertTrue(events.contains("[redacted]"));
    }

    @Test void eventLimitFailsTypedAndFinalizesReadablePartialTrace() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-limited", new TraceRecorder.Limits(
                1_000_000, 1, Duration.ofMinutes(1)));
        recorder.record(TraceEvent.commandStarted(
                "session-limited", "request-1", 1, snapshot(1, 1), Map.of()));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> recorder.record(TraceEvent.commandCompleted(
                        "session-limited", "request-1", 2, snapshot(2, 2), 0, Map.of())));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        TraceManifest manifest = recorder.lastManifest().orElseThrow();
        assertFalse(manifest.complete());
        assertEquals("event limit exceeded", manifest.terminationReason());
        TraceReplay replay = new TraceReplayer().load(manifest.archive());
        assertTrue(replay.partial());
        assertEquals(1, replay.manifest().eventCount());
        assertTrue(noTemporaryFiles(temporaryDirectory));
    }

    @Test void byteAndDurationLimitsPreservePartialManifest() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        TraceRecorder bytesRecorder = new TraceRecorder(temporaryDirectory.resolve("bytes"), clock);
        bytesRecorder.start("session-bytes", new TraceRecorder.Limits(
                8, 10, Duration.ofMinutes(1)));
        HarnessException bytesFailure = assertThrows(HarnessException.class,
                () -> bytesRecorder.addArtifact("application/octet-stream",
                        new ByteArrayInputStream(new byte[9])));
        assertEquals(ErrorCode.LIMIT_EXCEEDED, bytesFailure.code());
        assertFalse(bytesRecorder.lastManifest().orElseThrow().complete());

        TraceRecorder durationRecorder = new TraceRecorder(
                temporaryDirectory.resolve("duration"), clock);
        durationRecorder.start("session-time", new TraceRecorder.Limits(
                1_000_000, 10, Duration.ofSeconds(5)));
        clock.advance(Duration.ofSeconds(5));
        HarnessException timeFailure = assertThrows(HarnessException.class,
                () -> durationRecorder.record(TraceEvent.commandStarted(
                        "session-time", "request-time", 1, snapshot(1, 1), Map.of())));
        assertEquals(ErrorCode.LIMIT_EXCEEDED, timeFailure.code());
        assertFalse(durationRecorder.lastManifest().orElseThrow().complete());
        assertTrue(noTemporaryFiles(temporaryDirectory));
    }

    @Test void closeFinalizesInterruptedTraceAndReleasesCallbacks() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-interrupted", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-interrupted", "request-1", 1, snapshot(1, 1), Map.of()));

        recorder.close();

        TraceManifest manifest = recorder.lastManifest().orElseThrow();
        assertFalse(manifest.complete());
        assertEquals("interrupted", manifest.terminationReason());
        assertTrue(new TraceReplayer().load(manifest.archive()).partial());
        assertTrue(noTemporaryFiles(temporaryDirectory));
        recorder.close();
    }

    @Test void artifactCallbackFailureClosesSourceAndPublishesPartialEvidence()
            throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-callback", TraceRecorder.Limits.defaults());
        java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.io.InputStream failing = new java.io.InputStream() {
            @Override public int read() {
                throw new IllegalStateException("caller failure");
            }

            @Override public void close() {
                closed.set(true);
            }
        };

        HarnessException failure = assertThrows(HarnessException.class,
                () -> recorder.addArtifact("application/octet-stream", failing));

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
        assertTrue(closed.get());
        TraceManifest manifest = recorder.lastManifest().orElseThrow();
        assertFalse(manifest.complete());
        assertTrue(new TraceReplayer().load(manifest.archive()).partial());
        assertTrue(noTemporaryFiles(temporaryDirectory));
    }

    @Test void traceRootMayHaveCanonicalizingParentSymlinks() throws Exception {
        Path physicalParent = temporaryDirectory.resolve("physical");
        Path aliasParent = temporaryDirectory.resolve("alias");
        Files.createDirectory(physicalParent);
        try {
            Files.createSymbolicLink(aliasParent, physicalParent);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symbolic links are unavailable: " + exception.getMessage());
        }
        TraceRecorder recorder =
                new TraceRecorder(aliasParent.resolve("traces"), Clock.systemUTC());

        recorder.start("session-canonical-parent", TraceRecorder.Limits.defaults());
        TraceManifest manifest = recorder.stop();

        assertTrue(manifest.complete());
        assertTrue(Files.isRegularFile(manifest.archive()));
    }

    @Test void replacedStagingDirectoryCannotReachOutsideTraceRoot() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-symlink", TraceRecorder.Limits.defaults());
        Path staging;
        try (var entries = Files.list(temporaryDirectory)) {
            staging = entries.filter(path -> Files.isDirectory(path,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .findFirst()
                    .orElseThrow();
        }
        Path artifactDirectory = staging.resolve("artifacts");
        Path moved = temporaryDirectory.resolve("moved-artifacts");
        Path outside = temporaryDirectory.resolveSibling(
                temporaryDirectory.getFileName() + "-outside");
        Files.move(artifactDirectory, moved);
        Files.createDirectory(outside);
        Path outsideArtifact = outside.resolve("evidence.bin");
        Files.writeString(outsideArtifact, "outside", StandardCharsets.UTF_8);
        Files.createSymbolicLink(artifactDirectory, outside);
        try {
            HarnessException failure = assertThrows(HarnessException.class, recorder::close);
            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals("outside", Files.readString(outsideArtifact, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(artifactDirectory);
            Files.deleteIfExists(moved);
            Files.deleteIfExists(outsideArtifact);
            Files.deleteIfExists(outside);
        }
    }

    @Test void coreTraceRuntimeRemainsJdkOnly() {
        String[] classPath = System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
        assertFalse(java.util.Arrays.stream(classPath)
                .map(path -> Path.of(path).getFileName().toString())
                .anyMatch(name -> name.startsWith("jackson-")));
    }

    @Test void defaultRecorderCannotEmitEventUnreadableByDefaultReplayer() {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-line-limit", TraceRecorder.Limits.defaults());
        Map<String, String> oversizedEvidence = new java.util.LinkedHashMap<>();
        String value = "x".repeat(TraceEvent.MAX_TEXT_LENGTH);
        for (int index = 0; index < TraceEvent.MAX_EVIDENCE_ENTRIES; index++) {
            oversizedEvidence.put("key-" + index, value);
        }

        HarnessException failure = assertThrows(HarnessException.class,
                () -> recorder.record(TraceEvent.commandStarted(
                        "session-line-limit", "request-line-limit", 1,
                        snapshot(1, 1), oversizedEvidence)));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        TraceManifest manifest = recorder.lastManifest().orElseThrow();
        assertFalse(manifest.complete());
        assertTrue(new TraceReplayer().load(manifest.archive()).partial());
    }

    @Test void blockingArtifactProducerCannotPreventConcurrentClose() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-blocked", TraceRecorder.Limits.defaults());
        BlockingInputStream source = new BlockingInputStream();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var add = executor.submit(() ->
                    recorder.addArtifact("application/octet-stream", source));
            assertTrue(source.entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            var close = executor.submit(recorder::close);
            try {
                close.get(1, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                source.release.countDown();
            }
            java.util.concurrent.ExecutionException failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> add.get(1, java.util.concurrent.TimeUnit.SECONDS));
            HarnessException typed = (HarnessException) failure.getCause();
            assertEquals(ErrorCode.SESSION_CLOSED, typed.code());
        }
        assertTrue(source.closed);
        assertFalse(recorder.lastManifest().orElseThrow().complete());
        assertTrue(noTemporaryFiles(temporaryDirectory));
    }

    @Test void recorderDoesNotRetainWholeTraceOrArtifactByteArrays() {
        for (Field field : TraceRecorder.class.getDeclaredFields()) {
            assertNotEquals(byte[].class, field.getType(), field.toString());
            assertNotEquals(ByteArrayOutputStream.class, field.getType(), field.toString());
            assertFalse(Collection.class.isAssignableFrom(field.getType()), field.toString());
        }
    }

    private static SemanticSnapshot snapshot(long revision, long frame) {
        Bounds bounds = new Bounds(0, 0, 100, 100);
        SemanticNode root = new SemanticNode("root", null, List.of(), Role.GROUP, "root", "",
                null, null, null, null, state(), bounds, bounds, bounds, 0, Map.of());
        return new SemanticSnapshot(revision, frame, "root", Map.of("root", root));
    }

    private static SemanticState state() {
        return new SemanticState(true, true, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, 1.0,
                false, true, true);
    }

    private static long countZipEntries(Path archive, String name) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            return zip.stream().filter(entry -> entry.getName().equals(name)).count();
        }
    }

    private static String readZipEntry(Path archive, String name) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            java.util.zip.ZipEntry entry = Optional.ofNullable(zip.getEntry(name)).orElseThrow();
            try (var input = zip.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static boolean noTemporaryFiles(Path root) throws Exception {
        if (Files.notExists(root)) {
            return true;
        }
        try (var paths = Files.walk(root)) {
            return paths.noneMatch(path -> path.getFileName().toString().contains(".tmp"));
        }
    }

    private static final class BlockingInputStream extends java.io.InputStream {
        private final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        private volatile boolean closed;

        @Override public int read() throws java.io.IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted", exception);
            }
            return -1;
        }

        @Override public void close() {
            closed = true;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }
}
