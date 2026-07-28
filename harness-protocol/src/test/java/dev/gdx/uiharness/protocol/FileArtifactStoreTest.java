package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileArtifactStoreTest {
    @TempDir Path temporaryDirectory;

    @Test void expiredArtifactCannotEscapeItsSession() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        try (FileArtifactStore store = store(temporaryDirectory, 1_000, 10, clock)) {
            byte[] bytes = "png bytes".getBytes(StandardCharsets.UTF_8);
            ArtifactId id = store.put("session-a", ArtifactMediaType.PNG, bytes,
                    clock.instant().plusSeconds(5));

            HarnessException otherSession = assertThrows(HarnessException.class,
                    () -> store.read("session-b", id));
            assertEquals(ErrorCode.NOT_FOUND, otherSession.code());
            clock.advance(Duration.ofSeconds(5));
            HarnessException expired = assertThrows(HarnessException.class,
                    () -> store.read("session-a", id));
            assertEquals(ErrorCode.NOT_FOUND, expired.code());
            store.put("session-a", ArtifactMediaType.PNG, bytes,
                    clock.instant().plusSeconds(1));
            clock.advance(Duration.ofSeconds(1));
            assertEquals(1, store.cleanupExpired());
            assertFalse(Files.exists(findArtifactFile(temporaryDirectory),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test void streamsMediaMetadataAndPublishesOpaqueRandomIdsAtomically() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        try (FileArtifactStore store = store(temporaryDirectory, 1_000, 10, clock)) {
            byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
            ArtifactId first = store.put("../../caller-session", ArtifactMediaType.JSON,
                    new ByteArrayInputStream(bytes), clock.instant().plusSeconds(30));
            ArtifactId second = store.put("../../caller-session", ArtifactMediaType.PNG,
                    new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)),
                    clock.instant().plusSeconds(30));

            assertNotEquals(first, second);
            assertTrue(first.value().matches("[0-9a-f]{32}"));
            assertFalse(first.value().contains("caller"));
            assertArrayEquals(bytes, readAll(store.read("../../caller-session", first)));
            ArtifactStore.Metadata metadata = store.metadata("../../caller-session", first);
            assertEquals(ArtifactMediaType.JSON, metadata.mediaType());
            assertEquals(bytes.length, metadata.size());
            assertTrue(metadata.sha256().matches("[0-9a-f]{64}"));
            assertFalse(hasTemporaryFiles(temporaryDirectory));
        }
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test void exactQuotaAllowsBoundaryDeduplicatesHashAndLeavesNoFailedTemp() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        byte[] exact = "12345678".getBytes(StandardCharsets.UTF_8);
        Instant expiry = clock.instant().plusSeconds(30);
        try (FileArtifactStore store = store(temporaryDirectory, exact.length, 1, clock)) {
            ArtifactId first = store.put("session-a", ArtifactMediaType.OCTET_STREAM,
                    exact, expiry);
            ArtifactId duplicate = store.put("session-a", ArtifactMediaType.OCTET_STREAM,
                    new ByteArrayInputStream(exact), expiry);

            assertEquals(first, duplicate);
            java.util.concurrent.atomic.AtomicBoolean failedSourceClosed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            ByteArrayInputStream failedSource = new ByteArrayInputStream(new byte[] {9}) {
                @Override public void close() throws IOException {
                    failedSourceClosed.set(true);
                    super.close();
                }
            };
            HarnessException countOrBytes = assertThrows(HarnessException.class,
                    () -> store.put("session-a", ArtifactMediaType.OCTET_STREAM,
                            failedSource, expiry));
            assertEquals(ErrorCode.LIMIT_EXCEEDED, countOrBytes.code());
            assertTrue(failedSourceClosed.get());
            assertFalse(hasTemporaryFiles(temporaryDirectory));
            assertArrayEquals(exact, readAll(store.read("session-a", first)));
        }
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test void rejectsTraversalIdsAndSymlinkEscapesWithoutTouchingTarget() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactId("../outside"));
        Path outside = temporaryDirectory.resolveSibling("artifact-outside.txt");
        Files.writeString(outside, "do not touch", StandardCharsets.UTF_8);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        FileArtifactStore store = store(temporaryDirectory, 1_000, 10, clock);
        ArtifactId id = store.put("session-a", ArtifactMediaType.JSON,
                "inside".getBytes(StandardCharsets.UTF_8), clock.instant().plusSeconds(30));
        Path artifact = findArtifactFile(temporaryDirectory);
        Files.delete(artifact);
        Files.createSymbolicLink(artifact, outside);

        HarnessException failure = assertThrows(HarnessException.class,
                () -> store.read("session-a", id));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        store.disposeSession("session-a");
        assertEquals("do not touch", Files.readString(outside, StandardCharsets.UTF_8));
        store.close();
        assertDirectoryEmpty(temporaryDirectory);
        Files.delete(outside);
    }

    @Test void rejectsSymlinkConfiguredRoot() throws Exception {
        Path actual = temporaryDirectory.resolve("actual");
        Path link = temporaryDirectory.resolve("link");
        Files.createDirectory(actual);
        Files.createSymbolicLink(link, actual);

        assertThrows(IllegalArgumentException.class,
                () -> store(link, 1_000, 10, Clock.systemUTC()));
    }

    @Test void disposalClosesConcurrentReadersAndCleansOnlyOwnedDirectory() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        FileArtifactStore store = store(temporaryDirectory, 1_000, 10, clock);
        ArtifactId id = store.put("session-a", ArtifactMediaType.OCTET_STREAM,
                "payload".getBytes(StandardCharsets.UTF_8), clock.instant().plusSeconds(30));
        InputStream openRead = store.read("session-a", id);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cleanup = executor.submit(() -> {
                start.await();
                store.disposeSession("session-a");
                return null;
            });
            var secondCleanup = executor.submit(() -> {
                start.await();
                store.disposeSession("session-a");
                return null;
            });
            start.countDown();
            cleanup.get(5, TimeUnit.SECONDS);
            secondCleanup.get(5, TimeUnit.SECONDS);
        }

        assertThrows(IOException.class, openRead::read);
        assertThrows(HarnessException.class, () -> store.read("session-a", id));
        assertDirectoryEmpty(temporaryDirectory);
        store.close();
        store.close();
    }

    @Test void failedDisposalRetainsOwnedStateForSafeRetry() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        FileArtifactStore store = store(temporaryDirectory, 1_000, 10, clock);
        store.put("session-retry", ArtifactMediaType.OCTET_STREAM,
                "payload".getBytes(StandardCharsets.UTF_8), clock.instant().plusSeconds(30));
        Path sessionDirectory = findArtifactFile(temporaryDirectory).getParent();
        Path unknown = sessionDirectory.resolve("unknown-owned-file");
        Files.writeString(unknown, "block directory removal", StandardCharsets.UTF_8);

        assertThrows(HarnessException.class, () -> store.disposeSession("session-retry"));
        Files.delete(unknown);
        store.disposeSession("session-retry");

        assertDirectoryEmpty(temporaryDirectory);
        store.close();
    }

    @Test void blockingArtifactProducerCannotPreventConcurrentSessionDisposal()
            throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        FileArtifactStore store = store(temporaryDirectory, 1_000, 10, clock);
        BlockingInputStream source = new BlockingInputStream();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var put = executor.submit(() -> store.put("session-blocked",
                    ArtifactMediaType.OCTET_STREAM, source, clock.instant().plusSeconds(30)));
            assertTrue(source.entered.await(1, TimeUnit.SECONDS));
            var dispose = executor.submit(() -> store.disposeSession("session-blocked"));
            try {
                dispose.get(1, TimeUnit.SECONDS);
            } finally {
                source.release.countDown();
            }
            java.util.concurrent.ExecutionException failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> put.get(1, TimeUnit.SECONDS));
            HarnessException typed = (HarnessException) failure.getCause();
            assertEquals(ErrorCode.SESSION_CLOSED, typed.code());
        }
        assertTrue(source.closed);
        assertDirectoryEmpty(temporaryDirectory);
        store.close();
    }

    @Test void failedExpiryDeleteRetainsEntryAndQuotaForRetry() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        FileArtifactStore store = store(temporaryDirectory, payload.length, 1, clock);
        store.put("session-delete", ArtifactMediaType.OCTET_STREAM, payload,
                clock.instant().plusSeconds(1));
        Path blob = findArtifactFile(temporaryDirectory);
        Files.delete(blob);
        Files.createDirectory(blob);
        Path blocker = blob.resolve("blocker");
        Files.writeString(blocker, "block", StandardCharsets.UTF_8);
        clock.advance(Duration.ofSeconds(1));

        HarnessException deleteFailure = assertThrows(HarnessException.class,
                store::cleanupExpired);
        assertEquals(ErrorCode.INTERNAL_ERROR, deleteFailure.code());
        HarnessException quotaFailure = assertThrows(HarnessException.class,
                () -> store.put("session-delete", ArtifactMediaType.OCTET_STREAM,
                        payload, clock.instant().plusSeconds(30)));
        assertEquals(ErrorCode.INTERNAL_ERROR, quotaFailure.code());

        Files.delete(blocker);
        Files.delete(blob);
        assertEquals(1, store.cleanupExpired());
        store.put("session-delete", ArtifactMediaType.OCTET_STREAM, payload,
                clock.instant().plusSeconds(30));
        store.close();
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test void mediaTypesRejectUnregisteredCallerStrings() {
        assertEquals("image/png", ArtifactMediaType.PNG.value());
        assertEquals(ArtifactMediaType.NDJSON,
                ArtifactMediaType.fromValue("application/x-ndjson"));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactMediaType.fromValue("text/html"));
    }

    private static FileArtifactStore store(
            Path root, long bytes, int count, Clock clock) {
        return new FileArtifactStore(root, new ArtifactStore.Limits(bytes, count), clock);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (input) {
            return input.readAllBytes();
        }
    }

    private static Path findArtifactFile(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .findFirst()
                    .orElse(root.resolve("missing"));
        }
    }

    private static boolean hasTemporaryFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.getFileName().toString().contains(".tmp"));
        }
    }

    private static void assertDirectoryEmpty(Path root) throws Exception {
        try (var entries = Files.list(root)) {
            assertTrue(entries.findAny().isEmpty(), () -> "not empty: " + root);
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean closed;

        @Override public int read() throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", exception);
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
