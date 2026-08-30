package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.utils.SnapshotArray;
import dev.gdx.uiharness.core.layout.LayoutValidationEvidence;
import dev.gdx.uiharness.core.layout.TextLayoutEvidence;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Extracts bounded immutable intrinsic Label geometry on the owning render thread. */
final class Scene2dTextLayoutExtractor {
    private static final int MAX_TEXT_NODES = 10_000;
    private static final int MAX_CLIP_ANCESTORS = 128;

    private final Stage stage;

    Scene2dTextLayoutExtractor(
            Stage stage, Semantics semantics, Scene2dSnapshotter snapshotter) {
        this.stage = Objects.requireNonNull(stage, "stage");
        Semantics checkedSemantics = Objects.requireNonNull(semantics, "semantics");
        Scene2dSnapshotter checkedSnapshotter =
                Objects.requireNonNull(snapshotter, "snapshotter");
        if (checkedSnapshotter.semantics() != checkedSemantics) {
            throw new IllegalArgumentException("snapshotter must use the supplied semantics");
        }
    }

    LayoutValidationEvidence extract(SemanticSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        IdentityHashMap<Actor, String> actorIds = actorIds();
        CoordinateMapper coordinates = new CoordinateMapper(stage);
        Map<String, TextLayoutEvidence> result = new LinkedHashMap<>();
        collect(stage.getRoot(), true, snapshot, actorIds, coordinates, result);
        return LayoutValidationEvidence.available(Map.copyOf(result));
    }

    private void collect(
            Actor actor,
            boolean ancestorsVisible,
            SemanticSnapshot snapshot,
            IdentityHashMap<Actor, String> actorIds,
            CoordinateMapper coordinates,
            Map<String, TextLayoutEvidence> result) {
        boolean visible = ancestorsVisible && actor.isVisible();
        if (result.size() >= MAX_TEXT_NODES) {
            return;
        }
        if (visible
                && actor instanceof Label label
                && label.getText().length() > 0) {
            String nodeId = actorIds.get(actor);
            SemanticNode node = snapshot.nodes().get(nodeId);
            if (node != null) {
                label.validate();
                Scene2dTextGeometry.Placement placement =
                        Scene2dTextGeometry.placement(label);
                TextLayoutEvidence observed = new TextLayoutEvidence(
                        node.id(),
                        stageBounds(coordinates.typographyBounds(
                                label, placement.layoutBounds(), 1.0, 1.0)),
                        stageBounds(coordinates.typographyBounds(
                                label, placement.inkBounds(), 1.0, 1.0)),
                        clipStageBounds(label, coordinates));
                result.put(node.id(), observed);
            }
        }
        if (actor instanceof Group group) {
            SnapshotArray<Actor> children = group.getChildren();
            for (int index = 0; index < children.size; index++) {
                collect(
                        children.get(index),
                        visible,
                        snapshot,
                        actorIds,
                        coordinates,
                        result);
            }
        }
    }

    private static List<Bounds> clipStageBounds(
            Label label, CoordinateMapper coordinates) {
        List<Bounds> result = new ArrayList<>();
        for (Actor ancestor = label.getParent();
                ancestor != null && result.size() < MAX_CLIP_ANCESTORS;
                ancestor = ancestor.getParent()) {
            if (ancestor instanceof ScrollPane) {
                result.add(stageBounds(coordinates.typographyBounds(
                        ancestor, coordinates.localBounds(ancestor), 1.0, 1.0)));
            }
        }
        return List.copyOf(result);
    }

    private static Bounds stageBounds(List<CoordinateBounds> mapped) {
        CoordinateBounds value = mapped.stream()
                .filter(bounds -> bounds.space() == CoordinateSpace.STAGE)
                .findFirst()
                .orElseThrow();
        return new Bounds(value.x(), value.y(), value.width(), value.height());
    }

    private IdentityHashMap<Actor, String> actorIds() {
        IdentityHashMap<Actor, String> result = new IdentityHashMap<>();
        collectIds(stage.getRoot(), result);
        return result;
    }

    private static void collectIds(Actor actor, IdentityHashMap<Actor, String> result) {
        result.put(actor, "n" + result.size());
        if (actor instanceof Group group) {
            SnapshotArray<Actor> children = group.getChildren();
            for (int index = 0; index < children.size; index++) {
                collectIds(children.get(index), result);
            }
        }
    }
}
