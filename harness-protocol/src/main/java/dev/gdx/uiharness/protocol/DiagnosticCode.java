package dev.gdx.uiharness.protocol;

import java.util.List;

/** Closed V1 agent-facing diagnostic code registry with stable default semantics. */
public enum DiagnosticCode {
    UNKNOWN_OPERATION(DiagnosticEnvelope.Disposition.TERMINAL),
    MISSING_ARGUMENT(DiagnosticEnvelope.Disposition.TRANSIENT),
    UNKNOWN_ARGUMENT(DiagnosticEnvelope.Disposition.TRANSIENT),
    INVALID_ARGUMENT_TYPE(DiagnosticEnvelope.Disposition.TRANSIENT),
    OUT_OF_RANGE(DiagnosticEnvelope.Disposition.TRANSIENT),
    INVALID_ENUM_VALUE(DiagnosticEnvelope.Disposition.TRANSIENT),
    SCHEMA_CONFLICT(DiagnosticEnvelope.Disposition.TERMINAL),
    LOCATOR_NOT_FOUND(DiagnosticEnvelope.Disposition.TRANSIENT),
    LOCATOR_AMBIGUOUS(DiagnosticEnvelope.Disposition.TERMINAL),
    STALE_REVISION(DiagnosticEnvelope.Disposition.TRANSIENT),
    STATE_NOT_READY(DiagnosticEnvelope.Disposition.TRANSIENT),
    BUILD_FAILED(DiagnosticEnvelope.Disposition.TERMINAL),
    LAUNCH_FAILED(DiagnosticEnvelope.Disposition.TERMINAL),
    DEADLINE_EXCEEDED(DiagnosticEnvelope.Disposition.TERMINAL),
    LIMIT_EXCEEDED(DiagnosticEnvelope.Disposition.TERMINAL),
    NO_PROGRESS(DiagnosticEnvelope.Disposition.TRANSIENT),
    LOOP_DETECTED(DiagnosticEnvelope.Disposition.TERMINAL),
    RECOVERY_BUDGET_EXHAUSTED(DiagnosticEnvelope.Disposition.TERMINAL),
    INTERNAL_ERROR(DiagnosticEnvelope.Disposition.TERMINAL);

    /** Registry identity whose code meanings cannot change. */
    public static final String REGISTRY_VERSION = "diagnostic-code-registry/v1";

    private final DiagnosticEnvelope.Disposition defaultDisposition;

    DiagnosticCode(DiagnosticEnvelope.Disposition defaultDisposition) {
        this.defaultDisposition = defaultDisposition;
    }

    /** Returns the immutable default transient or terminal meaning. */
    public DiagnosticEnvelope.Disposition defaultDisposition() {
        return defaultDisposition;
    }

    /** Returns the stable V1 meaning independent of human diagnostic messages. */
    public String meaning() {
        return switch (this) {
            case UNKNOWN_OPERATION -> "operation is not in the allowlisted catalog";
            case MISSING_ARGUMENT -> "a required argument is absent";
            case UNKNOWN_ARGUMENT -> "an argument is not declared by the closed schema";
            case INVALID_ARGUMENT_TYPE -> "an argument has the wrong JSON type";
            case OUT_OF_RANGE -> "a value is outside an inclusive numeric or length bound";
            case INVALID_ENUM_VALUE -> "a value or tagged variant is not admissible";
            case SCHEMA_CONFLICT -> "the request cannot be corrected unambiguously";
            case LOCATOR_NOT_FOUND -> "the lazy locator resolved to zero actors";
            case LOCATOR_AMBIGUOUS -> "the strict locator resolved to multiple actors";
            case STALE_REVISION -> "evidence does not match the current semantic revision";
            case STATE_NOT_READY -> "the required observable state is not ready";
            case BUILD_FAILED -> "the declared build did not complete successfully";
            case LAUNCH_FAILED -> "the declared runtime did not become healthy";
            case DEADLINE_EXCEEDED -> "the monotonic deadline was reached";
            case LIMIT_EXCEEDED -> "a bounded resource or result limit was exceeded";
            case NO_PROGRESS -> "declared evidence did not change within policy";
            case LOOP_DETECTED -> "an equivalent recovery loop reached its boundary";
            case RECOVERY_BUDGET_EXHAUSTED -> "a hard recovery or cost ceiling was reached";
            case INTERNAL_ERROR -> "an unexpected bounded internal failure occurred";
        };
    }

    /** Returns a deterministic bounded registry projection for discovery. */
    public static List<Entry> registry() {
        return java.util.Arrays.stream(values())
                .map(code -> new Entry(
                        code.name(),
                        code.defaultDisposition,
                        code.defaultDisposition == DiagnosticEnvelope.Disposition.TRANSIENT,
                        code.meaning()))
                .toList();
    }

    /** One stable discoverable registry entry. */
    public record Entry(
            String code,
            DiagnosticEnvelope.Disposition disposition,
            boolean retryable,
            String meaning) {}
}
