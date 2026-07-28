package dev.gdx.uiharness.core.limits;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class HarnessLimitsTest {
    private final HarnessLimits limits =
            new HarnessLimits(2, 8, 10, 16_384, 1_048_576, Duration.ofSeconds(5));

    @Test void limitsRejectOversizedSnapshotBeforePublication() {
        HarnessException error =
                assertThrows(HarnessException.class, () -> limits.validateNodeCount(3));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, error.code());
        assertEquals("nodes", error.evidence().details().get("dimension"));
        assertEquals("3", error.evidence().details().get("actual"));
        assertEquals("2", error.evidence().details().get("limit"));
    }

    @Test void eachConfiguredLimitAcceptsBoundaryAndRejectsOverflow() {
        assertDoesNotThrow(() -> limits.validateNodeCount(2));
        assertDoesNotThrow(() -> limits.validateDepth(8));
        assertDoesNotThrow(() -> limits.validateMatchCount(10));
        assertDoesNotThrow(() -> limits.validateString("value", "field"));
        assertDoesNotThrow(() -> limits.validateSnapshotBytes(1_048_576));
        assertDoesNotThrow(() -> limits.validateDeadline(Duration.ofSeconds(5)));

        assertLimitExceeded(() -> limits.validateDepth(9));
        assertLimitExceeded(() -> limits.validateMatchCount(11));
        assertLimitExceeded(() -> limits.validateString("x".repeat(16_385), "field"));
        assertLimitExceeded(() -> limits.validateSnapshotBytes(1_048_577));
        assertLimitExceeded(() -> limits.validateDeadline(Duration.ofSeconds(5).plusNanos(1)));
    }

    @Test void invalidConfiguredLimitsAreRejectedPrecisely() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessLimits(0, 8, 10, 16_384, 1_048_576, Duration.ofSeconds(5)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessLimits(2, 8, 10, 16_384, 1_048_576, Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> new HarnessLimits(2, 8, 10, 16_384, 1_048_576, null));
    }

    @Test void negativeMeasurementsAreInvalidRatherThanLimitFailures() {
        assertThrows(IllegalArgumentException.class, () -> limits.validateNodeCount(-1));
        assertThrows(IllegalArgumentException.class, () -> limits.validateDepth(-1));
        assertThrows(IllegalArgumentException.class, () -> limits.validateMatchCount(-1));
        assertThrows(IllegalArgumentException.class, () -> limits.validateSnapshotBytes(-1));
        assertThrows(IllegalArgumentException.class,
                () -> limits.validateDeadline(Duration.ofNanos(-1)));
    }

    @Test void defaultsAreUsableAndBounded() {
        HarnessLimits defaults = HarnessLimits.defaults();

        assertDoesNotThrow(() -> defaults.validateNodeCount(1));
        assertDoesNotThrow(() -> defaults.validateDepth(1));
        assertDoesNotThrow(() -> defaults.validateMatchCount(1));
        assertDoesNotThrow(() -> defaults.validateString("value", "field"));
        assertDoesNotThrow(() -> defaults.validateSnapshotBytes(1));
        assertDoesNotThrow(() -> defaults.validateDeadline(Duration.ZERO));
    }

    private static void assertLimitExceeded(Runnable validation) {
        assertEquals(
                ErrorCode.LIMIT_EXCEEDED,
                assertThrows(HarnessException.class, validation::run).code());
    }
}
