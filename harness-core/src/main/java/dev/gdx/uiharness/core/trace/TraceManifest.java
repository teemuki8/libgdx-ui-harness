package dev.gdx.uiharness.core.trace;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Final or partial metadata for one atomically published trace archive. */
public record TraceManifest(
        Path archive,
        String sessionId,
        Instant startedAt,
        Instant endedAt,
        boolean complete,
        String terminationReason,
        long eventCount,
        long artifactCount,
        long uncompressedBytes,
        String schemaVersion,
        String eventsSha256,
        Map<String, ArtifactBinding> artifacts) {
    public static final String V1 = "trace-manifest/v1";
    public static final String V2 = "trace-manifest/v2";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** Canonical per-artifact digest binding carried by a v2 manifest. */
    public record ArtifactBinding(String sha256, long size, String mediaType) {
        /** Validates immutable artifact binding metadata. */
        public ArtifactBinding {
            requireSha256(sha256, "sha256");
            if (size < 0) {
                throw new IllegalArgumentException("artifact size must be non-negative");
            }
            requireText(mediaType, "mediaType");
        }
    }

    /** Validates immutable manifest metadata. */
    public TraceManifest {
        archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        sessionId = requireText(sessionId, "sessionId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        endedAt = Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not precede startedAt");
        }
        terminationReason = requireText(terminationReason, "terminationReason");
        if (eventCount < 0 || artifactCount < 0 || uncompressedBytes < 0) {
            throw new IllegalArgumentException("manifest counts must be non-negative");
        }
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (!schemaVersion.equals(V1) && !schemaVersion.equals(V2)) {
            throw new IllegalArgumentException(
                    "unknown manifest schema version: " + schemaVersion);
        }
        if (schemaVersion.equals(V2)) {
            requireSha256(eventsSha256, "eventsSha256");
            artifacts = Map.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        } else {
            if (eventsSha256 != null || artifacts != null && !artifacts.isEmpty()) {
                throw new IllegalArgumentException(
                        "v1 manifests carry no digest bindings");
            }
            artifacts = Map.of();
        }
    }

    byte[] toJson() {
        return TraceJson.encodeManifest(this);
    }

    static TraceManifest fromJson(Path archive, byte[] json) throws IOException {
        return TraceJson.decodeManifest(archive, json);
    }

    private static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must be 64 lowercase hex digits");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > TraceEvent.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }
}
