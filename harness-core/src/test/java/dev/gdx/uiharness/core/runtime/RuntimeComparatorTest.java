package dev.gdx.uiharness.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.RuntimeBinding;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RuntimeComparatorTest {
    private final StrictResolution locators = new StrictResolution();

    @Test void equalTypedValuesWithMatchingDeclaredFormatReportEqual() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("100", "integer"));
        assertEquals(DisplayedRuntimeComparison.Status.EQUAL,
                comparator.compare(snapshot("100", format("integer")),
                        Locator.testId("health"), locators).status());
    }

    @Test void incompatibleDeclaredAndRuntimeFormatsCannotReportEqual() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("100", "integer"));
        DisplayedRuntimeComparison result = comparator.compare(
                snapshot("100", format("string")), Locator.testId("health"), locators);

        assertEquals(DisplayedRuntimeComparison.Status.AMBIGUOUS, result.status());
        assertEquals("value-format-mismatch", result.details().get("reason"));
        assertEquals("string", result.details().get("declaredFormat"));
        assertEquals("integer", result.details().get("runtimeFormat"));
    }

    @Test void declaredFormatWithMissingRuntimeFormatCannotReportEqual() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("100", null));
        DisplayedRuntimeComparison result = comparator.compare(
                snapshot("100", format("integer")), Locator.testId("health"), locators);

        assertEquals(DisplayedRuntimeComparison.Status.AMBIGUOUS, result.status());
        assertEquals("value-format-mismatch", result.details().get("reason"));
        assertEquals("integer", result.details().get("declaredFormat"));
        assertEquals("", result.details().get("runtimeFormat"));
    }

    @Test void valueDesynchronizationReportsMismatchOnCorrelatedFrames() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("50", "integer"));
        assertEquals(DisplayedRuntimeComparison.Status.MISMATCH,
                comparator.compare(snapshot("100", format("integer")),
                        Locator.testId("health"), locators).status());
    }

    @Test void undeclaredBindingFormatRetainsTextualEquality() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("100", "integer"));
        assertEquals(DisplayedRuntimeComparison.Status.EQUAL,
                comparator.compare(snapshot("100", format(null)),
                        Locator.testId("health"), locators).status());
    }

    private static RuntimeObservationSource observation(String value, String runtimeFormat) {
        return binding -> Optional.of(new RuntimeObservation(
                "enemy-1", "health", 10, 10, value, runtimeFormat));
    }

    private static RuntimeBinding format(String declaredFormat) {
        return new RuntimeBinding(
                "enemy-1", "health", declaredFormat, null, "frame-1");
    }

    private static SemanticSnapshot snapshot(String displayed, RuntimeBinding binding) {
        Bounds bounds = new Bounds(0, 0, 100, 50);
        SemanticState state = new SemanticState(
                true, true, Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, true, 1.0, false, true, true);
        SemanticNode node = new SemanticNode(
                "n1", "root", List.of(), Role.GROUP, "Health", displayed, null,
                "health", null, "Label", state, bounds, bounds, bounds, 0, Map.of(), binding);
        SemanticNode root = new SemanticNode(
                "root", null, List.of("n1"), Role.GROUP, "root", "", null, null,
                null, null, state, bounds, bounds, bounds, 0, Map.of());
        var byId = new LinkedHashMap<String, SemanticNode>();
        byId.put("root", root);
        byId.put("n1", node);
        return new SemanticSnapshot(1, 10, "root", byId);
    }
}
