package dev.gdx.uiharness.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class StatisticsTest {
    private static final double EPSILON = 1.0e-6;

    @Test void wilsonIntervalHandlesNoSamplesWithoutNaN() {
        Statistics.WilsonInterval interval = Statistics.wilsonInterval(0, 0);

        assertEquals(0.0, interval.lower(), EPSILON);
        assertEquals(1.0, interval.upper(), EPSILON);
        assertEquals(1.0, interval.upperTolerance(), EPSILON);
    }

    @Test void wilsonIntervalHandlesZeroSuccesses() {
        Statistics.WilsonInterval interval = Statistics.wilsonInterval(0, 20);

        assertEquals(0.0, interval.lower(), EPSILON);
        assertEquals(0.161125, interval.upper(), EPSILON);
        assertEquals(interval.upper(), interval.upperTolerance(), EPSILON);
    }

    @Test void wilsonZeroSuccessesKeepExactMathematicalLowerBoundary() {
        Statistics.WilsonInterval interval = Statistics.wilsonInterval(0, 200);

        assertEquals(0.0, interval.lower());
    }

    @Test void wilsonIntervalHandlesOneSmallSample() {
        Statistics.WilsonInterval interval = Statistics.wilsonInterval(1, 1);

        assertEquals(0.206549, interval.lower(), EPSILON);
        assertEquals(1.0, interval.upper(), EPSILON);
        assertEquals(0.0, interval.upperTolerance(), EPSILON);
    }

    @Test void wilsonIntervalRejectsImpossibleCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> Statistics.wilsonInterval(-1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> Statistics.wilsonInterval(21, 20));
        assertThrows(IllegalArgumentException.class,
                () -> Statistics.wilsonInterval(0, -1));
    }

    @Test void resultRatesAreDerivedFromImmutableCounts() {
        BenchmarkResult result = result(18, 20, 2, 0.95, 8);

        assertEquals(0.9, result.completionRate(), EPSILON);
        assertEquals(0.1, result.timeoutOrFlakyRate(), EPSILON);
        assertEquals(0.95, result.actionableEvidenceRate(), EPSILON);
        assertEquals(8.0, result.medianToolCalls(), EPSILON);
    }

    @Test void resultRejectsInconsistentCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkResult(21, 20, 0, 20, 8, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkResult(20, 20, 21, 20, 8, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkResult(20, 20, 0, 21, 8, 0));
    }

    @Test void failsParityWhenHarnessCompletionFallsBelowPlaywright() {
        BenchmarkResult harness = result(18, 20, 1, 0.95, 8);
        BenchmarkResult playwright = result(20, 20, 0, 1.00, 7);

        Statistics.ParityVerdict verdict = Statistics.meetsParity(harness, playwright);

        assertFalse(verdict.passed());
        assertTrue(verdict.failures().stream().anyMatch(value -> value.contains("completion")));
    }

    @Test void failsParityWhenHarnessEvidenceFallsBelowPlaywright() {
        BenchmarkResult harness = result(20, 20, 0, 0.95, 6);
        BenchmarkResult playwright = result(20, 20, 0, 1.00, 7);

        Statistics.ParityVerdict verdict = Statistics.meetsParity(harness, playwright);

        assertFalse(verdict.passed());
        assertTrue(verdict.failures().stream().anyMatch(value -> value.contains("actionable")));
    }

    @Test void timeoutRateAtPlaywrightWilsonUpperBoundIsAccepted() {
        BenchmarkResult harness = result(20, 20, 3, 1.00, 6);
        BenchmarkResult playwright = result(20, 20, 0, 1.00, 7);

        assertTrue(Statistics.meetsParity(harness, playwright).passed());
    }

    @Test void timeoutRateAbovePlaywrightWilsonUpperBoundFails() {
        BenchmarkResult harness = result(20, 20, 4, 1.00, 6);
        BenchmarkResult playwright = result(20, 20, 0, 1.00, 7);

        Statistics.ParityVerdict verdict = Statistics.meetsParity(harness, playwright);

        assertFalse(verdict.passed());
        assertTrue(verdict.failures().stream().anyMatch(value -> value.contains("timeout")));
    }

    @Test void acceptsEqualCompletionAndDiagnosticsWithoutFlakeIncrease() {
        BenchmarkResult harness = result(20, 20, 0, 1.00, 6);
        BenchmarkResult playwright = result(20, 20, 0, 1.00, 7);

        Statistics.ParityVerdict verdict = Statistics.meetsParity(harness, playwright);

        assertTrue(verdict.passed());
        assertTrue(verdict.failures().isEmpty());
    }

    @Test void medianCallsAreReportedButDoNotFailParity() {
        BenchmarkResult harness = result(20, 20, 0, 1.00, 100);
        BenchmarkResult playwright = result(20, 20, 0, 1.00, 1);

        assertTrue(Statistics.meetsParity(harness, playwright).passed());
    }

    private static BenchmarkResult result(
            int completed, int total, int timeoutOrFlaky, double evidenceRate,
            double medianCalls) {
        return new BenchmarkResult(completed, total, timeoutOrFlaky,
                (int) Math.round(evidenceRate * total), medianCalls, 0);
    }
}
