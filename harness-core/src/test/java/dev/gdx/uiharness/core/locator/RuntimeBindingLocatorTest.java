package dev.gdx.uiharness.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
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

final class RuntimeBindingLocatorTest {
    private final StrictResolution engine = new StrictResolution(HarnessLimits.defaults());

    @Test void entityLocatorSelectsOnlyExplicitlyBoundNodes() {
        SemanticSnapshot snapshot = snapshot(
                bound("hp-bar", "enemy-1", "health"),
                bound("mp-bar", "enemy-1", "mana"),
                unbound("title"));

        QueryResult result = engine.query(snapshot, Locator.entity("enemy-1"));

        assertEquals(2, result.matches().size());
        assertTrue(result.matches().stream().anyMatch(node -> node.testId().equals("hp-bar")));
        assertTrue(result.matches().stream().anyMatch(node -> node.testId().equals("mp-bar")));
    }

    @Test void entityPropertyLocatorSelectsOneBindingAndStaysStrict() {
        SemanticSnapshot snapshot = snapshot(
                bound("hp-bar", "enemy-1", "health"),
                bound("mp-bar", "enemy-1", "mana"));

        assertEquals("hp-bar", engine.resolveStrict(
                snapshot, Locator.entityProperty("enemy-1", "health")).testId());
        HarnessException missing = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(snapshot, Locator.entity("other-1")));
        assertEquals(ErrorCode.NOT_FOUND, missing.code());
        HarnessException multiple = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(snapshot, Locator.entity("enemy-1")));
        assertEquals(ErrorCode.STRICTNESS_VIOLATION, multiple.code());
    }

    @Test void bindingFieldsAreBoundedAndValidated() {
        RuntimeBinding binding = new RuntimeBinding(
                "enemy-1", "health", "int", "exact", "frame-42");
        assertEquals("enemy-1", binding.entityId());
        assertEquals("health", binding.propertyId());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeBinding("", "health", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeBinding("enemy-1", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeBinding("x".repeat(257), "health", null, null, null));
    }

    @Test void unboundNodesWorkWithoutAnyBindingMetadata() {
        SemanticSnapshot snapshot = snapshot(unbound("save"), unbound("cancel"));

        assertEquals("save", engine.resolveStrict(
                snapshot, Locator.testId("save")).testId());
        assertTrue(engine.query(snapshot, Locator.entity("anything")).matches().isEmpty());
    }

    private static SemanticSnapshot snapshot(SemanticNode... nodes) {
        SemanticNode root = new SemanticNode("root", null,
                java.util.stream.Stream.of(nodes).map(SemanticNode::id).toList(),
                Role.GROUP, "root", "", null, null, null, null,
                state(), new Bounds(0, 0, 800, 600), new Bounds(0, 0, 800, 600),
                new Bounds(0, 0, 800, 600), 0, Map.of());
        var byId = new LinkedHashMap<String, SemanticNode>();
        byId.put("root", root);
        for (SemanticNode node : nodes) {
            byId.put(node.id(), node);
        }
        return new SemanticSnapshot(1, 1, "root", byId);
    }

    private static SemanticNode bound(String id, String entityId, String propertyId) {
        return new SemanticNode(
                id, "root", List.of(), Role.PROGRESS_BAR, id, id, null, id, null, null,
                state(), new Bounds(0, 0, 100, 20), new Bounds(0, 0, 100, 20),
                new Bounds(0, 0, 100, 20), 0, Map.of(),
                new RuntimeBinding(entityId, propertyId, null, null, null));
    }

    private static SemanticNode unbound(String id) {
        return new SemanticNode(
                id, "root", List.of(), Role.BUTTON, id, id, null, id, null, null,
                state(), new Bounds(0, 0, 100, 50), new Bounds(0, 0, 100, 50),
                new Bounds(0, 0, 100, 50), 0, Map.of());
    }

    private static SemanticState state() {
        return new SemanticState(
                true, true, Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, true, 1.0, false, true, true);
    }
}
