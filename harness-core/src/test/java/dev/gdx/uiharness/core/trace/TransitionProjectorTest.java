package dev.gdx.uiharness.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TransitionProjectorTest {
    private final TransitionProjector projector = new TransitionProjector();
    private final StrictResolution locators = new StrictResolution();

    @Test void appearanceDisappearanceAndEnabledTransitionsAreClassified() {
        SemanticSnapshot before = snapshot(
                node("save", Role.BUTTON, "Save", "save", true, true, 10, 10),
                node("gone", Role.BUTTON, "Gone", "gone", true, true, 30, 10));
        SemanticSnapshot after = snapshot(
                node("save", Role.BUTTON, "Save", "save", true, false, 10, 10),
                node("new", Role.BUTTON, "New", "new", true, true, 50, 10));

        TransitionQueryResult result = projector.query(
                List.of(
                        new SemanticObservation(1, 1, 1, before, null),
                        new SemanticObservation(5, 2, 2, after, 4L)),
                query(null, Set.of()),
                locators);

        assertKind(result, TransitionKind.DISABLED, "test-id:save");
        assertKind(result, TransitionKind.APPEARED, "test-id:new");
        assertKind(result, TransitionKind.DISAPPEARED, "test-id:gone");
        assertEquals(4L, result.transitions().stream()
                .filter(transition -> transition.kind() == TransitionKind.DISABLED)
                .findFirst().orElseThrow().causeSequence());
        assertEquals(4L, result.transitions().stream()
                .filter(transition -> transition.actorIdentity().equals("test-id:new"))
                .findFirst().orElseThrow().causeSequence());
    }

    @Test void textFocusAndBoundsTransitionsReportBeforeAndAfterValues() {
        SemanticSnapshot before = snapshot(
                node("save", Role.BUTTON, "Save", "save", true, true, 10, 10));
        SemanticSnapshot after = snapshot(
                node("save", Role.BUTTON, "Saved", "save", true, true, 20, 10));

        TransitionQueryResult result = projector.query(
                List.of(
                        new SemanticObservation(1, 1, 1, before, null),
                        new SemanticObservation(2, 2, 2, after, 3L)),
                query(null, Set.of()),
                locators);

        assertKind(result, TransitionKind.TEXT_CHANGED, "test-id:save");
        assertKind(result, TransitionKind.BOUNDS_CHANGED, "test-id:save");
        StateTransition text = result.transitions().stream()
                .filter(transition -> transition.kind() == TransitionKind.TEXT_CHANGED)
                .findFirst().orElseThrow();
        assertEquals("Save", text.beforeValues().get("text"));
        assertEquals("Saved", text.afterValues().get("text"));
    }

    @Test void locatorAndKindFiltersBoundTheProjection() {
        SemanticSnapshot before = snapshot(
                node("a", Role.BUTTON, "A", "a", true, true, 10, 10),
                node("b", Role.BUTTON, "B", "b", true, true, 30, 10));
        SemanticSnapshot after = snapshot(
                node("a", Role.BUTTON, "A", "a", true, false, 10, 10),
                node("b", Role.BUTTON, "B", "b", true, true, 30, 10));

        TransitionQueryResult result = projector.query(
                List.of(
                        new SemanticObservation(1, 1, 1, before, null),
                        new SemanticObservation(2, 2, 2, after, null)),
                query(Locator.testId("b"), Set.of()),
                locators);

        assertTrue(result.transitions().stream()
                .noneMatch(transition -> transition.actorIdentity().equals("test-id:a")));
    }

    @Test void frameGapsAndUnknownCausesAreReported() {
        SemanticSnapshot before = snapshot(
                node("a", Role.BUTTON, "A", "a", true, true, 10, 10));
        SemanticSnapshot after = snapshot(
                node("a", Role.BUTTON, "A", "a", true, false, 10, 10));

        TransitionQueryResult result = projector.query(
                List.of(
                        new SemanticObservation(1, 1, 1, before, null),
                        new SemanticObservation(2, 5, 2, after, null)),
                query(null, Set.of()),
                locators);

        assertEquals(1, result.gapCount());
        assertTrue(result.transitions().stream()
                .anyMatch(transition -> transition.causeSequence() == null));
        assertTrue(result.unknownCauseCount() > 0);
    }

    @Test void identicalObservationsProduceNoTransitions() {
        SemanticSnapshot same = snapshot(
                node("a", Role.BUTTON, "A", "a", true, true, 10, 10));

        TransitionQueryResult result = projector.query(
                List.of(
                        new SemanticObservation(1, 1, 1, same, null),
                        new SemanticObservation(2, 2, 2, same, null)),
                query(null, Set.of()),
                locators);

        assertEquals(List.of(), result.transitions());
    }

    @Test void repeatedProjectionIsDeterministic() {
        SemanticSnapshot before = snapshot(
                node("a", Role.BUTTON, "A", "a", true, true, 10, 10));
        SemanticSnapshot after = snapshot(
                node("a", Role.BUTTON, "Changed", "a", true, true, 20, 10));
        List<SemanticObservation> observations = List.of(
                new SemanticObservation(1, 1, 1, before, null),
                new SemanticObservation(2, 2, 2, after, 7L));

        TransitionQueryResult first = projector.query(observations, query(null, Set.of()),
                locators);
        TransitionQueryResult second = projector.query(observations, query(null, Set.of()),
                locators);

        assertEquals(first.transitions(), second.transitions());
    }

    private static TransitionQuery query(Locator locator, Set<TransitionKind> kinds) {
        return new TransitionQuery("trace-1", locator, kinds, Set.of(),
                null, null, 128, 65_536);
    }

    private static void assertKind(
            TransitionQueryResult result, TransitionKind kind, String identity) {
        assertTrue(result.transitions().stream()
                        .anyMatch(transition -> transition.kind() == kind
                                && transition.actorIdentity().equals(identity)),
                "expected " + kind + " for " + identity + " in " + result.transitions());
    }

    private static SemanticNode node(
            String id, Role role, String name, String testId,
            boolean visible, boolean enabled, double x, double y) {
        Bounds bounds = new Bounds(x, y, 100, 50);
        SemanticState state = new SemanticState(
                visible, true, Optional.of(enabled), Optional.empty(), Optional.empty(),
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
