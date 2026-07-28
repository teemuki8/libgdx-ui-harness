package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.utils.SnapshotArray;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Extracts immutable, backend-neutral semantic snapshots from live Scene2D stages. */
public final class Scene2dSnapshotter {

    private final HarnessLimits limits;
    private final Semantics semantics;
    private final ActorAdapterRegistry adapters;

    /** Creates a snapshotter with default limits and built-in widget adapters. */
    public Scene2dSnapshotter() {
        this(HarnessLimits.defaults());
    }

    /** Creates a snapshotter with explicit publication limits. */
    public Scene2dSnapshotter(HarnessLimits limits) {
        this(limits, new Semantics(() -> true), new ActorAdapterRegistry());
    }

    Scene2dSnapshotter(
            HarnessLimits limits, Semantics semantics, ActorAdapterRegistry adapters) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    /** Returns the metadata facade used by this snapshotter. */
    public Semantics semantics() {
        return semantics;
    }

    /** Returns the class-dispatched adapter registry used by this snapshotter. */
    public ActorAdapterRegistry adapters() {
        return adapters;
    }

    /** Captures one validated immutable semantic snapshot. */
    public SemanticSnapshot snapshot(Stage stage, long revision, long frame) {
        Objects.requireNonNull(stage, "stage");
        CoordinateMapper coordinates = new CoordinateMapper(stage);
        List<NodeFrame> frames = new ArrayList<>();
        IdentityHashMap<Actor, String> ids = new IdentityHashMap<>();
        Bounds stageViewport = coordinates.stageViewportBounds();
        collect(
                stage.getRoot(),
                null,
                0,
                0,
                true,
                1.0,
                true,
                null,
                coordinates,
                frames,
                ids);

        Map<String, SemanticNode> nodes = new LinkedHashMap<>(frames.size());
        long estimatedBytes = 32;
        for (NodeFrame nodeFrame : frames) {
            Actor actor = nodeFrame.actor();
            Bounds localBounds = coordinates.localBounds(actor);
            Bounds stageBounds = coordinates.stageBounds(actor);
            Bounds screenBounds = coordinates.screenBounds(actor);
            SemanticNodeBuilder builder = new SemanticNodeBuilder();
            if (actor instanceof Group) {
                builder.role(Role.GROUP);
            }
            adapters.contribute(actor, builder);
            builder.apply(semantics.metadata(actor));
            validateBuilder(builder);

            boolean viewportIntersecting = intersects(stageBounds, stageViewport)
                    && (nodeFrame.clip() == null || intersects(stageBounds, nodeFrame.clip()));
            boolean clipped = nodeFrame.clip() != null
                    && !contains(nodeFrame.clip(), stageBounds);
            boolean touchable = nodeFrame.ancestorsTouchable()
                    && actor.getTouchable() == Touchable.enabled;
            boolean visible = nodeFrame.ancestorsVisible() && actor.isVisible();
            double effectiveAlpha = clampAlpha(
                    nodeFrame.ancestorAlpha() * actor.getColor().a);
            boolean focused = stage.getKeyboardFocus() == actor || stage.getScrollFocus() == actor;
            boolean hitTarget = visible
                    && touchable
                    && viewportIntersecting
                    && isHitTarget(stage, actor, coordinates);
            SemanticState state = new SemanticState(
                    visible,
                    touchable,
                    optional(builder.enabled),
                    optional(builder.checked),
                    optional(builder.selected),
                    optional(builder.expanded),
                    optional(builder.editable),
                    focused,
                    builder.focusable || focused,
                    effectiveAlpha,
                    clipped,
                    viewportIntersecting,
                    hitTarget);

            List<String> childIds = childIds(actor, ids);
            String actorName = actor.getName();
            String actorType = actorType(actor);
            validateOptional(actorName, "actorName");
            validateOptional(actorType, "actorType");
            SemanticNode node = new SemanticNode(
                    nodeFrame.id(),
                    nodeFrame.parentId(),
                    childIds,
                    builder.role,
                    builder.accessibleName,
                    builder.text,
                    builder.label,
                    builder.testId,
                    actorName,
                    actorType,
                    state,
                    localBounds,
                    stageBounds,
                    screenBounds,
                    nodeFrame.zIndex(),
                    builder.properties);
            nodes.put(node.id(), node);
            estimatedBytes += estimateBytes(node);
        }
        limits.validateSnapshotBytes(estimatedBytes);
        return new SemanticSnapshot(revision, frame, "n0", nodes);
    }

