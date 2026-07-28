package dev.gdx.uiharness.benchmarks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fixed benchmark statistics and V1 semantic-parity thresholds. */
public final class Statistics {
    private static final double TWO_SIDED_95_PERCENT_Z = 1.959963984540054;
    private static final double RATE_EPSILON = 1.0e-12;

    private Statistics() {}

    /**
     * Computes the two-sided 95% Wilson score interval for a binomial proportion.
     * With no observations the only honest interval is the complete probability range.
     */
    public static WilsonInterval wilsonInterval(int successes, int samples) {
        if (samples < 0 || successes < 0 || successes > samples) {
            throw new IllegalArgumentException(
                    "successes must be between zero and non-negative samples");
        }
        if (samples == 0) {
            return new WilsonInterval(0.0, 1.0, 0.0);
        }

        double observed = (double) successes / samples;
        double zSquared = TWO_SIDED_95_PERCENT_Z * TWO_SIDED_95_PERCENT_Z;
        double denominator = 1.0 + zSquared / samples;
        double center = (observed + zSquared / (2.0 * samples)) / denominator;
        double radius = TWO_SIDED_95_PERCENT_Z
                * Math.sqrt((observed * (1.0 - observed) + zSquared / (4.0 * samples))
                        / samples)
                / denominator;
        double lower = successes == 0 ? 0.0 : Math.max(0.0, center - radius);
        double upper = successes == samples ? 1.0 : Math.min(1.0, center + radius);
        return new WilsonInterval(lower, upper, observed);
    }

    /** Evaluates the fixed V1 threshold without considering median tool calls. */
    public static ParityVerdict meetsParity(
            BenchmarkResult harness, BenchmarkResult playwright) {
        Objects.requireNonNull(harness, "harness");
        Objects.requireNonNull(playwright, "playwright");
        ArrayList<String> failures = new ArrayList<>();

        if (harness.completionRate() + RATE_EPSILON < playwright.completionRate()) {
            failures.add("harness completion rate is below Playwright");
        }
        if (harness.actionableEvidenceRate() + RATE_EPSILON
                < playwright.actionableEvidenceRate()) {
            failures.add("harness actionable-evidence rate is below Playwright");
        }

        WilsonInterval playwrightFailures = wilsonInterval(
                playwright.timeoutOrFlakyRuns(), playwright.totalRuns());
        if (harness.timeoutOrFlakyRate() > playwrightFailures.upper() + RATE_EPSILON) {
            failures.add("harness timeout/flaky rate exceeds the Playwright rate plus "
                    + "its two-sided 95% Wilson upper tolerance");
        }
        return new ParityVerdict(failures.isEmpty(), failures, playwrightFailures);
    }

    /** Wilson bounds plus the observed proportion used to derive the upper tolerance. */
    public record WilsonInterval(double lower, double upper, double observed) {
        /** Validates an ordered probability interval and finite observation. */
        public WilsonInterval {
            if (!Double.isFinite(lower) || !Double.isFinite(upper)
                    || !Double.isFinite(observed)
                    || lower < 0.0 || upper > 1.0 || lower > upper
                    || observed < 0.0 || observed > 1.0) {
                throw new IllegalArgumentException("invalid Wilson interval");
            }
        }

        /** Amount added to the observed Playwright failure rate by the fixed threshold. */
        public double upperTolerance() {
            return upper - observed;
        }
    }

    /** Immutable threshold outcome and machine-readable diagnostic detail. */
    public record ParityVerdict(
            boolean passed, List<String> failures, WilsonInterval playwrightFailureWilson) {
        /** Defensively copies failure messages. */
        public ParityVerdict {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            Objects.requireNonNull(playwrightFailureWilson, "playwrightFailureWilson");
            if (passed != failures.isEmpty()) {
                throw new IllegalArgumentException("passed must match whether failures are empty");
            }
        }
    }
}
