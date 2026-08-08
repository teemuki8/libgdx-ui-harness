package dev.gdx.uiharness.core.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ErrorCodeContractTest {
    @Test void renderThreadViolationIsStableAndSitsAfterRenderThreadFailure() {
        ErrorCode violation = ErrorCode.valueOf("RENDER_THREAD_VIOLATION");
        assertEquals(ErrorCode.RENDER_THREAD_FAILURE.ordinal() + 1, violation.ordinal());
        assertEquals("RENDER_THREAD_VIOLATION", violation.name());
    }

    @Test void everyCoreErrorCodeKeepsItsClosedOrderedContract() {
        assertEquals(
                List.of(
                        ErrorCode.INVALID_REQUEST,
                        ErrorCode.UNSUPPORTED_CAPABILITY,
                        ErrorCode.SESSION_NOT_FOUND,
                        ErrorCode.SESSION_CLOSED,
                        ErrorCode.NOT_FOUND,
                        ErrorCode.STRICTNESS_VIOLATION,
                        ErrorCode.NOT_ACTIONABLE,
                        ErrorCode.TIMEOUT,
                        ErrorCode.RENDER_THREAD_FAILURE,
                        ErrorCode.valueOf("RENDER_THREAD_VIOLATION"),
                        ErrorCode.CAPTURE_FAILURE,
                        ErrorCode.LIMIT_EXCEEDED,
                        ErrorCode.PROTOCOL_VERSION_MISMATCH,
                        ErrorCode.INTERNAL_ERROR),
                List.of(ErrorCode.values()));
    }

    @Test void valueOfRejectsMisspelledRenderThreadViolation() {
        assertThrows(IllegalArgumentException.class,
                () -> ErrorCode.valueOf("RENDER_THREAD_VIOLATION_"));
        assertThrows(IllegalArgumentException.class,
                () -> ErrorCode.valueOf("render-thread-violation"));
    }
}
