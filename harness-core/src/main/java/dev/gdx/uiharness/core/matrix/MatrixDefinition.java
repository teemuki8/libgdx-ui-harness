package dev.gdx.uiharness.core.matrix;

import dev.gdx.uiharness.core.assertion.AssertionRequest;
import java.util.List;
import java.util.Objects;

/**
 * Immutable display matrix definition. Width and height are authoritative; every other
 * dimension is an independent requested axis.
 */
public record MatrixDefinition(
        int schemaVersion,
        String scenarioId,
        List<MatrixWindow> windows,
        List<Double> uiScales,
        List<Double> devicePixelRatios,
        List<MatrixHiDpi> hiDpiModes,
        List<String> locales,
        List<String> fontSetIds,
        List<AssertionRequest> assertions) {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_DIMENSION_ENTRIES = 64;
    private static final int MAX_ASSERTIONS = 256;

    /** Validates the version, scenario identity, and bounded dimension lists. */
    public MatrixDefinition {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported matrix schema version: " + schemaVersion);
        }
        Objects.requireNonNull(scenarioId, "scenarioId");
        if (scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be blank");
        }
        windows = distinct(requireBounded(windows, "windows", MAX_DIMENSION_ENTRIES));
        uiScales = distinct(requireBoundedPositive(uiScales, "uiScales"));
        devicePixelRatios =
                distinct(requireBoundedPositive(devicePixelRatios, "devicePixelRatios"));
        hiDpiModes = distinct(requireBounded(hiDpiModes, "hiDpiModes", MAX_DIMENSION_ENTRIES));
        locales = distinct(requireBounded(locales, "locales", MAX_DIMENSION_ENTRIES));
        fontSetIds = distinct(requireBounded(fontSetIds, "fontSetIds", MAX_DIMENSION_ENTRIES));
        assertions = List.copyOf(requireBounded(
                assertions, "assertions", MAX_ASSERTIONS));
    }

    private static <T> List<T> requireBounded(List<T> values, String name, int maximum) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " entries");
        }
        return values;
    }

    private static List<Double> requireBoundedPositive(List<Double> values, String name) {
        List<Double> bounded = requireBounded(values, name, 64);
        for (Double value : bounded) {
            if (value == null || !Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must contain finite positive values");
            }
        }
        return bounded;
    }

    private static <T> List<T> distinct(List<T> values) {
        List<T> copied = List.copyOf(values);
        if (copied.stream().distinct().count() != copied.size()) {
            throw new IllegalArgumentException("matrix dimension contains duplicates");
        }
        return copied;
    }
}
