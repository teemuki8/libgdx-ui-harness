package dev.gdx.uiharness.protocol;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;

/** Session-scoped bounded storage for protocol evidence artifacts. */
public interface ArtifactStore extends AutoCloseable {
    /** Streams one artifact into the store. The source is always closed by this call. */
    ArtifactId put(
            String sessionId,
            ArtifactMediaType mediaType,
            InputStream source,
            Instant expiresAt);

    /** Convenience overload for already materialized caller bytes. */
    default ArtifactId put(
            String sessionId,
            ArtifactMediaType mediaType,
            byte[] bytes,
            Instant expiresAt) {
        Objects.requireNonNull(bytes, "bytes");
        return put(sessionId, mediaType, new ByteArrayInputStream(bytes), expiresAt);
    }

    /** Opens a tracked stream for one unexpired artifact owned by the supplied session. */
    InputStream read(String sessionId, ArtifactId artifactId);

    /** Returns bounded metadata for one unexpired session-owned artifact. */
    Metadata metadata(String sessionId, ArtifactId artifactId);

    /** Removes every currently expired entry and returns the number of removed IDs. */
    int cleanupExpired();

    /** Closes active readers and removes all artifacts belonging to one session. */
    void disposeSession(String sessionId);

    /** Closes all readers and removes every store-owned session directory. */
    @Override void close();

    /** Per-session hard limits. Deduplicated bytes count once; each distinct entry counts once. */
    record Limits(long maxBytes, int maxArtifacts) {
        /** Validates positive limits. */
        public Limits {
            if (maxBytes <= 0 || maxArtifacts <= 0) {
                throw new IllegalArgumentException("artifact limits must be positive");
            }
        }
    }

    /** Immutable artifact metadata safe to expose to protocol adapters. */
    record Metadata(
            ArtifactMediaType mediaType,
            long size,
            String sha256,
            Instant expiresAt) {
        /** Validates metadata returned by an implementation. */
        public Metadata {
            mediaType = Objects.requireNonNull(mediaType, "mediaType");
            if (size < 0) {
                throw new IllegalArgumentException("size must be non-negative");
            }
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be canonical lowercase hex");
            }
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
