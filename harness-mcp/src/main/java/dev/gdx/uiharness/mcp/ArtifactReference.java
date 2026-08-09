package dev.gdx.uiharness.mcp;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Opaque reference returned by an injected artifact publisher. */
public record ArtifactReference(
        String reference, String mediaType, long byteLength, String sha256) {
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
    }
}
