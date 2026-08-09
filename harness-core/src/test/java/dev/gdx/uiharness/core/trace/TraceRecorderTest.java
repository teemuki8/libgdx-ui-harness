package dev.gdx.uiharness.core.trace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    @Test void finalizedManifestBindsEventDigestAndArtifactIdentities() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-bound", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-bound", "request-1", 1, snapshot(1, 1), Map.of()));
        byte[] png = "png-bytes".getBytes(StandardCharsets.UTF_8);
        String hash = recorder.addArtifact("image/png", new ByteArrayInputStream(png));
        Path archive = recorder.stop().archive();

        byte[] manifestJson;
        byte[] eventsBytes;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            manifestJson = zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes();
            eventsBytes = zip.getInputStream(zip.getEntry("events.ndjson")).readAllBytes();
        }
        TraceManifest manifest = TraceJson.decodeManifest(archive, manifestJson);

        assertEquals(TraceManifest.V2, manifest.schemaVersion());
        assertEquals(sha256(eventsBytes), manifest.eventsSha256());
        TraceManifest.ArtifactBinding binding = manifest.artifacts().get(hash);
        assertNotNull(binding);
        assertEquals(hash, binding.sha256());
        assertEquals(png.length, binding.size());
        assertEquals("image/png", binding.mediaType());
        assertEquals(eventsBytes.length, manifest.uncompressedBytes() - png.length);
    }

    @Test void legacyV1ManifestWithoutBindingsStillDecodes() throws Exception {
        byte[] json = ("{\"sessionId\":\"session-a\",\"startedAt\":"
                + "\"2026-07-28T00:00:00Z\",\"endedAt\":"
                + "\"2026-07-28T00:00:01Z\",\"complete\":false,"
                + "\"terminationReason\":\"interrupted\",\"eventCount\":1,"
                + "\"artifactCount\":0,\"uncompressedBytes\":100}")
                .getBytes(StandardCharsets.UTF_8);

        TraceManifest manifest = TraceJson.decodeManifest(
                temporaryDirectory.resolve("legacy.zip"), json);

        assertEquals(TraceManifest.V1, manifest.schemaVersion());
        assertNull(manifest.eventsSha256());
        assertTrue(manifest.artifacts().isEmpty());
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

    @Test void symlinkedAncestorIsRejected() throws Exception {
        Path physicalParent = temporaryDirectory.resolve("physical");
        Path aliasParent = temporaryDirectory.resolve("alias");
        Files.createDirectory(physicalParent);
        try {
            Files.createSymbolicLink(aliasParent, physicalParent);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(IllegalArgumentException.class,
                () -> new TraceRecorder(aliasParent.resolve("traces"), Clock.systemUTC()));
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

    @Test void symlinkSwapAtOpenAbortsFinalizationWithoutFollowingTarget() throws Exception {
        Path outside = temporaryDirectory.resolveSibling(
                temporaryDirectory.getFileName() + "-secret");
        Files.writeString(outside, "victim-secret", StandardCharsets.UTF_8);
        try {
            TraceRecorder recorder = new TraceRecorder(temporaryDirectory,
                    Clock.systemUTC(), (step, path) -> {
                        if (step == TraceRecorder.FinalizationInterceptor.Step.OPEN_EVIDENCE
                                && path.getFileName().toString().equals("events.ndjson")) {
                            Files.deleteIfExists(path);
                            Files.createSymbolicLink(path, outside); // anchored open throws
                        }
                    });
            recorder.start("session-race", TraceRecorder.Limits.defaults());
            recorder.record(TraceEvent.commandStarted(
                    "session-race", "request-1", 1, snapshot(1, 1), Map.of()));

            HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals("victim-secret", Files.readString(outside, StandardCharsets.UTF_8));
            try (var paths = Files.walk(temporaryDirectory)) {
                Path swapped = paths
                        .filter(path -> path.getFileName().toString().equals("events.ndjson"))
                        .findFirst().orElseThrow();
                assertTrue(Files.isSymbolicLink(swapped),
                        "the substituted entry must be left untouched, not deleted through");
            }
            assertNull(publishedArchives(temporaryDirectory),
                    "no archive may be published from tampered staging");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test void swapAndRestoreOfValidatedFileCannotDefeatHandleContentIdentity()
            throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory,
                Clock.systemUTC(), (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.OPEN_EVIDENCE
                            && path.getFileName().toString().equals("events.ndjson")) {
                        byte[] original = Files.readAllBytes(path);
                        byte[] substituted = new byte[original.length];
                        java.util.Arrays.fill(substituted, (byte) 'A');
                        Files.write(path, substituted); // same size, different content
                    }
                });
        recorder.start("session-restore", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-restore", "request-1", 1, snapshot(1, 1), Map.of()));

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("digest"));
        // Fail-closed cleanup: the tampered file's recorded content evidence no
        // longer matches, so it is left untouched as residual rather than deleted.
        try (var paths = Files.walk(temporaryDirectory)) {
            Path tampered = paths
                    .filter(path -> path.getFileName().toString().equals("events.ndjson"))
                    .findFirst().orElseThrow();
            byte[] remaining = Files.readAllBytes(tampered);
            assertTrue(remaining.length > 0,
                    "the tampered events file must remain as residual evidence");
            boolean allA = true;
            for (byte value : remaining) {
                if (value != 'A') {
                    allA = false;
                    break;
                }
            }
            assertTrue(allA, "the tampered events content must never be deleted");
        }
        assertNull(publishedArchives(temporaryDirectory),
                "no archive may be published from substituted evidence");
    }

    @Test void symlinkSubstitutedForArtifactAtFinalizeIsRejected() throws Exception {
        Path outside = temporaryDirectory.resolveSibling(
                temporaryDirectory.getFileName() + "-artifact-secret");
        Files.writeString(outside, "artifact-secret", StandardCharsets.UTF_8);
        try {
            TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
            recorder.start("session-swap", TraceRecorder.Limits.defaults());
            recorder.record(TraceEvent.commandStarted(
                    "session-swap", "request-1", 1, snapshot(1, 1), Map.of()));
            recorder.addArtifact("image/png",
                    new ByteArrayInputStream("evidence".getBytes(StandardCharsets.UTF_8)));
            Path artifactFile;
            try (var paths = Files.walk(temporaryDirectory)) {
                artifactFile = paths
                        .filter(path -> path.getParent() != null
                                && path.getParent().getFileName().toString().equals("artifacts"))
                        .filter(path -> Files.isRegularFile(path,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .findFirst().orElseThrow();
            }
            Files.delete(artifactFile);
            Files.createSymbolicLink(artifactFile, outside);

            HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals("artifact-secret",
                    Files.readString(outside, StandardCharsets.UTF_8));
            assertTrue(Files.isSymbolicLink(artifactFile),
                    "the substituted artifact must be left untouched, not deleted through");
            assertNull(publishedArchives(temporaryDirectory),
                    "no archive may be published from tampered staging");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test void stagingDirectoriesAndFilesAreOwnerOnlyWherePosixSupported() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "posix permissions unavailable");
        }
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-perms", TraceRecorder.Limits.defaults());
        Set<PosixFilePermission> ownerOnlyDirectory = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        Set<PosixFilePermission> ownerOnlyFile = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.toList()) {
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    assertEquals(ownerOnlyDirectory,
                            Files.getPosixFilePermissions(path), path.toString());
                } else if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    assertEquals(ownerOnlyFile,
                            Files.getPosixFilePermissions(path), path.toString());
                }
            }
        }
    }

    @Test void permissiveTraceRootIsRejected() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "posix permissions unavailable");
        }
        Path root = temporaryDirectory.resolve("permissive-root");
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE));

        assertThrows(IllegalArgumentException.class,
                () -> new TraceRecorder(root, Clock.systemUTC()));
    }

    @Test void traceStorageWithoutSecurePermissionViewFailsClosed() throws Exception {
        Path zipPath = temporaryDirectory.resolve("zipfs.zip");
        try (java.nio.file.FileSystem zipFs = java.nio.file.FileSystems.newFileSystem(
                java.net.URI.create("jar:" + zipPath.toUri()), Map.of("create", "true"))) {
            Path root = zipFs.getPath("/traces");

            assertThrows(IllegalArgumentException.class,
                    () -> new TraceRecorder(root, Clock.systemUTC()));
        }
    }

    @Test void samePathStagingReplacementIsRejectedWithoutDeletingThroughIt()
            throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-replace", TraceRecorder.Limits.defaults());
        Path staging;
        try (var entries = Files.list(temporaryDirectory)) {
            staging = entries.filter(path -> Files.isDirectory(path,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .findFirst()
                    .orElseThrow();
        }
        Path replaced = temporaryDirectory.resolve("renamed-staging");
        Files.move(staging, replaced);
        Files.createDirectory(staging); // same pathname, different filesystem object

        try {
            HarnessException failure = assertThrows(HarnessException.class,
                    () -> recorder.record(TraceEvent.commandStarted(
                            "session-replace", "request-1", 1, snapshot(1, 1), Map.of())));
            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertTrue(Files.isDirectory(staging,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    "replacement directory must never be deleted through");
            assertTrue(Files.isDirectory(replaced),
                    "renamed-away staging must not be deleted through either");
        } finally {
            try (var walk = Files.walk(replaced)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(staging);
        }
    }

    @Test void temporaryArchiveReplacedWithHardlinkBeforePublicationIsRejected()
            throws Exception {
        Path victim = temporaryDirectory.resolveSibling(
                temporaryDirectory.getFileName() + "-archive-victim");
        Files.writeString(victim, "attacker-chosen-archive-content", StandardCharsets.UTF_8);
        try {
            TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                    (step, path) -> {
                        if (step == TraceRecorder.FinalizationInterceptor.Step.VERIFY_ARCHIVE
                                && path.getFileName().toString().endsWith(".zip.tmp")) {
                            Files.deleteIfExists(path);
                            Files.createLink(path, victim);
                        }
                    });
            recorder.start("session-archive-swap", TraceRecorder.Limits.defaults());
            recorder.record(TraceEvent.commandStarted(
                    "session-archive-swap", "request-1", 1, snapshot(1, 1), Map.of()));

            HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals("attacker-chosen-archive-content",
                    Files.readString(victim, StandardCharsets.UTF_8),
                    "victim file must be untouched");
            try (var paths = Files.walk(temporaryDirectory)) {
                Path leftover = paths
                        .filter(path -> path.getFileName().toString().endsWith(".zip.tmp"))
                        .findFirst().orElseThrow();
                assertEquals("attacker-chosen-archive-content",
                        Files.readString(leftover, StandardCharsets.UTF_8),
                        "the substituted temporary archive must be left untouched");
            }
            assertNull(publishedArchives(temporaryDirectory),
                    "no archive may be published from a substituted temporary archive");
        } finally {
            Files.deleteIfExists(victim);
        }
    }

    @Test void publishedArchiveReplacedBeforeFinalVerificationIsRejected() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.VERIFY_ARCHIVE
                            && path.getFileName().toString().endsWith(".zip")
                            && !path.getFileName().toString().endsWith(".zip.tmp")) {
                        Files.deleteIfExists(path);
                        Files.writeString(path, "tampered-archive", StandardCharsets.UTF_8);
                    }
                });
        recorder.start("session-post-move", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-post-move", "request-1", 1, snapshot(1, 1), Map.of()));

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(noTemporaryFiles(temporaryDirectory));
        try (var paths = Files.walk(temporaryDirectory)) {
            Path leftover = paths
                    .filter(path -> path.getFileName().toString().endsWith(".zip")
                            && !path.getFileName().toString().endsWith(".zip.tmp"))
                    .findFirst().orElseThrow();
            assertEquals("tampered-archive",
                    Files.readString(leftover, StandardCharsets.UTF_8),
                    "the replaced archive must be left untouched, not deleted");
        }
    }

    @Test void destinationCollisionIsRejected() throws Exception {
        Path[] collision = new Path[1];
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.CHECK_DESTINATION) {
                        collision[0] = path;
                        Files.writeString(path, "occupied", StandardCharsets.UTF_8);
                    }
                });
        recorder.start("session-collision", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-collision", "request-1", 1, snapshot(1, 1), Map.of()));

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("destination"));
        assertEquals("occupied", Files.readString(collision[0], StandardCharsets.UTF_8),
                "an occupied destination must never be overwritten");
        assertTrue(noTemporaryFiles(temporaryDirectory));
        assertTrue(java.util.Arrays.stream(failure.getSuppressed())
                        .map(Throwable::getMessage)
                        .anyMatch(message -> message.contains("identity unknown")),
                "an occupied destination without a recorded identity must be reported as residual");
    }

    @Test void afterFinalCheckReplacementIsRejectedByConsumer() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.AFTER_FINALIZE) {
                        Files.writeString(path, "tampered-after-finalize",
                                StandardCharsets.UTF_8);
                    }
                });
        recorder.start("session-consumer", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-consumer", "request-1", 1, snapshot(1, 1), Map.of()));

        TraceManifest manifest = recorder.stop(); // publication proof already completed

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer().load(manifest.archive()));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
    }

    @Test void substitutedChildIsLeftUntouchedDuringCleanup() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.AFTER_FINALIZE
                            && path.getFileName().toString().endsWith(".zip")) {
                        Path events = temporaryDirectory.resolve("substituted-events.ndjson");
                        try (var paths = Files.walk(temporaryDirectory)) {
                            events = paths
                                    .filter(p -> p.getFileName().toString()
                                            .equals("events.ndjson"))
                                    .findFirst().orElseThrow();
                        }
                        Files.delete(events);
                        Files.writeString(events, "substituted", StandardCharsets.UTF_8);
                    }
                });
        recorder.start("session-substitute", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-substitute", "request-1", 1, snapshot(1, 1), Map.of()));

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
        assertTrue(failure.getSuppressed().length > 0,
                "cleanup identity-mismatch failures must be retained");
        try (var paths = Files.walk(temporaryDirectory)) {
            Path substituted = paths
                    .filter(p -> p.getFileName().toString().equals("events.ndjson"))
                    .findFirst().orElseThrow();
            assertEquals("substituted",
                    Files.readString(substituted, StandardCharsets.UTF_8),
                    "the substituted child must remain untouched");
        }
    }

    @Test void cleanupFailureAfterSuccessfulFinalizeIsTerminal() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "posix permissions unavailable");
        }
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-cleanup-fail", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-cleanup-fail", "request-1", 1, snapshot(1, 1), Map.of()));
        recorder.addArtifact("image/png",
                new ByteArrayInputStream("png".getBytes(StandardCharsets.UTF_8)));
        Path artifactDirectory = artifactDirectory(temporaryDirectory);
        Files.setPosixFilePermissions(artifactDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

            assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
            assertTrue(failure.getSuppressed().length > 0,
                    "cleanup failures must be attached to the terminal failure");
            assertTrue(recorder.lastManifest().isPresent(),
                    "the archive is published before the terminal cleanup failure");
        } finally {
            Files.setPosixFilePermissions(artifactDirectory, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test void cleanupFailureIsSuppressedOntoPrimaryFinalizationFailure() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "posix permissions unavailable");
        }
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.OPEN_EVIDENCE
                            && path.getFileName().toString().equals("events.ndjson")) {
                        Files.writeString(path, "tampered-event", StandardCharsets.UTF_8);
                    }
                });
        recorder.start("session-primary", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-primary", "request-1", 1, snapshot(1, 1), Map.of()));
        recorder.addArtifact("image/png",
                new ByteArrayInputStream("png".getBytes(StandardCharsets.UTF_8)));
        Path artifactDirectory = artifactDirectory(temporaryDirectory);
        Files.setPosixFilePermissions(artifactDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertTrue(failure.getSuppressed().length > 0,
                    "cleanup failures must be suppressed onto the primary failure");
        } finally {
            Files.setPosixFilePermissions(artifactDirectory, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test void preExistingRootNotExactOwnerOnlyIsRejected() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "posix permissions unavailable");
        }
        Path root = temporaryDirectory.resolve("loose-root");
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE)); // 0755: owner-only write is not exact 0700

        assertThrows(IllegalArgumentException.class,
                () -> new TraceRecorder(root, Clock.systemUTC()));
    }

    @Test void aclOwnerOnlyEnforcedWhereAclIsTheSecureView() throws Exception {
        java.util.Set<String> views = FileSystems.getDefault().supportedFileAttributeViews();
        if (views.contains("posix") || !views.contains("acl")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "acl-only provider required");
        }
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-acl", TraceRecorder.Limits.defaults());
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.toList()) {
                java.nio.file.attribute.AclFileAttributeView view = Files.getFileAttributeView(
                        path, java.nio.file.attribute.AclFileAttributeView.class);
                if (view == null) {
                    continue;
                }
                java.nio.file.attribute.UserPrincipal owner = view.getOwner();
                for (java.nio.file.attribute.AclEntry entry : view.getAcl()) {
                    assertTrue(
                            entry.type() == java.nio.file.attribute.AclEntryType.ALLOW
                                    && entry.principal().equals(owner),
                            "ACL must grant only the owner: " + path);
                }
            }
        }
    }

    @Test void recorderManifestCarriesVerifiedArchiveIdentity() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-receipt", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-receipt", "request-1", 1, snapshot(1, 1), Map.of()));

        TraceManifest manifest = recorder.stop();

        assertEquals(sha256(Files.readAllBytes(manifest.archive())),
                manifest.archiveSha256());
        assertEquals(Files.size(manifest.archive()), manifest.archiveSize());
    }

    @Test void mutableUserNameDoesNotAffectEffectivePrincipal() throws Exception {
        String original = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "attacker-chosen-name");
            TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
            recorder.start("session-user", TraceRecorder.Limits.defaults());
            recorder.record(TraceEvent.commandStarted(
                    "session-user", "request-1", 1, snapshot(1, 1), Map.of()));

            TraceManifest manifest = recorder.stop();

            assertTrue(manifest.complete());
        } finally {
            System.setProperty("user.name", original);
        }
    }

    @Test void finalPathReplacedWithDifferentValidTraceIsRejectedByReceiptConsumer()
            throws Exception {
        TraceRecorder original = new TraceRecorder(
                temporaryDirectory.resolve("original"), Clock.systemUTC());
        original.start("session-original", TraceRecorder.Limits.defaults());
        original.record(TraceEvent.commandStarted(
                "session-original", "request-1", 1, snapshot(1, 1), Map.of()));
        TraceManifest receipt = original.stop();

        TraceRecorder other = new TraceRecorder(
                temporaryDirectory.resolve("other"), Clock.systemUTC());
        other.start("session-other", TraceRecorder.Limits.defaults());
        other.record(TraceEvent.commandStarted(
                "session-other", "request-2", 2, snapshot(2, 2), Map.of()));
        Path otherArchive = other.stop().archive();

        Files.delete(receipt.archive());
        Files.copy(otherArchive, receipt.archive()); // a different VALID trace at the receipt path

        assertDoesNotThrow(() -> new TraceReplayer().load(receipt.archive()));
        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer().load(receipt.archive(),
                        receipt.archiveSha256(), receipt.archiveSize()));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("receipt"));
    }

    @Test void twoIndependentCleanupFailuresAreBothRetained() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.AFTER_FINALIZE
                            && path.getFileName().toString().endsWith(".zip")
                            && !path.getFileName().toString().endsWith(".zip.tmp")) {
                        try (var paths = Files.walk(temporaryDirectory)) {
                            for (Path child : paths.toList()) {
                                if (child.getFileName().toString().equals("events.ndjson")) {
                                    Files.delete(child);
                                    Files.writeString(child, "substituted-events",
                                            StandardCharsets.UTF_8);
                                } else if (child.getParent() != null
                                        && child.getParent().getFileName().toString()
                                                .equals("artifacts")
                                        && Files.isRegularFile(child,
                                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                    Files.delete(child);
                                    Files.writeString(child, "substituted-artifact",
                                            StandardCharsets.UTF_8);
                                }
                            }
                        }
                    }
                });
        recorder.start("session-two-fail", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-two-fail", "request-1", 1, snapshot(1, 1), Map.of()));
        recorder.addArtifact("image/png",
                new ByteArrayInputStream("png".getBytes(StandardCharsets.UTF_8)));

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
        List<String> messages = java.util.Arrays.stream(failure.getSuppressed())
                .map(Throwable::getMessage).toList();
        assertTrue(messages.size() >= 2,
                "both independent cleanup failures must be retained: " + messages);
        assertTrue(messages.stream().anyMatch(message -> message.contains("events.ndjson")),
                "events cleanup failure must be retained: " + messages);
        assertTrue(messages.stream().anyMatch(message -> !message.contains("events.ndjson")),
                "artifact cleanup failure must be retained: " + messages);
    }

    @Test void substitutedArtifactDirectorySymlinkIsLeftWithResidualFailure()
            throws Exception {
        Path outside = temporaryDirectory.resolveSibling(
                temporaryDirectory.getFileName() + "-dir-secret");
        Files.writeString(outside, "dir-secret", StandardCharsets.UTF_8);
        try {
            TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
            recorder.start("session-dir-swap", TraceRecorder.Limits.defaults());
            recorder.record(TraceEvent.commandStarted(
                    "session-dir-swap", "request-1", 1, snapshot(1, 1), Map.of()));
            Path artifactDirectory = artifactDirectory(temporaryDirectory);
            Path moved = temporaryDirectory.resolve("moved-artifacts");
            Files.move(artifactDirectory, moved);
            Files.createSymbolicLink(artifactDirectory, outside);

            HarnessException failure = assertThrows(HarnessException.class, recorder::close);

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertTrue(Files.isSymbolicLink(artifactDirectory),
                    "the substituted artifact directory symlink must never be unlinked");
            assertEquals("dir-secret", Files.readString(outside, StandardCharsets.UTF_8));
            assertTrue(java.util.Arrays.stream(failure.getSuppressed())
                            .map(Throwable::getMessage)
                            .anyMatch(message -> message.contains("symbolic link")),
                    "residual risk must be reported for the substituted symlink");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test void cleanupWithUnknownEntryIdentityLeavesEntryAndReportsResidual()
            throws Exception {
        // White-box contract guard: an existing entry without a recorded identity
        // must be left untouched and reported as residual, never silently skipped.
        Path directory = Files.createTempDirectory("identity");
        TraceRecorder recorder = new TraceRecorder(directory, Clock.systemUTC());
        Path entry = directory.resolve("events.ndjson");
        Files.writeString(entry, "content", StandardCharsets.UTF_8);
        java.util.List<Throwable> failures = new java.util.ArrayList<>();
        try (java.nio.file.SecureDirectoryStream<Path> stream =
                (java.nio.file.SecureDirectoryStream<Path>) Files.newDirectoryStream(directory)) {
            java.lang.reflect.Method method = TraceRecorder.class.getDeclaredMethod(
                    "deleteChildChecked", java.nio.file.SecureDirectoryStream.class,
                    String.class, Object.class, TraceRecorder.ContentEvidence.class,
                    boolean.class, java.util.List.class);
            method.setAccessible(true);
            method.invoke(recorder, stream, "events.ndjson", null, null, true, failures);
        }
        assertEquals("content", Files.readString(entry, StandardCharsets.UTF_8),
                "an entry with unknown identity must be left untouched");
        assertFalse(failures.isEmpty(), "a residual cleanup failure must be reported");
        assertTrue(failures.get(0).getMessage().contains("identity unknown"));
    }

    @Test void equalFileKeyContentReplacementIsNeverDeletedOrPublished() throws Exception {
        // A delete/recreate collision where the replacement reuses the recorded
        // fileKey is simulated by reporting the recorded key; cleanup and the
        // archive copy must still reject the changed content deterministically.
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> { }, simulatedFileKeyReuse());
        recorder.start("session-reuse", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-reuse", "request-1", 1, snapshot(1, 1), Map.of()));
        recorder.addArtifact("image/png",
                new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)));
        Path artifact = artifactFile(temporaryDirectory);
        Files.writeString(artifact, "replacement", StandardCharsets.UTF_8);

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertEquals("replacement", Files.readString(artifact, StandardCharsets.UTF_8),
                "a same-key content replacement must never be deleted during cleanup");
        assertNull(publishedArchives(temporaryDirectory),
                "no archive may be published with substituted artifact content");
        List<String> suppressed = java.util.Arrays.stream(failure.getSuppressed())
                .map(Throwable::getMessage).toList();
        assertTrue(suppressed.stream().anyMatch(message -> message.contains("content")),
                "the refused cleanup must be reported: " + suppressed);
    }

    @Test void equalFileKeySymlinkReplacementIsLeftUntouchedDuringCleanup()
            throws Exception {
        Path outside = temporaryDirectory.resolveSibling(
                temporaryDirectory.getFileName() + "-reuse-secret");
        Files.writeString(outside, "reuse-secret", StandardCharsets.UTF_8);
        try {
            TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                    (step, path) -> { }, simulatedFileKeyReuse());
            recorder.start("session-reuse-symlink", TraceRecorder.Limits.defaults());
            recorder.record(TraceEvent.commandStarted(
                    "session-reuse-symlink", "request-1", 1, snapshot(1, 1), Map.of()));
            recorder.addArtifact("image/png",
                    new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)));
            Path artifact = artifactFile(temporaryDirectory);
            Files.delete(artifact);
            Files.createSymbolicLink(artifact, outside);

            HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertTrue(Files.isSymbolicLink(artifact),
                    "a same-key symlink replacement must never be deleted during cleanup");
            assertEquals("reuse-secret", Files.readString(outside, StandardCharsets.UTF_8));
            assertNull(publishedArchives(temporaryDirectory),
                    "no archive may be published from a substituted artifact");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test void equalFileKeyArchiveReplacementAfterPublishIsLeftUntouched()
            throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.VERIFY_ARCHIVE
                            && path.getFileName().toString().endsWith(".zip")
                            && !path.getFileName().toString().endsWith(".zip.tmp")) {
                        Files.deleteIfExists(path);
                        Files.writeString(path, "tampered-archive", StandardCharsets.UTF_8);
                    }
                }, simulatedFileKeyReuse());
        recorder.start("session-reuse-archive", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-reuse-archive", "request-1", 1, snapshot(1, 1), Map.of()));

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(noTemporaryFiles(temporaryDirectory));
        try (var paths = Files.walk(temporaryDirectory)) {
            Path leftover = paths
                    .filter(path -> path.getFileName().toString().endsWith(".zip")
                            && !path.getFileName().toString().endsWith(".zip.tmp"))
                    .findFirst().orElseThrow();
            assertEquals("tampered-archive",
                    Files.readString(leftover, StandardCharsets.UTF_8),
                    "the same-key replaced archive must be left untouched, not deleted");
        }
    }

    @Test void equalFileKeyEventsReplacementIsLeftUntouchedDuringCleanup()
            throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC(),
                (step, path) -> {
                    if (step == TraceRecorder.FinalizationInterceptor.Step.AFTER_FINALIZE
                            && path.getFileName().toString().endsWith(".zip")) {
                        Path events;
                        try (var paths = Files.walk(temporaryDirectory)) {
                            events = paths
                                    .filter(candidate -> candidate.getFileName().toString()
                                            .equals("events.ndjson"))
                                    .findFirst().orElseThrow();
                        }
                        Files.delete(events);
                        Files.writeString(events, "substituted", StandardCharsets.UTF_8);
                    }
                }, simulatedFileKeyReuse());
        recorder.start("session-reuse-events", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-reuse-events", "request-1", 1, snapshot(1, 1), Map.of()));

        HarnessException failure = assertThrows(HarnessException.class, recorder::stop);

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
        assertTrue(failure.getSuppressed().length > 0,
                "cleanup identity-mismatch failures must be retained");
        try (var paths = Files.walk(temporaryDirectory)) {
            Path substituted = paths
                    .filter(candidate -> candidate.getFileName().toString()
                            .equals("events.ndjson"))
                    .findFirst().orElseThrow();
            assertEquals("substituted",
                    Files.readString(substituted, StandardCharsets.UTF_8),
                    "the same-key substituted events file must be left untouched");
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String readZipEntry(Path archive, String name) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            java.util.zip.ZipEntry entry = Optional.ofNullable(zip.getEntry(name)).orElseThrow();
            try (var input = zip.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static Path artifactDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().endsWith(".tmp"))
                    .filter(path -> path.getFileName().toString().equals("artifacts"))
                    .findFirst().orElseThrow();
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

    private static Path publishedArchives(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .findFirst().orElse(null);
        }
    }

    private static Path artifactFile(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals("artifacts"))
                    .filter(path -> Files.isRegularFile(path,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .findFirst().orElseThrow();
        }
    }

    /**
     * Attribute seam that reports the recorded expected fileKey for every child,
     * deterministically simulating a delete/recreate collision where the
     * filesystem reuses the freed inode. Content, type, and size stay real, so
     * only the key-based part of the identity is spoofed.
     */
    private static TraceRecorder.ChildAttributeReader simulatedFileKeyReuse() {
        return (parent, name, expectedKey) -> {
            BasicFileAttributes real = parent.getFileAttributeView(Path.of(name),
                    BasicFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
            if (real.fileKey() != null && expectedKey != null) {
                return new ReuseSimulatedAttributes(real, expectedKey);
            }
            return real;
        };
    }

    private static final class ReuseSimulatedAttributes implements BasicFileAttributes {
        private final BasicFileAttributes delegate;
        private final Object simulatedKey;

        private ReuseSimulatedAttributes(BasicFileAttributes delegate, Object simulatedKey) {
            this.delegate = delegate;
            this.simulatedKey = simulatedKey;
        }

        @Override public Object fileKey() {
            return simulatedKey;
        }

        @Override public long size() {
            return delegate.size();
        }

        @Override public boolean isDirectory() {
            return delegate.isDirectory();
        }

        @Override public boolean isRegularFile() {
            return delegate.isRegularFile();
        }

        @Override public boolean isSymbolicLink() {
            return delegate.isSymbolicLink();
        }

        @Override public boolean isOther() {
            return delegate.isOther();
        }

        @Override public FileTime lastModifiedTime() {
            return delegate.lastModifiedTime();
        }

        @Override public FileTime lastAccessTime() {
            return delegate.lastAccessTime();
        }

        @Override public FileTime creationTime() {
            return delegate.creationTime();
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
