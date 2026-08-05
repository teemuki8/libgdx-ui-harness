package dev.gdx.uiharness.core.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.assertion.AssertionRequest;
import dev.gdx.uiharness.core.assertion.UiAssertion;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MatrixPlannerTest {
    private final MatrixPlanner planner = new MatrixPlanner();
    private final MatrixLimits limits = MatrixLimits.defaults();

    @Test void cartesianProductIsDeterministicAndDerivesAspectRatio() {
        MatrixDefinition definition = new MatrixDefinition(
                1,
                "navigation",
                List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                List.of(1.0, 2.0),
                List.of(1.0),
                List.of(MatrixHiDpi.LOGICAL),
                List.of("en"),
                List.of(),
                List.of());

        List<MatrixCase> cases = planner.plan(definition, limits);

        assertEquals(4, cases.size());
        assertEquals(0, cases.get(0).index());
        assertEquals(new MatrixWindow(1280, 720), cases.get(0).window());
        assertEquals(1.0, cases.get(0).uiScale());
        assertEquals(16.0 / 9.0, cases.get(0).aspectRatio(), 1e-9);
        assertEquals(16.0 / 9.0, cases.get(1).aspectRatio(), 1e-9);
        assertEquals(2.0, cases.get(1).uiScale());
        assertEquals(1920, cases.get(2).window().width());
        assertEquals(cases.get(3).index(), cases.size() - 1);
    }

    @Test void productLimitIsRejectedBeforeExecution() {
        MatrixDefinition definition = new MatrixDefinition(
                1,
                "navigation",
                List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                List.of(1.0, 2.0, 3.0),
                List.of(1.0, 2.0),
                List.of(MatrixHiDpi.LOGICAL, MatrixHiDpi.PIXELS),
                List.of("en"),
                List.of(),
                List.of());
        MatrixLimits tight = MatrixLimits.builder().maxCases(10).build();

        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(definition, tight));
    }

    @Test void largeBoundedProductIsRejectedAsALimitViolation() {
        MatrixDefinition definition = new MatrixDefinition(
                1,
                "navigation",
                List.of(new MatrixWindow(1280, 720)),
                java.util.stream.IntStream.rangeClosed(1, 64)
                        .mapToDouble(value -> value).boxed().toList(),
                java.util.stream.IntStream.rangeClosed(1, 64)
                        .mapToDouble(value -> value).boxed().toList(),
                List.of(MatrixHiDpi.LOGICAL, MatrixHiDpi.PIXELS),
                List.of("en", "fi", "sv"),
                List.of(),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> planner.plan(definition, limits));
    }

    @Test void duplicateAndContradictoryDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MatrixDefinition(
                1, "navigation",
                List.of(new MatrixWindow(1280, 720), new MatrixWindow(1280, 720)),
                List.of(1.0), List.of(1.0), List.of(MatrixHiDpi.LOGICAL),
                List.of("en"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MatrixWindow(0, 720));
        assertThrows(IllegalArgumentException.class, () -> new MatrixDefinition(
                2, "navigation",
                List.of(new MatrixWindow(1280, 720)),
                List.of(1.0), List.of(1.0), List.of(MatrixHiDpi.LOGICAL),
                List.of("en"), List.of(), List.of()));
    }

    @Test void emptyWindowDimensionProducesZeroCasesWithoutOverflow() {
        MatrixDefinition definition = new MatrixDefinition(
                1, "navigation",
                List.of(),
                List.of(1.0), List.of(1.0), List.of(MatrixHiDpi.LOGICAL),
                List.of("en"), List.of(), List.of());

        assertEquals(List.of(), planner.plan(definition, limits));
    }

    @Test void assertionsAreCarriedPerCaseAndCopiedImmutably() {
        AssertionRequest assertion = new AssertionRequest(1,
                dev.gdx.uiharness.core.locator.Locator.testId("save"),
                new UiAssertion.Visible(),
                dev.gdx.uiharness.core.time.Deadline.after(
                        () -> 0L, Duration.ofSeconds(1)));
        MatrixDefinition definition = new MatrixDefinition(
                1, "navigation",
                List.of(new MatrixWindow(1280, 720)),
                List.of(1.0), List.of(1.0), List.of(MatrixHiDpi.LOGICAL),
                List.of("en"), List.of(),
                List.of(assertion));

        MatrixCase single = planner.plan(definition, limits).getFirst();

        assertEquals(List.of(assertion), single.assertions());
        assertThrows(UnsupportedOperationException.class,
                () -> single.assertions().add(assertion));
    }
}
