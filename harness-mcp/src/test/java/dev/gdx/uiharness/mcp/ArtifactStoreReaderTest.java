package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.protocol.ArtifactId;
import dev.gdx.uiharness.protocol.ArtifactMediaType;
import dev.gdx.uiharness.protocol.ArtifactStore;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ArtifactStoreReaderTest {
    private static final String SESSION = "game";
    private static final String REFERENCE = "artifact:" + "a".repeat(32);

    @Test void readsAndVerifiesBoundedRegionsWhileClosingEveryStoreStream() {
        byte[] payload = "verified store payload".getBytes(StandardCharsets.UTF_8);
        TrackingStore store = new TrackingStore(payload);
        ArtifactStoreReader reader = new ArtifactStoreReader(store);

        ArtifactReference.Chunk middle = reader.read(SESSION, REFERENCE, 4, 6);
        assertArrayEquals(java.util.Arrays.copyOfRange(payload, 4, 10), middle.content());
        assertEquals(10, middle.nextOffset());
        assertEquals(false, middle.eof());
        assertTrue(store.lastClosed.get());

        ArtifactReference.Chunk eof = reader.read(SESSION, REFERENCE, payload.length, 8);
        assertArrayEquals(new byte[0], eof.content());
        assertEquals(true, eof.eof());
        assertTrue(store.lastClosed.get());
    }

    @Test void closesStreamsOnReadErrorAndIntegrityFailure() {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        TrackingStore failing = new TrackingStore(payload);
        failing.failRead = true;
        assertThrows(ArtifactReference.ArtifactReadUnavailableException.class,
                () -> new ArtifactStoreReader(failing).read(SESSION, REFERENCE, 0, 4));
        assertTrue(failing.lastClosed.get());

        TrackingStore corrupt = new TrackingStore(payload);
        corrupt.metadataSha256 = "0".repeat(64);
        assertThrows(ArtifactReference.ArtifactIntegrityException.class,
                () -> new ArtifactStoreReader(corrupt).read(SESSION, REFERENCE, 0, 4));
        assertTrue(corrupt.lastClosed.get());
    }

    @Test void normalizesUnknownExpiredAndWrongSessionWithoutLeakingStoreEvidence() {
        TrackingStore store = new TrackingStore(new byte[] {1});
        store.notFound = true;
        ArtifactReference.ArtifactNotFoundException failure = assertThrows(
                ArtifactReference.ArtifactNotFoundException.class,
                () -> new ArtifactStoreReader(store).read("wrong", REFERENCE, 0, 1));
        assertEquals("Artifact is unavailable for this session", failure.getMessage());
        assertThrows(ArtifactReference.ArtifactNotFoundException.class,
                () -> new ArtifactStoreReader(store).read(SESSION, "artifact:malformed", 0, 1));
    }

    @Test void normalizesClosedStoreLifecycle() {
        TrackingStore store = new TrackingStore(new byte[] {1});
        store.closed = true;
        assertThrows(ArtifactReference.ArtifactReadUnavailableException.class,
                () -> new ArtifactStoreReader(store).read(SESSION, REFERENCE, 0, 1));
    }

    @Test void rejectsOffsetsPastTotalBeforeOpeningAStream() {
        TrackingStore store = new TrackingStore(new byte[] {1, 2});
        assertThrows(ArtifactReference.InvalidArtifactOffsetException.class,
                () -> new ArtifactStoreReader(store).read(SESSION, REFERENCE, 3, 1));
        assertEquals(0, store.readCalls);
    }

    private static final class TrackingStore implements ArtifactStore {
        private final byte[] payload;
        private String metadataSha256;
        private boolean failRead;
        private boolean notFound;
        private int readCalls;
        private AtomicBoolean lastClosed = new AtomicBoolean();
        private boolean closed;

        TrackingStore(byte[] payload) {
            this.payload = payload.clone();
            metadataSha256 = sha256(payload);
        }

        @Override public ArtifactId put(String sessionId, ArtifactMediaType mediaType,
                InputStream source, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override public InputStream read(String sessionId, ArtifactId artifactId) {
            readCalls++;
            lastClosed = new AtomicBoolean();
            InputStream delegate = failRead
                    ? new InputStream() {
                        private boolean first = true;
                        @Override public int read() throws IOException {
                            if (first) {
                                first = false;
                                return payload[0];
                            }
                            throw new IOException("/private/store/path");
                        }
                    }
                    : new ByteArrayInputStream(payload);
            return new FilterInputStream(delegate) {
                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        lastClosed.set(true);
                    }
                }
            };
        }

        @Override public Metadata metadata(String sessionId, ArtifactId artifactId) {
            if (closed) {
                throw new IllegalStateException("closed store at /private/root");
            }
            if (notFound) {
                throw new HarnessException(ErrorCode.NOT_FOUND, "secret receipt",
                        ErrorEvidence.empty());
            }
            return new Metadata(ArtifactMediaType.PNG, payload.length, metadataSha256,
                    Instant.parse("2030-01-01T00:00:00Z"));
        }

        @Override public int cleanupExpired() { return 0; }
        @Override public void disposeSession(String sessionId) {}
        @Override public void close() {}
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
