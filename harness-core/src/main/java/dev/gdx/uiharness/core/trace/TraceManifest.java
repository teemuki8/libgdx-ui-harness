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
        Map<String, ArtifactBinding> artifacts,
        String archiveSha256,
        long archiveSize) {
    public static final String V1 = "trace-manifest/v1";
    public static final String V2 = "trace-manifest/v2";
    /** Upper bound on artifact bindings carried by one v2 manifest. */
    public static final int MAX_MANIFEST_ARTIFACTS = 100_000;
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
            if (artifacts.size() > MAX_MANIFEST_ARTIFACTS) {
                throw new IllegalArgumentException(
                        "manifest artifact bindings exceed the limit");
            }
            if (artifactCount != artifacts.size()) {
                throw new IllegalArgumentException(
                        "artifactCount must equal the number of artifact bindings");
            }
            for (Map.Entry<String, ArtifactBinding> entry : artifacts.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().sha256())) {
                    throw new IllegalArgumentException(
                            "artifact binding key must match its sha256");
                }
            }
            if (archiveSha256 == null) {
                if (archiveSize != -1) {
                    throw new IllegalArgumentException(
                            "legacy v2 manifests must carry archiveSize -1");
                }
            } else {
                requireSha256(archiveSha256, "archiveSha256");
                if (archiveSize < 0) {
                    throw new IllegalArgumentException(
                            "archiveSize must be non-negative");
                }
            }
        } else {
            if (eventsSha256 != null || artifacts != null && !artifacts.isEmpty()
                    || archiveSha256 != null || archiveSize != -1) {
                throw new IllegalArgumentException(
                        "v1 manifests carry no digest bindings");
            }
            artifacts = Map.of();
        }
    }

    /**
     * Builds a v1 manifest without digest bindings (released constructor shape).
     */
    public TraceManifest(
            Path archive,
            String sessionId,
            Instant startedAt,
            Instant endedAt,
            boolean complete,
            String terminationReason,
            long eventCount,
            long artifactCount,
            long uncompressedBytes) {
        this(archive, sessionId, startedAt, endedAt, complete, terminationReason,
                eventCount, artifactCount, uncompressedBytes, V1, null, null, null, -1);
    }

    /**
     * Builds a v2 manifest whose archive identity is blank (unverified legacy);
     * the recorder's returned manifest carries the verified archive digest and size.
     */
    public TraceManifest(
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
        this(archive, sessionId, startedAt, endedAt, complete, terminationReason,
                eventCount, artifactCount, uncompressedBytes, schemaVersion,
                eventsSha256, artifacts, null, -1);
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
