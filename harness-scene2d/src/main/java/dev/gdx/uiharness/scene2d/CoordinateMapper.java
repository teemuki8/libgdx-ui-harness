package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.Viewport;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.typography.AffineTransformObservation;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinatePoint;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.util.Objects;

/** Maps actor rectangles among actor-local, stage, and conventional top-left screen spaces. */
public final class CoordinateMapper {
    private final Stage stage;
    private final Vector2[] corners = {
        new Vector2(), new Vector2(), new Vector2(), new Vector2()
    };
    private final Vector2 point = new Vector2();

    /** Creates a mapper for one stage and its viewport. */
    public CoordinateMapper(Stage stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    /** Returns the actor-local rectangle. */
    public Bounds localBounds(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        return new Bounds(0, 0, actor.getWidth(), actor.getHeight());
    }

    /** Returns the axis-aligned stage-space rectangle containing all transformed actor corners. */
    public Bounds stageBounds(Actor actor) {
        mapCornersToStage(actor);
        return boundsOf(corners);
    }

    /** Returns the axis-aligned top-left-origin screen rectangle containing the actor. */
    public Bounds screenBounds(Actor actor) {
        mapCornersToStage(actor);
        Viewport viewport = stage.getViewport();
        float screenHeight = screenHeight(viewport);
        for (Vector2 corner : corners) {
            viewport.project(corner);
            corner.y = screenHeight - corner.y;
        }
        return boundsOf(corners);
    }

    /** Returns the stage-space rectangle represented by the current screen viewport. */
    public Bounds stageViewportBounds() {
        Viewport viewport = stage.getViewport();
        float height = screenHeight(viewport);
        float left = viewport.getScreenX();
        float right = left + viewport.getScreenWidth();
        float top = height - viewport.getScreenY() - viewport.getScreenHeight();
        float bottom = top + viewport.getScreenHeight();
        stage.screenToStageCoordinates(corners[0].set(left, top));
        stage.screenToStageCoordinates(corners[1].set(right, top));
        stage.screenToStageCoordinates(corners[2].set(left, bottom));
        stage.screenToStageCoordinates(corners[3].set(right, bottom));
        return boundsOf(corners);
    }

    /** Converts one actor-local point to stage space without allocating. */
    Vector2 localToStage(Actor actor, float x, float y) {
        point.set(x, y);
        return actor.localToStageCoordinates(point);
    }

    /** Returns the actor-local to parent-space affine mapping. */
    AffineTransformObservation localToParentTransform(Actor actor) {
        return transform(
                CoordinateSpace.LOCAL,
                CoordinateSpace.PARENT,
                (x, y, target) -> actor.localToParentCoordinates(target.set(x, y)));
    }

    /** Returns the parent-local to stage-space affine mapping. */
    AffineTransformObservation parentToStageTransform(Actor actor) {
        Actor parent = actor.getParent();
        if (parent == null) {
            return AffineTransformObservation.identity(
                    CoordinateSpace.PARENT, CoordinateSpace.STAGE);
        }
        return transform(
                CoordinateSpace.PARENT,
                CoordinateSpace.STAGE,
                (x, y, target) -> parent.localToStageCoordinates(target.set(x, y)));
    }

    /** Returns the stage to top-left logical-screen affine mapping. */
    AffineTransformObservation stageToScreenTransform() {
        Viewport viewport = stage.getViewport();
        double height = screenHeight(viewport);
        return transform(
                CoordinateSpace.STAGE,
                CoordinateSpace.SCREEN,
                (x, y, target) -> {
                    viewport.project(target.set(x, y));
                    target.y = (float) (height - target.y);
                    return target;
                });
    }

    /** Returns the top-left logical-screen to framebuffer affine mapping. */
    static AffineTransformObservation screenToFramebufferTransform(
            double scaleX, double scaleY) {
        return AffineTransformObservation.fromMatrix(
                CoordinateSpace.SCREEN,
                CoordinateSpace.FRAMEBUFFER,
                scaleX,
                0,
                0,
                0,
                scaleY,
                0);
    }

    /** Maps one actor-local point to all public rendering spaces. */
    java.util.List<CoordinatePoint> typographyPoint(
            Actor actor, double x, double y, double scaleX, double scaleY) {
        Vector2 local = new Vector2((float) x, (float) y);
        Vector2 stagePoint = actor.localToStageCoordinates(new Vector2(local));
        Vector2 screenPoint = stage.getViewport().project(new Vector2(stagePoint));
        screenPoint.y = screenHeight(stage.getViewport()) - screenPoint.y;
        return java.util.List.of(
                new CoordinatePoint(CoordinateSpace.LOCAL, local.x, local.y),
                new CoordinatePoint(CoordinateSpace.STAGE, stagePoint.x, stagePoint.y),
                new CoordinatePoint(CoordinateSpace.SCREEN, screenPoint.x, screenPoint.y),
                new CoordinatePoint(
                        CoordinateSpace.FRAMEBUFFER,
                        screenPoint.x * scaleX,
                        screenPoint.y * scaleY));
    }

    /** Maps one actor-local rectangle to axis-aligned bounds in every public space. */
    java.util.List<CoordinateBounds> typographyBounds(
            Actor actor, Bounds local, double scaleX, double scaleY) {
        Vector2[] localCorners = {
            new Vector2((float) local.x(), (float) local.y()),
            new Vector2((float) (local.x() + local.width()), (float) local.y()),
            new Vector2((float) local.x(), (float) (local.y() + local.height())),
            new Vector2(
                    (float) (local.x() + local.width()),
                    (float) (local.y() + local.height()))
        };
        Vector2[] stageCorners = copy(localCorners);
        for (Vector2 value : stageCorners) {
            actor.localToStageCoordinates(value);
        }
        Vector2[] screenCorners = copy(stageCorners);
        float height = screenHeight(stage.getViewport());
        for (Vector2 value : screenCorners) {
            stage.getViewport().project(value);
            value.y = height - value.y;
        }
        Vector2[] framebufferCorners = copy(screenCorners);
        for (Vector2 value : framebufferCorners) {
            value.scl((float) scaleX, (float) scaleY);
        }
        return java.util.List.of(
                coordinateBounds(CoordinateSpace.LOCAL, localCorners),
                coordinateBounds(CoordinateSpace.STAGE, stageCorners),
                coordinateBounds(CoordinateSpace.SCREEN, screenCorners),
                coordinateBounds(CoordinateSpace.FRAMEBUFFER, framebufferCorners));
    }

    private void mapCornersToStage(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        float width = actor.getWidth();
        float height = actor.getHeight();
        actor.localToStageCoordinates(corners[0].set(0, 0));
        actor.localToStageCoordinates(corners[1].set(width, 0));
        actor.localToStageCoordinates(corners[2].set(0, height));
        actor.localToStageCoordinates(corners[3].set(width, height));
    }

    private static Bounds boundsOf(Vector2[] values) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (Vector2 value : values) {
            minX = Math.min(minX, value.x);
            minY = Math.min(minY, value.y);
            maxX = Math.max(maxX, value.x);
            maxY = Math.max(maxY, value.y);
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }

    private static CoordinateBounds coordinateBounds(
            CoordinateSpace space, Vector2[] values) {
        Bounds bounds = boundsOf(values);
        return new CoordinateBounds(
                space, bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    private static Vector2[] copy(Vector2[] source) {
        Vector2[] result = new Vector2[source.length];
        for (int index = 0; index < source.length; index++) {
            result[index] = new Vector2(source[index]);
        }
        return result;
    }

    private static AffineTransformObservation transform(
            CoordinateSpace source,
            CoordinateSpace target,
            PointTransform mapping) {
        Vector2 origin = mapping.map(0, 0, new Vector2());
        Vector2 xAxis = mapping.map(1, 0, new Vector2());
        Vector2 yAxis = mapping.map(0, 1, new Vector2());
        return AffineTransformObservation.fromMatrix(
                source,
                target,
                xAxis.x - origin.x,
                yAxis.x - origin.x,
                origin.x,
                xAxis.y - origin.y,
                yAxis.y - origin.y,
                origin.y);
    }

    private static float screenHeight(Viewport viewport) {
        if (Gdx.graphics != null) {
            return Gdx.graphics.getHeight();
        }
        return viewport.getScreenY() + viewport.getScreenHeight();
    }

    @FunctionalInterface
    private interface PointTransform {
        Vector2 map(float x, float y, Vector2 target);
    }
}
