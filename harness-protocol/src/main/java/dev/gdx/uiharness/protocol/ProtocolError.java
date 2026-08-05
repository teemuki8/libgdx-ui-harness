package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.gdx.uiharness.core.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable remotely safe typed protocol failure. */
public record ProtocolError(
        Code code,
        String message,
        String requestId,
        String sessionId,
        String locator,
        long elapsedMillis,
        Long lastSnapshotRevision,
        String traceReference,
        List<Map<String, String>> candidates,
        Map<String, String> details,
        String traceId,
        List<LocatorSuggestionSpec> suggestions) {
    private static final int MAX_CANDIDATES = 1_000;
    private static final int MAX_DETAILS = 256;
    private static final int MAX_SUGGESTIONS = 1_000;

    /** Validates and recursively copies bounded error evidence. */
    public ProtocolError {
        code = Objects.requireNonNull(code, "code");
        ProtocolJson.requireText(message, "message");
        ProtocolJson.requireIdentifier(requestId, "requestId");
        ProtocolJson.requireIdentifier(sessionId, "sessionId");
        if (locator != null) {
            ProtocolJson.requireText(locator, "locator");
        }
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be non-negative");
        }
        if (lastSnapshotRevision != null && lastSnapshotRevision < 0) {
            throw new IllegalArgumentException("lastSnapshotRevision must be non-negative");
        }
        if (traceReference != null) {
            ProtocolJson.requireText(traceReference, "traceReference");
        }
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("too many error candidates");
        }
        candidates = candidates.stream().map(ProtocolError::copyEvidenceMap).toList();
        details = copyEvidenceMap(Objects.requireNonNull(details, "details"));
        if (traceId != null) {
            ProtocolJson.requireIdentifier(traceId, "traceId");
        }
        Objects.requireNonNull(suggestions, "suggestions");
        if (suggestions.size() > MAX_SUGGESTIONS) {
            throw new IllegalArgumentException("too many locator suggestions");
        }
        suggestions = List.copyOf(suggestions);
    }

    /**
     * Backward-compatible constructor retaining the pre-suggestion signature.
     *
     * @param code stable failure category
     * @param message bounded human-readable explanation
     * @param requestId request identifier
     * @param sessionId session identifier
     * @param locator failed locator description, when present
     * @param elapsedMillis elapsed monotonic time at failure
     * @param lastSnapshotRevision most recent semantic revision, when present
     * @param traceReference trace artifact reference, when present
     * @param candidates bounded candidate summaries
     * @param details bounded error-specific evidence
     * @param traceId bounded internal trace identifier, when present
     */
    public ProtocolError(
            Code code,
            String message,
            String requestId,
            String sessionId,
            String locator,
            long elapsedMillis,
            Long lastSnapshotRevision,
            String traceReference,
            List<Map<String, String>> candidates,
            Map<String, String> details,
            String traceId) {
        this(code, message, requestId, sessionId, locator, elapsedMillis, lastSnapshotRevision,
                traceReference, candidates, details, traceId, List.of());
    }

    private static Map<String, String> copyEvidenceMap(Map<String, String> source) {
        if (source.size() > MAX_DETAILS) {
            throw new IllegalArgumentException("too many error evidence entries");
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            ProtocolJson.requireText(entry.getKey(), "evidence key");
            ProtocolJson.requireText(entry.getValue(), "evidence value");
        }
        return Map.copyOf(source);
    }

    /** Stable remotely visible failure codes. */
    public enum Code {
        INVALID_REQUEST("invalid-request"),
        UNSUPPORTED_CAPABILITY("unsupported-capability"),
        SESSION_NOT_FOUND("session-not-found"),
        SESSION_CLOSED("session-closed"),
        NOT_FOUND("not-found"),
        STRICTNESS_VIOLATION("strictness-violation"),
        NOT_ACTIONABLE("not-actionable"),
        TIMEOUT("timeout"),
        RENDER_THREAD_FAILURE("render-thread-failure"),
        CAPTURE_FAILURE("capture-failure"),
        LIMIT_EXCEEDED("limit-exceeded"),
        PROTOCOL_VERSION_MISMATCH("protocol-version-mismatch"),
        INTERNAL_ERROR("internal-error");

        private final String wireName;

        Code(String wireName) {
            this.wireName = wireName;
        }

        /** Returns the stable kebab-case wire name. */
        @JsonValue public String wireName() {
            return wireName;
        }

        /** Parses an allowlisted stable wire name. */
        @JsonCreator public static Code fromWireName(String wireName) {
            return Arrays.stream(values())
                    .filter(value -> value.wireName.equals(wireName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown protocol error code: " + wireName));
        }

        /** Converts a core failure category without accepting arbitrary strings. */
        public static Code fromCore(ErrorCode core) {
            return valueOf(Objects.requireNonNull(core, "core").name());
        }
    }
}
