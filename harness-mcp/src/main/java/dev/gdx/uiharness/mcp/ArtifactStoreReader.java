package dev.gdx.uiharness.mcp;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.protocol.ArtifactId;
import dev.gdx.uiharness.protocol.ArtifactStore;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bounded opaque-receipt reader backed by an application-owned {@link ArtifactStore}.
 * Every call verifies the complete immutable payload and closes its tracked store stream.
 */
public final class ArtifactStoreReader implements ArtifactReference.Reader {
    private static final String PREFIX = "artifact:";
    private static final int VERIFY_BUFFER_BYTES = 16 * 1_024;

    private final ArtifactStore store;

    /** Uses the same session ownership, expiry, and quotas as the supplied store. */
    public ArtifactStoreReader(ArtifactStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override public ArtifactReference.Chunk read(
            String sessionId, String reference, long offset, int maxBytes) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(reference, "reference");
        if (maxBytes < 1 || maxBytes > ArtifactReference.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("maxBytes is outside the artifact chunk bound");
        }
        ArtifactId id = artifactId(reference);
        ArtifactStore.Metadata metadata = metadata(sessionId, id);
        if (offset < 0 || offset > metadata.size()) {
            throw new ArtifactReference.InvalidArtifactOffsetException();
        }

        int chunkLength = Math.toIntExact(Math.min((long) maxBytes, metadata.size() - offset));
        byte[] chunk = new byte[chunkLength];
        MessageDigest digest = sha256Digest();
        long position = 0;
        int copied = 0;
        byte[] buffer = new byte[VERIFY_BUFFER_BYTES];
        try (InputStream input = store.read(sessionId, id)) {
            for (int count; (count = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, count);
                long overlapStart = Math.max(position, offset);
                long overlapEnd = Math.min(position + count, offset + chunkLength);
                if (overlapStart < overlapEnd) {
                    int sourceOffset = Math.toIntExact(overlapStart - position);
                    int length = Math.toIntExact(overlapEnd - overlapStart);
                    System.arraycopy(buffer, sourceOffset, chunk, copied, length);
                    copied += length;
                }
                position += count;
            }
        } catch (HarnessException failure) {
            throw normalizeStoreFailure(failure);
        } catch (IOException failure) {
            throw new ArtifactReference.ArtifactReadUnavailableException(
                    "Artifact store read failed", failure);
        } catch (RuntimeException failure) {
            throw new ArtifactReference.ArtifactReadUnavailableException(
                    "Artifact store read failed", failure);
        }
        String observedSha256 = HexFormat.of().formatHex(digest.digest());
        if (position != metadata.size() || copied != chunk.length
                || !observedSha256.equals(metadata.sha256())) {
            throw new ArtifactReference.ArtifactIntegrityException();
        }
        ArtifactReference artifact = new ArtifactReference(
                reference, metadata.mediaType().value(), metadata.size(), metadata.sha256());
        long nextOffset = offset + chunk.length;
        return new ArtifactReference.Chunk(
                artifact, offset, nextOffset, nextOffset == metadata.size(), chunk);
    }

    private ArtifactStore.Metadata metadata(String sessionId, ArtifactId id) {
        try {
            return store.metadata(sessionId, id);
        } catch (HarnessException failure) {
            throw normalizeStoreFailure(failure);
        } catch (RuntimeException failure) {
            throw new ArtifactReference.ArtifactReadUnavailableException(
                    "Artifact store is unavailable", failure);
        }
    }

    private static RuntimeException normalizeStoreFailure(HarnessException failure) {
        if (failure.code() == ErrorCode.NOT_FOUND
                || failure.code() == ErrorCode.SESSION_NOT_FOUND
                || failure.code() == ErrorCode.SESSION_CLOSED) {
            return new ArtifactReference.ArtifactNotFoundException();
        }
        return new ArtifactReference.ArtifactReadUnavailableException(
                "Artifact store is unavailable", failure);
    }

    private static ArtifactId artifactId(String reference) {
        if (!reference.startsWith(PREFIX)) {
            throw new ArtifactReference.ArtifactNotFoundException();
        }
        try {
            return new ArtifactId(reference.substring(PREFIX.length()));
        } catch (IllegalArgumentException failure) {
            throw new ArtifactReference.ArtifactNotFoundException();
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }
}
