package dev.gdx.uiharness.core.matrix;

import dev.gdx.uiharness.core.assertion.AssertionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure matrix expander. Computes the Cartesian product overflow-safely before any case is
 * emitted and rejects products above the configured case bound.
 */
public final class MatrixPlanner {
    /**
     * Expands one definition into deterministic bounded cases in window, scale, DPR, HiDPI,
     * locale, font-set order.
     *
     * @param definition immutable matrix definition
     * @param limits hard case bound
     * @return immutable bounded case list in deterministic order
     */
    public List<MatrixCase> plan(MatrixDefinition definition, MatrixLimits limits) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(limits, "limits");
        List<AssertionRequest> assertions = definition.assertions();
        List<String> fontSets = definition.fontSetIds().isEmpty()
                ? List.of("") : definition.fontSetIds();
        long product = 1;
        for (List<?> dimension : List.of(
                definition.windows(),
                definition.uiScales(),
                definition.devicePixelRatios(),
                definition.hiDpiModes(),
                definition.locales(),
                fontSets)) {
            product = multiplyExact(product, dimension.size());
        }
        if (product > limits.maxCases()) {
            throw new IllegalArgumentException(
                    "matrix product " + product + " exceeds the case bound "
                            + limits.maxCases());
        }
        var cases = new ArrayList<MatrixCase>((int) product);
        int index = 0;
        for (MatrixWindow window : definition.windows()) {
            for (Double uiScale : definition.uiScales()) {
                for (Double devicePixelRatio : definition.devicePixelRatios()) {
                    for (MatrixHiDpi hiDpiMode : definition.hiDpiModes()) {
                        for (String locale : definition.locales()) {
                            for (String fontSetId : fontSets) {
                                cases.add(new MatrixCase(
                                        index++,
                                        window,
                                        uiScale,
                                        devicePixelRatio,
                                        hiDpiMode,
                                        locale,
                                        fontSetId,
                                        (double) window.width() / window.height(),
                                        assertions));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(cases);
    }

    private static long multiplyExact(long first, long second) {
        long product = first * second;
        if (first != 0 && product / first != second) {
            throw new IllegalArgumentException("matrix product overflows the case bound");
        }
        return product;
    }
}
