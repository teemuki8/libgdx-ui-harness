package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DiagnosticEnvelopeTest {
    @Test void registryIsClosedVersionedAndHasStableDefaultSemantics() {
        assertEquals("diagnostic-code-registry/v1", DiagnosticCode.REGISTRY_VERSION);
        assertEquals(List.of(
                "UNKNOWN_OPERATION", "MISSING_ARGUMENT", "UNKNOWN_ARGUMENT",
                "INVALID_ARGUMENT_TYPE", "OUT_OF_RANGE", "INVALID_ENUM_VALUE",
                "SCHEMA_CONFLICT", "LOCATOR_NOT_FOUND", "LOCATOR_AMBIGUOUS",
                "STALE_REVISION", "STATE_NOT_READY", "BUILD_FAILED",
                "LAUNCH_FAILED", "DEADLINE_EXCEEDED", "LIMIT_EXCEEDED", "NO_PROGRESS",
                "LOOP_DETECTED", "RECOVERY_BUDGET_EXHAUSTED", "INTERNAL_ERROR"),
                java.util.Arrays.stream(DiagnosticCode.values())
                        .map(Enum::name).toList());
        assertEquals(DiagnosticEnvelope.Disposition.TRANSIENT,
                DiagnosticCode.STATE_NOT_READY.defaultDisposition());
        assertEquals(DiagnosticEnvelope.Disposition.TERMINAL,
                DiagnosticCode.UNKNOWN_OPERATION.defaultDisposition());
        assertEquals(DiagnosticEnvelope.Disposition.TERMINAL,
                DiagnosticCode.LOOP_DETECTED.defaultDisposition());
        assertEquals(DiagnosticEnvelope.Disposition.TERMINAL,
                DiagnosticCode.LIMIT_EXCEEDED.defaultDisposition());
        assertEquals(
                "a bounded resource or result limit was exceeded",
                DiagnosticCode.LIMIT_EXCEEDED.meaning());
        assertEquals(
                "an equivalent recovery loop reached its boundary",
                DiagnosticCode.LOOP_DETECTED.meaning());
    }

    @Test void envelopeIsBoundedImmutableAndDigestIdentified() {
        DiagnosticEnvelope.FieldProblem problem = new DiagnosticEnvelope.FieldProblem(
                DiagnosticCode.OUT_OF_RANGE,
                "$.maxWidth",
                "9000",
                new DiagnosticEnvelope.Expected(
                        "integer", true, null, List.of(),
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.valueOf(8192),
                        null, null, null, false),
                List.of("inclusive range [1,8192]"),
                Map.of("maxWidth", 1280));
        DiagnosticEnvelope envelope = DiagnosticEnvelope.create(
                "request-1",
                1,
                "ui_screenshot",
                DiagnosticCode.OUT_OF_RANGE,
                "One or more arguments are invalid",
                List.of(problem),
                null,
                DiagnosticEnvelope.Progress.unavailable(),
                new DiagnosticEnvelope.Recovery(
                        "recovery-policy/v1", 1, 2, 29_000, 30_000,
                        "correct-request"),
                List.of("event:" + "a".repeat(64)));

        assertEquals("diagnostic-envelope/v1", envelope.schemaVersion());
        assertTrue(envelope.diagnosticId().matches("diag-[0-9a-f]{64}"));
        assertTrue(envelope.retryable());
        assertEquals(DiagnosticEnvelope.Disposition.TRANSIENT,
                envelope.disposition());
        assertEquals("$.maxWidth", envelope.fieldPath());
        assertEquals(1, envelope.problems().size());
        assertThrows(UnsupportedOperationException.class,
                () -> envelope.problems().add(problem));
    }

    @Test void releasedCompleteFactoryRemainsSourceCompatible() {
        DiagnosticEnvelope envelope = DiagnosticEnvelope.create(
                "request-1",
                1,
                "ui_action",
                DiagnosticCode.LOCATOR_NOT_FOUND,
                "No actor matched",
                List.of(),
                "role=button",
                List.of(),
                Map.of("matchCount", "0"),
                12L,
                "trace-1",
                null,
                DiagnosticEnvelope.Progress.unavailable(),
                new DiagnosticEnvelope.Recovery(
                        "recovery-policy/v1", 0, 1, 12, 30_000, "refine-locator"),
                List.of());

        assertEquals(List.of(), envelope.suggestions());
        assertEquals("role=button", envelope.locator());
    }

    @Test void terminalCodesCannotClaimRetryability() {
        DiagnosticEnvelope envelope = DiagnosticEnvelope.create(
                "request-1",
                1,
                "ui_action",
                DiagnosticCode.LOOP_DETECTED,
                "Equivalent request loop exhausted",
                List.of(),
                null,
                DiagnosticEnvelope.Progress.unavailable(),
                new DiagnosticEnvelope.Recovery(
                        "recovery-policy/v1", 3, 3, 30_000, 30_000,
                        "equivalent-error-no-progress/v1"),
                List.of());

        assertFalse(envelope.retryable());
        assertEquals(DiagnosticEnvelope.Disposition.TERMINAL,
                envelope.disposition());
    }
}
