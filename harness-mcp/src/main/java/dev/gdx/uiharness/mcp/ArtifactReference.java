package dev.gdx.uiharness.mcp;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/** Opaque reference returned by an injected artifact publisher. */
public record ArtifactReference(
        String reference, String mediaType, long byteLength, String sha256) {
    /** Hard byte limit for one artifact-read response chunk. */
    public static final int MAX_CHUNK_BYTES = 65_536;

    /** Validates transport-safe artifact metadata without interpreting the reference as a path. */
    public ArtifactReference {
        requireOpaque(reference);
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(sha256, "sha256");
        if (mediaType.isBlank() || mediaType.length() > 256) {
            throw new IllegalArgumentException("mediaType must be between 1 and 256 characters");
        }
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be non-negative");
        }
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sha256 must be a 64-character hexadecimal digest");
        }
    }

    /** Validates an opaque transport reference, including references produced by protocols. */
    public static String requireOpaque(String reference) {
        Objects.requireNonNull(reference, "reference");
        boolean drivePath = reference.length() >= 3 && Character.isLetter(reference.charAt(0))
                && reference.charAt(1) == ':'
                && (reference.charAt(2) == '/' || reference.charAt(2) == '\\');
        boolean relativePath = reference.startsWith("./") || reference.startsWith("../")
                || reference.startsWith(".\\") || reference.startsWith("..\\")
                || reference.startsWith("~/") || reference.startsWith("~\\");
        if (reference.isBlank() || reference.startsWith("/")
                || reference.regionMatches(true, 0, "file:", 0, 5)
                || reference.indexOf('\\') >= 0 || drivePath || relativePath) {
            throw new InvalidArtifactReferenceException(
                    "reference must be opaque and must not be a file path");
        }
        return reference;
    }

    /** Stores bytes outside the MCP adapter and returns an opaque reference to them. */
    @FunctionalInterface
    public interface Publisher {
        /** Publishes one immutable payload; implementations must not expose filesystem paths. */
        ArtifactReference publish(String mediaType, byte[] content);

        /**
         * Publishes one immutable payload from a read-only buffer. The default implementation
         * copies once into {@link #publish(String, byte[])}; publishers may override for
         * zero-copy streaming. Implementations must not retain the buffer beyond the call.
         * The distinct name keeps {@link #publish(String, byte[])} call sites with a null
         * payload unambiguous, so the byte[] SAM stays source-compatible for released callers.
         */
        default ArtifactReference publishBuffer(String mediaType, ByteBuffer content) {
            byte[] copy = new byte[content.remaining()];
            content.get(copy);
            return publish(mediaType, copy);
        }

        /** Publisher used when artifact persistence has not been installed. */
        static Publisher unavailable() {
            return (mediaType, content) -> {
                throw new ArtifactUnavailableException(
                        "Artifact persistence is not configured for this server");
            };
        }
    }

    /** Resolves only opaque, session-owned artifact receipts into bounded immutable chunks. */
    @FunctionalInterface
    public interface Reader {
        /**
         * Reads at most {@code maxBytes} beginning at {@code offset}. Implementations own
         * receipt authorization, expiry, quota, integrity verification, and stream cleanup.
         */
        Chunk read(String sessionId, String reference, long offset, int maxBytes);

        /** Reader used when artifact retrieval has not been installed. */
        static Reader unavailable() {
            return (sessionId, reference, offset, maxBytes) -> {
                throw new ArtifactReadUnavailableException(
                        "Artifact retrieval is not configured for this server");
            };
        }
    }

    /**
     * One verified bounded artifact region. Byte arrays are copied on construction and access
     * so an application reader cannot mutate a response after returning it.
     */
    public record Chunk(
            ArtifactReference artifact,
            long offset,
            long nextOffset,
            boolean eof,
            byte[] content) {
        /** Validates chunk bounds independently of any reader implementation. */
        public Chunk {
            artifact = Objects.requireNonNull(artifact, "artifact");
            content = Objects.requireNonNull(content, "content").clone();
            if (offset < 0 || offset > artifact.byteLength()) {
                throw new IllegalArgumentException("offset must be within the artifact");
            }
            if (content.length > MAX_CHUNK_BYTES) {
                throw new IllegalArgumentException("content exceeds the maximum chunk size");
            }
            if (nextOffset < offset
                    || nextOffset != offset + content.length
                    || nextOffset > artifact.byteLength()) {
                throw new IllegalArgumentException("nextOffset must follow the returned content");
            }
            if (eof != (nextOffset == artifact.byteLength())) {
                throw new IllegalArgumentException("eof must identify the end of the artifact");
            }
        }

        @Override public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }

    /** Fixed session-safe outcome for an unknown, expired, or differently owned receipt. */
    @SuppressWarnings("serial")
    public static final class ArtifactNotFoundException extends RuntimeException {
        /** Creates the fixed unavailable-for-session outcome. */
        public ArtifactNotFoundException() {
            super("Artifact is unavailable for this session");
        }
    }

    /** Stable local failure for a server without a usable artifact reader. */
    @SuppressWarnings("serial")
    public static final class ArtifactReadUnavailableException extends RuntimeException {
        /** Creates an unavailable-reader failure. */
        public ArtifactReadUnavailableException(String message) {
            super(message);
        }

        /** Creates an unavailable-reader failure from a delegate failure. */
        public ArtifactReadUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Stable local failure for an offset beyond a receipt's total byte length. */
    @SuppressWarnings("serial")
    public static final class InvalidArtifactOffsetException extends IllegalArgumentException {
        /** Creates the fixed offset failure. */
        public InvalidArtifactOffsetException() {
            super("Artifact offset is outside the payload");
        }
    }

    /** Stable local failure when stored bytes no longer match immutable receipt metadata. */
    @SuppressWarnings("serial")
    public static final class ArtifactIntegrityException extends RuntimeException {
        /** Creates the fixed integrity failure. */
        public ArtifactIntegrityException() {
            super("Artifact integrity verification failed");
        }
    }

    /** Stable local failure for a path-like or otherwise invalid artifact reference. */
    @SuppressWarnings("serial")
    public static final class InvalidArtifactReferenceException extends IllegalArgumentException {
        /** Creates an invalid-reference failure. */
        public InvalidArtifactReferenceException(String message) {
            super(message);
        }
    }

    /** Stable local failure for an unavailable injected artifact publisher. */
    @SuppressWarnings("serial")
    public static final class ArtifactUnavailableException extends RuntimeException {
        /** Creates an unavailable-publisher failure. */
        public ArtifactUnavailableException(String message) {
            super(message);
        }

        /** Creates an unavailable-publisher failure from a delegate failure. */
        public ArtifactUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
