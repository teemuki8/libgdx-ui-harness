package dev.gdx.uiharness.benchmarks;

/** Immutable aggregate of raw benchmark run records for one system. */
public record BenchmarkResult(
        int completedRuns,
        int totalRuns,
        int timeoutOrFlakyRuns,
        int actionableEvidenceRuns,
        double medianToolCalls,
        long traceBytes) {

    /** Validates that every aggregate count can have arisen from {@code totalRuns}. */
    public BenchmarkResult {
        if (totalRuns <= 0) {
            throw new IllegalArgumentException("totalRuns must be positive");
        }
        requireCount("completedRuns", completedRuns, totalRuns);
        requireCount("timeoutOrFlakyRuns", timeoutOrFlakyRuns, totalRuns);
        requireCount("actionableEvidenceRuns", actionableEvidenceRuns, totalRuns);
        if (!Double.isFinite(medianToolCalls) || medianToolCalls < 0.0) {
            throw new IllegalArgumentException("medianToolCalls must be finite and non-negative");
        }
        if (traceBytes < 0) {
            throw new IllegalArgumentException("traceBytes must be non-negative");
        }
    }

    /** Returns the observed completion proportion. */
    public double completionRate() {
        return proportion(completedRuns);
    }

    /** Returns the observed timeout-or-flaky-failure proportion. */
    public double timeoutOrFlakyRate() {
        return proportion(timeoutOrFlakyRuns);
    }

    /** Returns the observed actionable-evidence proportion. */
    public double actionableEvidenceRate() {
        return proportion(actionableEvidenceRuns);
    }

    private double proportion(int count) {
        return (double) count / totalRuns;
    }

    private static void requireCount(String name, int count, int total) {
        if (count < 0 || count > total) {
            throw new IllegalArgumentException(name + " must be between zero and totalRuns");
        }
    }
}
