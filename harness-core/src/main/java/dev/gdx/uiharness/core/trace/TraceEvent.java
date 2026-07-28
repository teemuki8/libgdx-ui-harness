package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.io.IOException;
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
    static final int MAX_ENCODED_BYTES = 1_048_576;

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

    byte[] toJson() {
        return TraceJson.encodeEvent(this);
    }

    static TraceEvent fromJson(byte[] json) throws IOException {
        return TraceJson.decodeEvent(json);
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
        return java.util.Collections.unmodifiableMap(copy);
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
