package dev.gdx.uiharness.core.visual;

/** Backend implementation that compares bounded reference and current visual evidence. */
@FunctionalInterface
public interface VisualComparator {
    /** Produces deterministic measurements and ordered attributed differences. */
    Comparison compare(
            VisualReference reference, CurrentVisualEvidence current, VisualPolicy policy);

    /** Backend comparison output before orchestration assigns freshness/completion status. */
    record Comparison(
            VisualMetrics metrics,
            java.util.List<VisualDifference> differences,
            java.util.List<VisualRegion> regions,
            VisualHeatmap heatmap) {
        /** Defensively copies ordered differences. */
        public Comparison {
            java.util.Objects.requireNonNull(metrics, "metrics");
            differences = java.util.List.copyOf(
                    java.util.Objects.requireNonNull(differences, "differences"));
            regions = java.util.List.copyOf(
                    java.util.Objects.requireNonNull(regions, "regions"));
            if (differences.size() > 1_024 || regions.size() > 256) {
                throw new IllegalArgumentException("comparison evidence exceeds bounds");
            }
        }

        /** Compatibility constructor for comparators without spatial artifacts. */
        public Comparison(VisualMetrics metrics, java.util.List<VisualDifference> differences) {
            this(metrics, differences, java.util.List.of(), null);
        }
    }
}
