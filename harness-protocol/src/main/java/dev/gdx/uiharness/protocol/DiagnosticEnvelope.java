package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable bounded diagnostic, progress, and recovery envelope for agent clients. */
public record DiagnosticEnvelope(
        String schemaVersion,
        String diagnosticId,
        String requestId,
        long sequence,
        String operation,
        DiagnosticCode code,
        Severity severity,
        Disposition disposition,
        boolean retryable,
        String message,
        String fieldPath,
        String observed,
        Expected expected,
        List<String> admissible,
        Map<String, Object> minimalExample,
        List<FieldProblem> problems,
        String locator,
        List<Map<String, String>> candidates,
        Map<String, String> details,
        List<LocatorSuggestionSpec> suggestions,
        Long elapsedMillis,
        String traceId,
        StateIdentity stateIdentity,
        Progress progress,
        Recovery recovery,
        List<String> evidenceRefs) {
    /** Immutable envelope version. */
    public static final String SCHEMA_VERSION = "diagnostic-envelope/v1";
    private static final int MAX_PROBLEMS = 256;
    private static final int MAX_EVIDENCE_REFS = 256;
    private static final int MAX_SUGGESTIONS = 1_000;

    /** Validates and defensively copies every bounded public value. */
    public DiagnosticEnvelope {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported diagnostic envelope");
        }
        requireIdentifier(diagnosticId, "diagnosticId");
        requireIdentifier(requestId, "requestId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        requireText(operation, "operation");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(disposition, "disposition");
        if (retryable != (disposition == Disposition.TRANSIENT)) {
            throw new IllegalArgumentException("retryability contradicts disposition");
        }
        requireText(message, "message");
        optionalText(fieldPath, "fieldPath");
        optionalBounded(observed, "observed");
        admissible = boundedStrings(admissible, 256, "admissible");
        minimalExample = copyObject(minimalExample, "minimalExample");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        if (problems.size() > MAX_PROBLEMS) {
            throw new IllegalArgumentException("too many field problems");
        }
        optionalText(locator, "locator");
        candidates = copyCandidates(candidates);
        details = copyStringMap(details, "details");
        suggestions = List.copyOf(Objects.requireNonNull(suggestions, "suggestions"));
        if (suggestions.size() > MAX_SUGGESTIONS) {
            throw new IllegalArgumentException("too many locator suggestions");
        }
        if (elapsedMillis != null && elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be non-negative");
        }
        optionalText(traceId, "traceId");
        progress = Objects.requireNonNull(progress, "progress");
        recovery = Objects.requireNonNull(recovery, "recovery");
        evidenceRefs = boundedStrings(
                evidenceRefs, MAX_EVIDENCE_REFS, "evidenceRefs");
    }

    /**
     * Backward-compatible constructor retaining the pre-suggestion signature.
     *
     * @param schemaVersion immutable envelope version
     * @param diagnosticId bounded diagnostic identifier
     * @param requestId bounded request identifier
     * @param sequence monotonically increasing diagnostic sequence
     * @param operation bounded operation name
     * @param code closed diagnostic code
     * @param severity derived severity
     * @param disposition derived disposition
     * @param retryable whether the diagnostic is retryable
     * @param message bounded human-readable message
     * @param fieldPath first problem field path, when present
     * @param observed first problem observed value, when present
     * @param expected first problem expectation, when present
     * @param admissible first problem admissible values, when present
     * @param minimalExample first problem example, when present
     * @param problems bounded field problems
     * @param locator failed locator description, when present
     * @param candidates bounded candidate summaries
     * @param details bounded error-specific evidence
     * @param elapsedMillis operation elapsed time, when present
     * @param traceId bounded trace identifier, when present
     * @param stateIdentity bounded state identity
     * @param progress bounded progress state
     * @param recovery bounded recovery state
     * @param evidenceRefs bounded evidence references
     */
    public DiagnosticEnvelope(
            String schemaVersion,
            String diagnosticId,
            String requestId,
            long sequence,
            String operation,
            DiagnosticCode code,
            Severity severity,
            Disposition disposition,
            boolean retryable,
            String message,
            String fieldPath,
            String observed,
            Expected expected,
            List<String> admissible,
            Map<String, Object> minimalExample,
            List<FieldProblem> problems,
            String locator,
            List<Map<String, String>> candidates,
            Map<String, String> details,
            Long elapsedMillis,
            String traceId,
            StateIdentity stateIdentity,
            Progress progress,
            Recovery recovery,
            List<String> evidenceRefs) {
        this(schemaVersion, diagnosticId, requestId, sequence, operation, code, severity,
                disposition, retryable, message, fieldPath, observed, expected, admissible,
                minimalExample, problems, locator, candidates, details, List.of(), elapsedMillis,
                traceId, stateIdentity, progress, recovery, evidenceRefs);
    }

    /** Constructs an envelope and derives its immutable identity from all other fields. */
    public static DiagnosticEnvelope create(
            String requestId,
            long sequence,
            String operation,
            DiagnosticCode code,
            String message,
            List<FieldProblem> problems,
            StateIdentity stateIdentity,
            Progress progress,
            Recovery recovery,
            List<String> evidenceRefs) {
        return create(
                requestId, sequence, operation, code, message, problems,
                null, List.of(), Map.of(), List.of(), null, null,
                stateIdentity, progress, recovery, evidenceRefs);
    }

    /** Constructs a complete envelope retaining bounded protocol failure evidence. */
    public static DiagnosticEnvelope create(
            String requestId,
            long sequence,
            String operation,
            DiagnosticCode code,
            String message,
            List<FieldProblem> problems,
            String locator,
            List<Map<String, String>> candidates,
            Map<String, String> details,
            List<LocatorSuggestionSpec> suggestions,
            Long elapsedMillis,
            String traceId,
            StateIdentity stateIdentity,
            Progress progress,
            Recovery recovery,
            List<String> evidenceRefs) {
        List<FieldProblem> safeProblems = List.copyOf(problems);
        FieldProblem first = safeProblems.isEmpty() ? null : safeProblems.getFirst();
        Disposition disposition = code.defaultDisposition();
        String digestInput;
        try {
            java.util.LinkedHashMap<String, Object> identity =
                    new java.util.LinkedHashMap<>();
            identity.put("schemaVersion", SCHEMA_VERSION);
            identity.put("requestId", requestId);
            identity.put("sequence", sequence);
            identity.put("operation", operation);
            identity.put("code", code.name());
            identity.put("message", message);
            identity.put("problems", safeProblems);
            identity.put("locator", locator);
            identity.put("candidates", candidates);
            identity.put("details", details);
            identity.put("suggestions", suggestions);
            identity.put("elapsedMillis", elapsedMillis);
            identity.put("traceId", traceId);
            identity.put("stateIdentity", stateIdentity);
            identity.put("progress", progress);
            identity.put("recovery", recovery);
            identity.put("evidenceRefs", evidenceRefs);
            digestInput = ProtocolJson.mapper().writeValueAsString(identity);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "diagnostic identity could not be encoded", failure);
        }
        return new DiagnosticEnvelope(
                SCHEMA_VERSION,
                "diag-" + sha256(digestInput),
                requestId,
                sequence,
                operation,
                code,
                disposition == Disposition.TERMINAL
                        ? Severity.ERROR : Severity.WARNING,
                disposition,
                disposition == Disposition.TRANSIENT,
                message,
                first == null ? null : first.fieldPath(),
                first == null ? null : first.observed(),
                first == null ? null : first.expected(),
                first == null ? List.of() : first.admissible(),
                first == null ? Map.of() : first.minimalExample(),
                safeProblems,
                locator,
                candidates,
                details,
                suggestions,
                elapsedMillis,
                traceId,
                stateIdentity,
                progress,
                recovery,
                evidenceRefs);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireIdentifier(String value, String label) {
        requireText(value, label);
        if (value.length() > 256) {
            throw new IllegalArgumentException(label + " exceeds 256 characters");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()
                || value.length() > ProtocolJson.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(label + " must be bounded text");
        }
    }

    private static void optionalText(String value, String label) {
        if (value != null) {
            requireText(value, label);
        }
    }

    private static void optionalBounded(String value, String label) {
        if (value != null && value.length() > ProtocolJson.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(label + " exceeds text bound");
        }
    }

    private static List<String> boundedStrings(
            List<String> values, int maximum, String label) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, label));
        if (copy.size() > maximum) {
            throw new IllegalArgumentException(label + " exceeds " + maximum);
        }
        copy.forEach(value -> requireText(value, label));
        return copy;
    }

    private static Map<String, Object> copyObject(
            Map<String, Object> value, String label) {
        Objects.requireNonNull(value, label);
        if (value.size() > 256) {
            throw new IllegalArgumentException(label + " exceeds 256 fields");
        }
        try {
            byte[] encoded = ProtocolJson.mapper().writeValueAsBytes(value);
            if (encoded.length > ProtocolJson.MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException(
                        label + " exceeds encoded byte bound");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> copy =
                    ProtocolJson.mapper().readValue(encoded, Map.class);
            return Map.copyOf(copy);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(label + " is not serializable", failure);
        }
    }

    private static List<Map<String, String>> copyCandidates(
            List<Map<String, String>> values) {
        List<Map<String, String>> source =
                List.copyOf(Objects.requireNonNull(values, "candidates"));
        java.util.ArrayList<Map<String, String>> copy = new java.util.ArrayList<>();
        source.stream().limit(255)
                .map(value -> copyStringMap(value, "candidate"))
                .forEach(copy::add);
        if (source.size() > 255) {
            copy.add(Map.of(
                    "overflow", Integer.toString(source.size() - 255)
                            + " additional candidates retained in trace"));
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> copyStringMap(
            Map<String, String> value, String label) {
        Map<String, String> source = Objects.requireNonNull(value, label);
        if (source.size() > 256) {
            throw new IllegalArgumentException(label + " exceeds 256 fields");
        }
        java.util.LinkedHashMap<String, String> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> {
            requireText(key, label + ".key");
            requireText(item, label + ".value");
            copy.put(key, item);
        });
        return Map.copyOf(copy);
    }

    /** Stable severity independent of message wording. */
    public enum Severity {
        WARNING, ERROR;

        @JsonValue public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Whether bounded automatic recovery is admissible. */
    public enum Disposition {
        TRANSIENT, TERMINAL;

        @JsonValue public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Complete expected-field constraints relevant to one validation failure. */
    public record Expected(
            String type,
            boolean required,
            String discriminator,
            List<String> enumValues,
            BigDecimal minimum,
            BigDecimal maximum,
            String pattern,
            Integer minLength,
            Integer maxLength,
            boolean additionalProperties) {
        public Expected {
            requireText(type, "expected.type");
            optionalText(discriminator, "expected.discriminator");
            enumValues = boundedStrings(enumValues, 256, "expected.enumValues");
            optionalText(pattern, "expected.pattern");
            if (minimum != null && maximum != null
                    && minimum.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("expected range is inverted");
            }
            if (minLength != null && (minLength < 0
                    || maxLength != null && minLength > maxLength)) {
                throw new IllegalArgumentException("expected length is invalid");
            }
        }
    }

    /** One independently detectable field failure in deterministic path order. */
    public record FieldProblem(
            DiagnosticCode code,
            String fieldPath,
            String observed,
            Expected expected,
            List<String> admissible,
            Map<String, Object> minimalExample) {
        public FieldProblem {
            Objects.requireNonNull(code, "code");
            requireText(fieldPath, "fieldPath");
            optionalBounded(observed, "observed");
            Objects.requireNonNull(expected, "expected");
            admissible = boundedStrings(admissible, 256, "admissible");
            minimalExample = copyObject(minimalExample, "minimalExample");
        }
    }

    /** Optional immutable application/session state identity. */
    public record StateIdentity(
            String applicationSha256,
            String sessionId,
            Long revision,
            String viewportIdentity) {
        public StateIdentity {
            optionalText(applicationSha256, "applicationSha256");
            optionalText(sessionId, "sessionId");
            optionalText(viewportIdentity, "viewportIdentity");
            if (revision != null && revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
        }
    }

    /** Separate declared progress dimensions; unavailable never becomes unchanged. */
    public record Progress(
            String status,
            Map<String, String> dimensions,
            String ruleId) {
        public Progress {
            if (!List.of("available", "unavailable").contains(status)) {
                throw new IllegalArgumentException("unknown progress status");
            }
            dimensions = Map.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
            if (dimensions.size() > 32
                    || dimensions.values().stream().anyMatch(
                            value -> !List.of(
                                    "changed", "unchanged", "unavailable").contains(value))) {
                throw new IllegalArgumentException("invalid progress dimensions");
            }
            requireText(ruleId, "progress.ruleId");
        }

        public static Progress unavailable() {
            return new Progress(
                    "unavailable", Map.of(), "progress-fingerprint/v1");
        }
    }

    /** Monotonic bounded recovery state attached to every diagnostic. */
    public record Recovery(
            String policyVersion,
            long consumedBefore,
            long consumed,
            long limit,
            long remainingBefore,
            long remaining,
            long elapsedMillis,
            long maxWallTimeMillis,
            String terminatingRule) {
        public Recovery(
                String policyVersion,
                long consumed,
                long limit,
                long elapsedMillis,
                long maxWallTimeMillis,
                String terminatingRule) {
            this(
                    policyVersion,
                    Math.max(0, consumed - 1),
                    consumed,
                    limit,
                    Math.max(0, limit - Math.max(0, consumed - 1)),
                    Math.max(0, limit - consumed),
                    elapsedMillis,
                    maxWallTimeMillis,
                    terminatingRule);
        }

        public Recovery {
            requireText(policyVersion, "policyVersion");
            if (consumedBefore < 0 || consumed < consumedBefore || limit < 1
                    || remainingBefore != Math.max(0, limit - consumedBefore)
                    || remaining != Math.max(0, limit - consumed)
                    || elapsedMillis < 0
                    || maxWallTimeMillis < 1) {
                throw new IllegalArgumentException("invalid recovery counters");
            }
            requireText(terminatingRule, "terminatingRule");
        }
    }
}
