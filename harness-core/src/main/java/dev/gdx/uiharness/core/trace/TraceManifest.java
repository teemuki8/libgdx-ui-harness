package dev.gdx.uiharness.core.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    byte[] toJson(ObjectMapper mapper) throws IOException {
        ObjectNode object = mapper.createObjectNode();
        object.put("sessionId", sessionId);
        object.put("startedAt", startedAt.toString());
        object.put("endedAt", endedAt.toString());
        object.put("complete", complete);
        object.put("terminationReason", terminationReason);
        object.put("eventCount", eventCount);
        object.put("artifactCount", artifactCount);
        object.put("uncompressedBytes", uncompressedBytes);
        return mapper.writeValueAsBytes(object);
    }

    static TraceManifest fromJson(Path archive, ObjectMapper mapper, byte[] json)
            throws IOException {
        JsonNode object = mapper.readTree(json);
        if (object == null || !object.isObject()) {
            throw new IOException("manifest must be a JSON object");
        }
        try {
            return new TraceManifest(
                    archive,
                    requiredText(object, "sessionId"),
                    Instant.parse(requiredText(object, "startedAt")),
                    Instant.parse(requiredText(object, "endedAt")),
                    requiredBoolean(object, "complete"),
                    requiredText(object, "terminationReason"),
                    requiredLong(object, "eventCount"),
                    requiredLong(object, "artifactCount"),
                    requiredLong(object, "uncompressedBytes"));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new IOException("invalid manifest fields", exception);
        }
    }

    private static String requiredText(JsonNode object, String name) throws IOException {
        JsonNode value = object.get(name);
        if (value == null || !value.isTextual()) {
            throw new IOException("manifest " + name + " must be a string");
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode object, String name) throws IOException {
        JsonNode value = object.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IOException("manifest " + name + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static long requiredLong(JsonNode object, String name) throws IOException {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber()) {
            throw new IOException("manifest " + name + " must be an integer");
        }
        return value.longValue();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > TraceEvent.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }
}
