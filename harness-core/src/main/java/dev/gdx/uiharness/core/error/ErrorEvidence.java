package dev.gdx.uiharness.core.error;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Bounded structured context attached to a {@link HarnessException}. All collections are
 * recursively copied so evidence can be safely shared across module and thread boundaries.
 *
 * @param requestId request identifier, when a request context exists
 * @param sessionId session identifier, when a session context exists
 * @param locator stable locator description, when relevant
 * @param elapsed elapsed monotonic time at failure
 * @param lastSnapshotRevision most recent semantic revision, when available
 * @param traceReference trace artifact reference, when available
 * @param candidates bounded candidate summaries for location failures
 * @param details bounded error-specific key/value evidence
 */
public record ErrorEvidence(
        Optional<String> requestId,
        Optional<String> sessionId,
        Optional<String> locator,
        Duration elapsed,
        OptionalLong lastSnapshotRevision,
        Optional<String> traceReference,
        List<Map<String, String>> candidates,
        Map<String, String> details) {
    private static final int MAX_STRING_LENGTH = 16_384;
    private static final int MAX_CANDIDATES = 1_000;
    private static final int MAX_ENTRIES_PER_MAP = 256;
    private static final ErrorEvidence EMPTY =
            new ErrorEvidence(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Duration.ZERO,
                    OptionalLong.empty(),
                    Optional.empty(),
                    List.of(),
                    Map.of());

    /** Validates and defensively copies all evidence. */
    public ErrorEvidence {
        requestId = validateOptional(requestId, "requestId");
        sessionId = validateOptional(sessionId, "sessionId");
        locator = validateOptional(locator, "locator");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must be non-negative");
        }
        Objects.requireNonNull(lastSnapshotRevision, "lastSnapshotRevision");
        if (lastSnapshotRevision.isPresent() && lastSnapshotRevision.getAsLong() < 0) {
            throw new IllegalArgumentException("lastSnapshotRevision must be non-negative");
        }
        traceReference = validateOptional(traceReference, "traceReference");

        Objects.requireNonNull(candidates, "candidates");
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("too many candidate summaries");
        }
        var candidateCopies = new ArrayList<Map<String, String>>(candidates.size());
        for (Map<String, String> candidate : candidates) {
            candidateCopies.add(copyMap(candidate, "candidate"));
        }
        candidates = List.copyOf(candidateCopies);
        details = copyMap(details, "details");
    }

    /**
     * Returns evidence with no request-specific context.
     *
     * @return a shared immutable empty evidence value
     */
    public static ErrorEvidence empty() {
        return EMPTY;
    }

    /**
     * Creates context-free evidence containing error-specific details.
     *
     * @param details bounded detail values
     * @return immutable evidence containing the supplied details
     */
    public static ErrorEvidence ofDetails(Map<String, String> details) {
        return new ErrorEvidence(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ZERO,
                OptionalLong.empty(),
                Optional.empty(),
                List.of(),
                details);
    }

    private static Optional<String> validateOptional(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(text -> validateString(text, name, false));
        return value;
    }

    private static Map<String, String> copyMap(Map<String, String> source, String name) {
        Objects.requireNonNull(source, name);
        if (source.size() > MAX_ENTRIES_PER_MAP) {
            throw new IllegalArgumentException(name + " has too many entries");
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            validateString(entry.getKey(), name + " key", true);
            validateString(entry.getValue(), name + " value", false);
        }
        return Map.copyOf(source);
    }

    private static void validateString(String value, String name, boolean nonBlank) {
        Objects.requireNonNull(value, name);
        if (nonBlank && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_STRING_LENGTH + " characters");
        }
    }
}
