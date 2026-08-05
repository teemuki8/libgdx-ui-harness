package dev.gdx.uiharness.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SemanticComparatorTest {
    private final SemanticComparator comparator = new SemanticComparator();
    private final SemanticComparePolicy policy = SemanticComparePolicy.defaults();

    @Test void identicalBaselineAndSnapshotMatchByTestIdAndSurviveNodeIdChanges() {
        SemanticBaseline baseline = baseline("save-golden",
                new BaselineNode(Role.GROUP, "root", null, null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, Map.of(), List.of(
                                new BaselineNode(Role.BUTTON, "Save", null, null, "save",
                                        null, null, true, true, null, null, null, null,
                                        null, null, new Bounds(10, 10, 100, 50), null,
                                        Map.of(), List.of()))));
        SemanticSnapshot snapshot = snapshot(
                node("reconstructed-id", Role.BUTTON, "Save", "save", true, new Bounds(10, 10, 100, 50)));

        SemanticCompareResult result = comparator.compare(baseline, snapshot, policy);

        assertTrue(result.matched());
        assertTrue(result.differences().isEmpty());
    }

    @Test void addedRemovedAndChangedAreClassifiedSeparately() {
        SemanticBaseline baseline = baseline("diff",
                new BaselineNode(Role.GROUP, "root", null, null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, Map.of(), List.of(
                                new BaselineNode(Role.BUTTON, "Save", null, null, "save",
                                        null, null, null, true, null, null, null, null,
                                        null, null, null, null, Map.of(), List.of()),
                                new BaselineNode(Role.BUTTON, "Gone", null, null, "gone",
                                        null, null, null, null, null, null, null, null,
                                        null, null, null, null, Map.of(), List.of()))));
        SemanticSnapshot snapshot = snapshot(
                node("n1", Role.BUTTON, "Save", "save", true, new Bounds(10, 10, 100, 50)),
                node("n2", Role.BUTTON, "New", "new", true, new Bounds(20, 20, 100, 50)));

        SemanticCompareResult result = comparator.compare(baseline, snapshot, policy);

        assertFalse(result.matched());
        assertTrue(result.differences().stream()
                .anyMatch(difference -> difference.kind() == SemanticDifference.Kind.REMOVED
                        && difference.baselineKey().equals("test-id:gone")));
        assertTrue(result.differences().stream()
                .anyMatch(difference -> difference.kind() == SemanticDifference.Kind.ADDED
                        && difference.baselineKey().equals("test-id:new")));
    }

    @Test void changedNodeReportsPropertyPathsAndValues() {
        SemanticBaseline baseline = baseline("changed",
                new BaselineNode(Role.BUTTON, "Save", null, null, "save", null, null,
                        true, true, null, null, null, null, null, null,
                        null, null, Map.of(), List.of()));
        SemanticSnapshot snapshot = snapshot(
                node("n1", Role.BUTTON, "Save", "save", false, new Bounds(10, 10, 100, 50)));

        SemanticCompareResult result = comparator.compare(baseline, snapshot, policy);

        SemanticDifference changed = result.differences().stream()
                .filter(difference -> difference.kind() == SemanticDifference.Kind.CHANGED)
                .findFirst().orElseThrow();
        assertTrue(changed.propertyPaths().contains("visible"));
        assertEquals("true", changed.beforeValues().get("visible"));
        assertEquals("false", changed.afterValues().get("visible"));
    }

    @Test void duplicateKeysAreAmbiguousNeverHeuristicallyPaired() {
        SemanticBaseline baseline = baseline("ambiguous",
                new BaselineNode(Role.BUTTON, null, null, null, "dup", null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, Map.of(), List.of()));
        SemanticSnapshot snapshot = snapshot(
                node("a", Role.BUTTON, "First", "dup", true, new Bounds(10, 10, 100, 50)),
                node("b", Role.BUTTON, "Second", "dup", true, new Bounds(120, 10, 100, 50)));

        SemanticCompareResult result = comparator.compare(baseline, snapshot, policy);

        assertTrue(result.differences().stream()
                .anyMatch(difference -> difference.kind() == SemanticDifference.Kind.AMBIGUOUS
                        && difference.ambiguousIdentities().size() == 2));
        assertFalse(result.matched());
    }

    @Test void positionalToleranceAppliesOnlyInItsNamedSpace() {
        PositionalTolerance tolerance = new PositionalTolerance(
                "viewport-10", CoordinateSpace.STAGE, "pixels", 10, 10, 0, 0);
        SemanticComparePolicy tolerant = new SemanticComparePolicy(
                List.of(tolerance), java.util.Set.of(), 256, 16_384);
        SemanticBaseline baseline = baseline("bounds",
                new BaselineNode(Role.GROUP, "root", null, null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, Map.of(), List.of(
                                new BaselineNode(Role.BUTTON, "Save", null, null, "save",
                                        null, null, null, null, null, null, null, null,
                                        null, null, new Bounds(10, 10, 100, 50), null,
                                        Map.of(), List.of()))));
        SemanticSnapshot moved = snapshot(
                node("n1", Role.BUTTON, "Save", "save", true, new Bounds(18, 12, 100, 50)));

        assertTrue(comparator.compare(baseline, moved, tolerant).matched());
        SemanticSnapshot far = snapshot(
                node("n1", Role.BUTTON, "Save", "save", true, new Bounds(30, 30, 100, 50)));
        assertFalse(comparator.compare(baseline, far, tolerant).matched());
    }

    @Test void identityFieldsCannotBeExcluded() {
        assertThrows(IllegalArgumentException.class, () -> new SemanticComparePolicy(
                List.of(), java.util.Set.of("testId"), 256, 16_384));
    }

    @Test void unknownMajorVersionFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticBaseline(2, 0, "future",
                        new BaselineNode(Role.GROUP, "root", null, null, null, null, null,
                                null, null, null, null, null, null, null, null,
                                null, null, Map.of(), List.of()),
                        false));
    }

    @Test void repeatedComparisonIsDeterministic() {
        SemanticBaseline baseline = baseline("deterministic",
                new BaselineNode(Role.BUTTON, "Save", null, null, "save", null, null,
                        null, true, null, null, null, null, null, null,
                        null, null, Map.of(), List.of()));
        SemanticSnapshot snapshot = snapshot(
                node("n1", Role.BUTTON, "Save", "save", false, new Bounds(10, 10, 100, 50)),
                node("n2", Role.BUTTON, "New", "new", true, new Bounds(20, 20, 100, 50)));

        SemanticCompareResult first = comparator.compare(baseline, snapshot, policy);
        SemanticCompareResult second = comparator.compare(baseline, snapshot, policy);

        assertEquals(first.differences(), second.differences());
    }

    private static SemanticBaseline baseline(String id, BaselineNode root) {
        return new SemanticBaseline(1, 0, id, root, false);
    }

    private static SemanticNode node(
            String id, Role role, String name, String testId, boolean visible, Bounds bounds) {
        SemanticState state = new SemanticState(
                visible, true, Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, true, 1.0, false, true, true);
        return new SemanticNode(
                id, "root", List.of(), role, name, name, null, testId, null, null,
                state, bounds, bounds, bounds, 0, Map.of());
    }

    private static SemanticSnapshot snapshot(SemanticNode... nodes) {
        SemanticNode root = new SemanticNode("root", null,
                java.util.stream.Stream.of(nodes).map(SemanticNode::id).toList(),
                Role.GROUP, "root", "", null, null, null, null,
                new SemanticState(true, false, Optional.of(true), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                        1.0, false, true, true),
                new Bounds(0, 0, 800, 600), new Bounds(0, 0, 800, 600),
                new Bounds(0, 0, 800, 600), 0, Map.of());
        var byId = new LinkedHashMap<String, SemanticNode>();
        byId.put("root", root);
        for (SemanticNode node : nodes) {
            byId.put(node.id(), node);
        }
        return new SemanticSnapshot(1, 1, "root", byId);
    }
}
