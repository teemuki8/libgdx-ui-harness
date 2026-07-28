package dev.gdx.uiharness.mcp;

import java.util.Objects;

/** Opaque reference returned by an injected artifact publisher. */
public record ArtifactReference(
        String reference, String mediaType, long byteLength, String sha256) {
    /** Validates transport-safe artifact metadata without interpreting the reference as a path. */
    public ArtifactReference {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(sha256, "sha256");
        boolean drivePath = reference.length() >= 3 && Character.isLetter(reference.charAt(0))
                && reference.charAt(1) == ':'
                && (reference.charAt(2) == '/' || reference.charAt(2) == '\\');
        boolean relativePath = reference.startsWith("./") || reference.startsWith("../")
                || reference.startsWith(".\\") || reference.startsWith("..\\")
                || reference.startsWith("~/") || reference.startsWith("~\\");
        if (reference.isBlank() || reference.startsWith("/")
                || reference.regionMatches(true, 0, "file:", 0, 5)
                || reference.indexOf('\\') >= 0 || drivePath || relativePath) {
            throw new IllegalArgumentException("reference must be opaque and must not be a file path");
        }
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

    /** Stores bytes outside the MCP adapter and returns an opaque reference to them. */
    @FunctionalInterface
    public interface Publisher {
        /** Publishes one immutable payload; implementations must not expose filesystem paths. */
        ArtifactReference publish(String mediaType, byte[] content);

        /** Publisher used when artifact persistence has not been installed. */
        static Publisher unavailable() {
            return (mediaType, content) -> {
                throw new ArtifactUnavailableException(
                        "Artifact persistence is not configured for this server");
            };
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
