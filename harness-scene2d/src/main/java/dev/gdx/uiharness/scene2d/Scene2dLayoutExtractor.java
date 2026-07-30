package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.SnapshotArray;
import dev.gdx.uiharness.core.layout.LayoutClip;
import dev.gdx.uiharness.core.layout.LayoutObservation;
import dev.gdx.uiharness.core.layout.LayoutPadding;
import dev.gdx.uiharness.core.layout.LayoutScroll;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.DisplayObservation;
import dev.gdx.uiharness.core.typography.TransformChain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Extracts selected Scene2D layout, ownership, clip, and scroll evidence. */
final class Scene2dLayoutExtractor {
    private final Stage stage;
    private final Semantics semantics;

    Scene2dLayoutExtractor(Stage stage, Semantics semantics) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
    }

    List<LayoutObservation> extract(
            long revision, long frame, LayoutCaptureContext context) {
        Objects.requireNonNull(context, "context");
        IdentityHashMap<Actor, String> actorIds = actorIds();
        CoordinateMapper coordinates = new CoordinateMapper(stage);
        double scaleX = context.framebufferWidth() / (double) context.windowWidth();
        double scaleY = context.framebufferHeight() / (double) context.windowHeight();
        DisplayObservation display = new DisplayObservation(
                context.applicationId(),
                context.viewportId(),
                context.windowWidth(),
                context.windowHeight(),
                Math.round(stage.getViewport().getWorldWidth()),
                Math.round(stage.getViewport().getWorldHeight()),
                context.framebufferWidth(),
                context.framebufferHeight(),
                scaleX,
                scaleY);
        List<LayoutObservation> result = new ArrayList<>();
        collect(stage.getRoot(), true, actorIds, coordinates, context, display,
                scaleX, scaleY, revision, frame, result);
        if (result.size() != context.controlIds().size()) {
            List<String> observed = result.stream()
                    .map(LayoutObservation::controlId)
                    .toList();
            throw new IllegalArgumentException(
                    "missing selected layout controls: "
                            + context.controlIds().stream()
                                    .filter(value -> !observed.contains(value))
                                    .toList());
        }
        return List.copyOf(result);
    }

    private void collect(
            Actor actor,
            boolean ancestorsVisible,
            IdentityHashMap<Actor, String> actorIds,
            CoordinateMapper coordinates,
            LayoutCaptureContext context,
            DisplayObservation display,
            double scaleX,
            double scaleY,
            long revision,
            long frame,
            List<LayoutObservation> result) {
        boolean visible = ancestorsVisible && actor.isVisible();
        ActorMetadata metadata = semantics.metadata(actor);
        if (visible
                && metadata.testId() != null
                && context.controlIds().contains(metadata.testId())) {
            if (metadata.layout() == null) {
                throw new IllegalArgumentException(
                        "missing layout metadata for control " + metadata.testId());
            }
            result.add(observe(actor, actorIds, coordinates, context, display,
                    scaleX, scaleY, revision, frame, metadata));
        }
        if (actor instanceof Group group) {
            SnapshotArray<Actor> children = group.getChildren();
            for (int index = 0; index < children.size; index++) {
                collect(children.get(index), visible, actorIds, coordinates,
                        context, display, scaleX, scaleY, revision, frame, result);
            }
        }
    }

    private LayoutObservation observe(
            Actor actor,
            IdentityHashMap<Actor, String> actorIds,
            CoordinateMapper coordinates,
            LayoutCaptureContext context,
            DisplayObservation display,
            double scaleX,
            double scaleY,
            long revision,
            long frame,
            ActorMetadata metadata) {
        Actor parent = actor.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "selected layout control must have a parent: " + metadata.testId());
        }
        Actor layoutOwner = layoutOwner(actor);
        ScrollPane scrollOwner = scrollOwner(actor);
        List<CoordinateBounds> bounds = coordinates.typographyBounds(
                actor, coordinates.localBounds(actor), scaleX, scaleY);
        List<LayoutClip> clips = clips(actor, actorIds, coordinates, scaleX, scaleY);
        CoordinateBounds framebuffer = find(bounds, CoordinateSpace.FRAMEBUFFER);
        CoordinateBounds visible = intersection(
                framebuffer,
                clips.isEmpty()
                        ? framebufferViewport(context)
                        : clips.getLast().framebufferBounds());
        LayoutScroll scroll = scroll(
                scrollOwner, coordinates, scaleX, scaleY, context);
        TransformChain transforms = new TransformChain(
                coordinates.localToParentTransform(actor),
                coordinates.parentToStageTransform(actor),
                coordinates.stageToScreenTransform(),
                CoordinateMapper.screenToFramebufferTransform(scaleX, scaleY));
        LayoutPadding padding = padding(actor);
        String actorId = actorIds.get(actor);
        String parentId = stableId(parent, actorIds);
        String layoutOwnerId = stableId(layoutOwner, actorIds);
        String scrollOwnerId = scrollOwner == null
                ? null : stableId(scrollOwner, actorIds);
        String clipOwnerId = clips.isEmpty() ? null : clips.getLast().ownerId();
        String digest = digest(
                metadata.testId(), actorId, parentId, layoutOwnerId,
                scrollOwnerId, clipOwnerId, metadata.layout().role(),
                bounds, padding, clips, scroll, transforms);
        return new LayoutObservation(
                "layout/v1",
                metadata.testId(),
                actorId,
                parentId,
                layoutOwnerId,
                scrollOwnerId,
                clipOwnerId,
                metadata.layout().role(),
                revision,
                frame,
                context.layoutRevision(),
                context.currentArtifactId(),
                context.captureSha256(),
                digest,
                display,
                transforms,
                bounds,
                padding,
                clips,
                visible,
                scroll);
    }

    private List<LayoutClip> clips(
            Actor actor,
            IdentityHashMap<Actor, String> actorIds,
            CoordinateMapper coordinates,
            double scaleX,
            double scaleY) {
        List<LayoutClip> result = new ArrayList<>();
        Actor ancestor = actor.getParent();
        while (ancestor != null) {
            if (ancestor instanceof ScrollPane) {
                List<CoordinateBounds> mapped = coordinates.typographyBounds(
                        ancestor, coordinates.localBounds(ancestor), scaleX, scaleY);
                result.add(new LayoutClip(
                        stableId(ancestor, actorIds),
                        find(mapped, CoordinateSpace.STAGE),
                        find(mapped, CoordinateSpace.SCREEN),
                        find(mapped, CoordinateSpace.FRAMEBUFFER)));
            }
            ancestor = ancestor.getParent();
        }
        return List.copyOf(result);
    }

    private static Actor layoutOwner(Actor actor) {
        Actor ancestor = actor instanceof Table ? actor : actor.getParent();
        while (ancestor != null) {
            if (ancestor instanceof Table) {
                return ancestor;
            }
            ancestor = ancestor.getParent();
        }
        return actor.getParent();
    }

    private static ScrollPane scrollOwner(Actor actor) {
        Actor ancestor = actor instanceof ScrollPane ? actor : actor.getParent();
        while (ancestor != null) {
            if (ancestor instanceof ScrollPane pane) {
                return pane;
            }
            ancestor = ancestor.getParent();
        }
        return null;
    }

    private LayoutScroll scroll(
            ScrollPane pane,
            CoordinateMapper coordinates,
            double scaleX,
            double scaleY,
            LayoutCaptureContext context) {
        if (pane == null) {
            CoordinateBounds viewport = framebufferViewport(context);
            return new LayoutScroll(0, 0, 0, 0, viewport, viewport, false);
        }
        CoordinateBounds viewport = find(
                coordinates.typographyBounds(
                        pane, coordinates.localBounds(pane), scaleX, scaleY),
                CoordinateSpace.FRAMEBUFFER);
        Actor widget = pane.getActor();
        CoordinateBounds content = widget == null
                ? viewport
                : find(coordinates.typographyBounds(
                        widget, coordinates.localBounds(widget), scaleX, scaleY),
                        CoordinateSpace.FRAMEBUFFER);
        boolean active = pane.isDragging()
                || pane.isPanning()
                || pane.isFlinging()
                || Float.compare(pane.getScrollX(), pane.getVisualScrollX()) != 0
                || Float.compare(pane.getScrollY(), pane.getVisualScrollY()) != 0;
        return new LayoutScroll(
                pane.getScrollX(),
                pane.getScrollY(),
                pane.getMaxX(),
                pane.getMaxY(),
                viewport,
                content,
                active);
    }

    private static LayoutPadding padding(Actor actor) {
        if (actor instanceof Table table) {
            return new LayoutPadding(
                    table.getPadTop(),
                    table.getPadRight(),
                    table.getPadBottom(),
                    table.getPadLeft());
        }
        return new LayoutPadding(0, 0, 0, 0);
    }

    private String stableId(Actor actor, IdentityHashMap<Actor, String> actorIds) {
        String testId = semantics.metadata(actor).testId();
        return testId == null ? actorIds.get(actor) : testId;
    }

    private static CoordinateBounds find(
            List<CoordinateBounds> values, CoordinateSpace space) {
        return values.stream()
                .filter(value -> value.space() == space)
                .findFirst()
                .orElseThrow();
    }

    private static CoordinateBounds framebufferViewport(LayoutCaptureContext context) {
        return new CoordinateBounds(
                CoordinateSpace.FRAMEBUFFER,
                0,
                0,
                context.framebufferWidth(),
                context.framebufferHeight());
    }

    private static CoordinateBounds intersection(
            CoordinateBounds first, CoordinateBounds second) {
        double x = Math.max(first.x(), second.x());
        double y = Math.max(first.y(), second.y());
        double right = Math.min(
                first.x() + first.width(), second.x() + second.width());
        double bottom = Math.min(
                first.y() + first.height(), second.y() + second.height());
        return new CoordinateBounds(
                CoordinateSpace.FRAMEBUFFER,
                x,
                y,
                Math.max(0, right - x),
                Math.max(0, bottom - y));
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

    private static String digest(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }
}
