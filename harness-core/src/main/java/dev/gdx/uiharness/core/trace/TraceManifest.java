package dev.gdx.uiharness.core.trace;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

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
        long uncompressedBytes) {

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
    }

    byte[] toJson() {
        return TraceJson.encodeManifest(this);
    }

    static TraceManifest fromJson(Path archive, byte[] json) throws IOException {
        return TraceJson.decodeManifest(archive, json);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > TraceEvent.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }
}
