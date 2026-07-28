package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.Viewport;
import dev.gdx.uiharness.core.model.Bounds;
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

    private static float screenHeight(Viewport viewport) {
        if (Gdx.graphics != null) {
            return Gdx.graphics.getHeight();
        }
        return viewport.getScreenY() + viewport.getScreenHeight();
    }
}
