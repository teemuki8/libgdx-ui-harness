package dev.gdx.uiharness.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SemanticSnapshotTest {
    @Test void snapshotRejectsMissingChildReference() {
        var root = node("root", null, List.of("missing"));

        assertThrows(IllegalArgumentException.class,
                () -> new SemanticSnapshot(1, 2, "root", Map.of("root", root)));
    }

    @Test void snapshotRejectsMissingParentReference() {
        var root = node("root", null, List.of());
        var orphan = node("orphan", "missing", List.of());

        assertThrows(IllegalArgumentException.class,
                () -> new SemanticSnapshot(
                        1, 2, "root", Map.of("root", root, "orphan", orphan)));
    }

    @Test void snapshotRejectsDisconnectedCycle() {
        var root = node("root", null, List.of());
        var first = node("first", "second", List.of("second"));
        var second = node("second", "first", List.of("first"));

        assertThrows(IllegalArgumentException.class,
                () -> new SemanticSnapshot(
                        1,
                        2,
                        "root",
                        Map.of("root", root, "first", first, "second", second)));
    }

    @Test void snapshotAndNodeCollectionsCannotBeMutated() {
        var children = new ArrayList<String>();
        children.add("child");
        var properties = new HashMap<String, String>();
        properties.put("source", "fixture");
        var root = node("root", null, children, properties);
        var child = node("child", "root", List.of());
        var source = new HashMap<String, SemanticNode>();
        source.put("root", root);
        source.put("child", child);

        var snapshot = new SemanticSnapshot(1, 2, "root", source);
        children.clear();
        properties.clear();
        source.clear();

        assertEquals(List.of("child"), snapshot.nodes().get("root").childIds());
        assertEquals(Map.of("source", "fixture"), snapshot.nodes().get("root").properties());
        assertEquals(2, snapshot.nodes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.nodes().put("other", child));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.nodes().get("root").childIds().add("other"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.nodes().get("root").properties().put("other", "value"));
    }

    @Test void stateDistinguishesUnsupportedFromFalse() {
        var unsupported = state(Optional.empty());
        var falseValue = state(Optional.of(false));

        assertEquals(Optional.empty(), unsupported.checked());
        assertEquals(Optional.of(false), falseValue.checked());
        assertFalse(falseValue.checked().orElseThrow());
    }

    @Test void boundsRequireFiniteCoordinatesAndNonNegativeExtents() {
        assertThrows(IllegalArgumentException.class,
                () -> new Bounds(Double.NaN, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Bounds(0, 0, -1, 1));
    }

    @Test void nodeRejectsStringsBeyondTheHardBound() {
        String oversized = "x".repeat(16_385);

        assertThrows(IllegalArgumentException.class,
                () -> new SemanticNode(
                        "root",
                        null,
                        List.of(),
                        Role.GENERIC,
                        oversized,
                        null,
                        null,
                        null,
                        null,
                        null,
                        state(Optional.empty()),
                        new Bounds(0, 0, 0, 0),
                        new Bounds(0, 0, 0, 0),
                        new Bounds(0, 0, 0, 0),
                        0,
                        Map.of()));
    }

    private static SemanticNode node(String id, String parentId, List<String> childIds) {
        return node(id, parentId, childIds, Map.of());
    }

    private static SemanticNode node(
            String id,
            String parentId,
            List<String> childIds,
            Map<String, String> properties) {
        var bounds = new Bounds(0, 0, 10, 10);
        return new SemanticNode(
                id,
                parentId,
                childIds,
                Role.GENERIC,
                "",
                "",
                null,
                null,
                null,
                null,
                state(Optional.empty()),
                bounds,
                bounds,
                bounds,
                0,
                properties);
    }

    private static SemanticState state(Optional<Boolean> checked) {
        return new SemanticState(
                true,
                true,
                Optional.of(true),
                checked,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                1.0,
                false,
                true,
                true);
    }
}
