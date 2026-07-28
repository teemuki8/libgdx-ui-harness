package dev.gdx.uiharness.core.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One bounded causal event in a trace's newline-delimited event stream. */
public record TraceEvent(
        long sequence,
        Kind kind,
        String sessionId,
        String requestId,
        long logicalTime,
        Long frame,
        Long revision,
        Long parentSequence,
        Map<String, String> evidence) {
    static final int MAX_TEXT_LENGTH = 16_384;
    static final int MAX_EVIDENCE_ENTRIES = 256;

    /** Supported lifecycle evidence kinds. */
    public enum Kind {
        COMMAND_STARTED,
        INPUT_DISPATCHED,
        COMMAND_COMPLETED,
        COMMAND_FAILED,
        SNAPSHOT,
        LOG
    }

    /** Validates and copies one event. Sequence {@code -1} requests recorder assignment. */
    public TraceEvent {
        if (sequence < -1) {
            throw new IllegalArgumentException("sequence must be -1 or non-negative");
        }
        kind = Objects.requireNonNull(kind, "kind");
        sessionId = requireText(sessionId, "sessionId");
        if (requestId != null) {
            requestId = requireText(requestId, "requestId");
        }
        if (logicalTime < 0) {
            throw new IllegalArgumentException("logicalTime must be non-negative");
        }
        validateOptionalNonNegative(frame, "frame");
        validateOptionalNonNegative(revision, "revision");
        validateOptionalNonNegative(parentSequence, "parentSequence");
        evidence = copyEvidence(evidence);
    }

    /** Creates before-snapshot evidence for one command. */
    public static TraceEvent commandStarted(
            String sessionId,
            String requestId,
            long logicalTime,
            SemanticSnapshot before,
            Map<String, String> evidence) {
        Objects.requireNonNull(before, "before");
        return new TraceEvent(-1, Kind.COMMAND_STARTED, sessionId, requestId, logicalTime,
                before.frame(), before.revision(), null, evidence);
    }

    /** Creates evidence for one input dispatch causally parented to an earlier event. */
    public static TraceEvent inputDispatched(
            String sessionId,
            String requestId,
            long logicalTime,
            long frame,
            long revision,
            long parentSequence,
            Map<String, String> evidence) {
        return new TraceEvent(-1, Kind.INPUT_DISPATCHED, sessionId, requestId, logicalTime,
                frame, revision, parentSequence, evidence);
    }

    /** Creates after-snapshot evidence for successful command completion. */
    public static TraceEvent commandCompleted(
            String sessionId,
            String requestId,
            long logicalTime,
            SemanticSnapshot after,
            long parentSequence,
            Map<String, String> evidence) {
        Objects.requireNonNull(after, "after");
        return new TraceEvent(-1, Kind.COMMAND_COMPLETED, sessionId, requestId, logicalTime,
                after.frame(), after.revision(), parentSequence, evidence);
    }

    TraceEvent withSequence(long assignedSequence) {
        return new TraceEvent(assignedSequence, kind, sessionId, requestId, logicalTime, frame,
                revision, parentSequence, evidence);
    }

    TraceEvent withEvidence(Map<String, String> sanitizedEvidence) {
        return new TraceEvent(sequence, kind, sessionId, requestId, logicalTime, frame, revision,
                parentSequence, sanitizedEvidence);
    }

    byte[] toJson(ObjectMapper mapper) throws IOException {
        ObjectNode object = mapper.createObjectNode();
        object.put("sequence", sequence);
        object.put("kind", kind.name());
        object.put("sessionId", sessionId);
        if (requestId == null) {
            object.putNull("requestId");
        } else {
            object.put("requestId", requestId);
        }
        object.put("logicalTime", logicalTime);
        putNullable(object, "frame", frame);
        putNullable(object, "revision", revision);
        putNullable(object, "parentSequence", parentSequence);
        ObjectNode evidenceNode = object.putObject("evidence");
        evidence.forEach(evidenceNode::put);
        return mapper.writeValueAsBytes(object);
    }

    static TraceEvent fromJson(ObjectMapper mapper, byte[] json) throws IOException {
        JsonNode object = mapper.readTree(json);
        if (object == null || !object.isObject()) {
            throw new IOException("event must be a JSON object");
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        JsonNode evidenceNode = required(object, "evidence");
        if (!evidenceNode.isObject()) {
            throw new IOException("event evidence must be an object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields =
                evidenceNode.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw new IOException("event evidence values must be strings");
            }
            evidence.put(field.getKey(), field.getValue().textValue());
        }
        try {
            return new TraceEvent(
                    requiredLong(object, "sequence"),
                    Kind.valueOf(requiredText(object, "kind")),
                    requiredText(object, "sessionId"),
                    nullableText(object.get("requestId")),
                    requiredLong(object, "logicalTime"),
                    nullableLong(object.get("frame")),
                    nullableLong(object.get("revision")),
                    nullableLong(object.get("parentSequence")),
                    evidence);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("invalid event fields", exception);
        }
    }

    private static JsonNode required(JsonNode object, String name) throws IOException {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            throw new IOException("event is missing " + name);
        }
        return value;
    }

    private static String requiredText(JsonNode object, String name) throws IOException {
        JsonNode value = required(object, name);
        if (!value.isTextual()) {
            throw new IOException("event " + name + " must be a string");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode object, String name) throws IOException {
        JsonNode value = required(object, name);
        if (!value.isIntegralNumber()) {
            throw new IOException("event " + name + " must be an integer");
        }
        return value.longValue();
    }

    private static String nullableText(JsonNode value) throws IOException {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IOException("event text field must be a string or null");
        }
        return value.textValue();
    }

    private static Long nullableLong(JsonNode value) throws IOException {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new IOException("event numeric field must be an integer or null");
        }
        return value.longValue();
    }

    private static void putNullable(ObjectNode object, String name, Long value) {
        if (value == null) {
            object.putNull(name);
        } else {
            object.put(name, value);
        }
    }

    private static Map<String, String> copyEvidence(Map<String, String> source) {
        Objects.requireNonNull(source, "evidence");
        if (source.size() > MAX_EVIDENCE_ENTRIES) {
            throw new IllegalArgumentException(
                    "evidence exceeds " + MAX_EVIDENCE_ENTRIES + " entries");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                requireText(key, "evidence key"), requireText(value, "evidence value")));
        return Map.copyOf(copy);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_TEXT_LENGTH);
        }
        return value;
    }

    private static void validateOptionalNonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