    private void collect(
            Actor actor,
            String parentId,
            int zIndex,
            int depth,
            boolean ancestorsVisible,
            double ancestorAlpha,
            boolean ancestorsTouchable,
            Bounds inheritedClip,
            CoordinateMapper coordinates,
            List<NodeFrame> frames,
            IdentityHashMap<Actor, String> ids) {
        limits.validateDepth(depth);
        limits.validateNodeCount(frames.size() + 1);
        String id = "n" + frames.size();
        ids.put(actor, id);
        frames.add(new NodeFrame(
                actor,
                id,
                parentId,
                zIndex,
                ancestorsVisible,
                ancestorAlpha,
                ancestorsTouchable,
                inheritedClip));

        if (!(actor instanceof Group group)) {
            return;
        }
        boolean childAncestorsVisible = ancestorsVisible && actor.isVisible();
        double childAncestorAlpha = clampAlpha(ancestorAlpha * actor.getColor().a);
        boolean childAncestorsTouchable = ancestorsTouchable
                && actor.getTouchable() != Touchable.disabled;
        Bounds childClip = inheritedClip;
        if (actor instanceof ScrollPane) {
            Bounds actorBounds = coordinates.stageBounds(actor);
            childClip = childClip == null ? actorBounds : intersection(childClip, actorBounds);
        }
        SnapshotArray<Actor> children = group.getChildren();
        for (int index = 0; index < children.size; index++) {
            collect(
                    children.get(index),
                    id,
                    index,
                    depth + 1,
                    childAncestorsVisible,
                    childAncestorAlpha,
                    childAncestorsTouchable,
                    childClip,
                    coordinates,
                    frames,
                    ids);
        }
    }

    private void validateBuilder(SemanticNodeBuilder builder) {
        validateOptional(builder.accessibleName, "accessibleName");
        validateOptional(builder.text, "text");
        validateOptional(builder.label, "label");
        validateOptional(builder.testId, "testId");
        if (builder.properties.size() > SemanticNodeBuilder.MAX_PROPERTIES) {
            String actual = Integer.toString(builder.properties.size());
            String limit = Integer.toString(SemanticNodeBuilder.MAX_PROPERTIES);
            throw new HarnessException(
                    ErrorCode.LIMIT_EXCEEDED,
                    "properties exceeds configured limit " + limit + " (actual " + actual + ")",
                    ErrorEvidence.ofDetails(Map.of(
                            "dimension", "properties", "actual", actual, "limit", limit)));
        }
        for (Map.Entry<String, String> property : builder.properties.entrySet()) {
            limits.validateString(property.getKey(), "property key");
            limits.validateString(property.getValue(), "property value");
        }
    }

    private void validateOptional(String value, String field) {
        if (value != null) {
            limits.validateString(value, field);
        }
    }

    private static Optional<Boolean> optional(Boolean value) {
        return Optional.ofNullable(value);
    }

    private static List<String> childIds(Actor actor, IdentityHashMap<Actor, String> ids) {
        if (!(actor instanceof Group group)) {
            return List.of();
        }
        SnapshotArray<Actor> children = group.getChildren();
        List<String> childIds = new ArrayList<>(children.size);
        for (int index = 0; index < children.size; index++) {
            childIds.add(ids.get(children.get(index)));
        }
        return childIds;
    }

    private static boolean isHitTarget(
            Stage stage, Actor actor, CoordinateMapper coordinates) {
        Vector2 center = coordinates.localToStage(
                actor, actor.getWidth() * 0.5f, actor.getHeight() * 0.5f);
        Actor hit = stage.hit(center.x, center.y, true);
        for (Actor current = hit; current != null; current = current.getParent()) {
            if (current == actor) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Bounds outer, Bounds inner) {
        return inner.x() >= outer.x()
                && inner.y() >= outer.y()
                && inner.x() + inner.width() <= outer.x() + outer.width()
                && inner.y() + inner.height() <= outer.y() + outer.height();
    }

    private static boolean intersects(Bounds first, Bounds second) {
        return first.width() > 0
                && first.height() > 0
                && second.width() > 0
                && second.height() > 0
                && first.x() < second.x() + second.width()
                && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height()
                && first.y() + first.height() > second.y();
    }

    private static Bounds intersection(Bounds first, Bounds second) {
        double minX = Math.max(first.x(), second.x());
        double minY = Math.max(first.y(), second.y());
        double maxX = Math.min(first.x() + first.width(), second.x() + second.width());
        double maxY = Math.min(first.y() + first.height(), second.y() + second.height());
        return new Bounds(minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY));
    }

    private static double clampAlpha(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String actorType(Actor actor) {
        String simpleName = actor.getClass().getSimpleName();
        return simpleName.isBlank() ? actor.getClass().getName() : simpleName;
    }

    private static long estimateBytes(SemanticNode node) {
        long bytes = 192;
        bytes += utf8Length(node.id());
        bytes += utf8Length(node.parentId());
        bytes += utf8Length(node.accessibleName());
        bytes += utf8Length(node.text());
        bytes += utf8Length(node.label());
        bytes += utf8Length(node.testId());
        bytes += utf8Length(node.actorName());
        bytes += utf8Length(node.actorType());
        for (String childId : node.childIds()) {
            bytes += utf8Length(childId);
        }
        for (Map.Entry<String, String> property : node.properties().entrySet()) {
            bytes += utf8Length(property.getKey()) + utf8Length(property.getValue());
        }
        return bytes;
    }

    private static int utf8Length(String value) {
        if (value == null) {
            return 0;
        }
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private record NodeFrame(
            Actor actor,
            String id,
            String parentId,
            int zIndex,
            boolean ancestorsVisible,
            double ancestorAlpha,
            boolean ancestorsTouchable,
            Bounds clip) {}
}
