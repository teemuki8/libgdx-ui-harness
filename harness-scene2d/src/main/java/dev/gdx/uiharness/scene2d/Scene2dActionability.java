package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import dev.gdx.uiharness.core.action.Actionability;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import java.util.Objects;

/** Computes live Scene2D actionability and a visible stage-space input point. */
public final class Scene2dActionability {
    /** Inspects an actor only while executing on its owning render thread. */
    public Observation inspect(
            Stage stage, Actor actor, SemanticNode node, boolean stable) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(node, "node");
        CoordinateMapper coordinates = new CoordinateMapper(stage);
        Bounds actorBounds = coordinates.stageBounds(actor);
        Bounds visibleBounds = intersection(actorBounds, coordinates.stageViewportBounds());
        for (Actor parent = actor.getParent(); parent != null && visibleBounds != null;
                parent = parent.getParent()) {
            if (parent instanceof ScrollPane) {
                visibleBounds = intersection(visibleBounds, coordinates.stageBounds(parent));
            }
        }

        boolean attached = actor.getStage() == stage && reachesRoot(actor, stage);
        boolean visible = node.state().visible()
                && node.state().effectiveAlpha() > 0
                && actorBounds.width() > 0
                && actorBounds.height() > 0;
        boolean enabled = node.state().enabled().orElse(true);
        boolean touchable = node.state().touchable();
        boolean viewportIntersecting = visibleBounds != null
                && visibleBounds.width() > 0
                && visibleBounds.height() > 0
                && node.state().viewportIntersecting();
        float pointX = viewportIntersecting
                ? (float) (visibleBounds.x() + visibleBounds.width() * 0.5)
                : (float) (actorBounds.x() + actorBounds.width() * 0.5);
        float pointY = viewportIntersecting
                ? (float) (visibleBounds.y() + visibleBounds.height() * 0.5)
                : (float) (actorBounds.y() + actorBounds.height() * 0.5);
        Actor hit = viewportIntersecting ? stage.hit(pointX, pointY, true) : null;
        boolean hitTarget = isSelfOrDescendant(hit, actor);
        Actionability state = new Actionability(
                attached,
                visible,
                enabled,
                touchable,
                stable,
                viewportIntersecting,
                hitTarget);
        return new Observation(
                state, pointX, pointY, System.identityHashCode(actor), actorBounds);
    }

    private static boolean reachesRoot(Actor actor, Stage stage) {
        Actor current = actor;
        while (current.getParent() != null) {
            current = current.getParent();
        }
        return current == stage.getRoot();
    }

    private static boolean isSelfOrDescendant(Actor hit, Actor intended) {
        for (Actor current = hit; current != null; current = current.getParent()) {
            if (current == intended) {
                return true;
            }
        }
        return false;
    }

    private static Bounds intersection(Bounds left, Bounds right) {
        double x = Math.max(left.x(), right.x());
        double y = Math.max(left.y(), right.y());
        double rightEdge = Math.min(left.x() + left.width(), right.x() + right.width());
        double topEdge = Math.min(left.y() + left.height(), right.y() + right.height());
        if (rightEdge <= x || topEdge <= y) {
            return null;
        }
        return new Bounds(x, y, rightEdge - x, topEdge - y);
    }

    /** Live observation containing no retained Actor reference. */
    public record Observation(
            Actionability actionability,
            float stageX,
            float stageY,
            int actorIdentity,
            Bounds stageBounds) {
        public Observation {
            Objects.requireNonNull(actionability, "actionability");
            Objects.requireNonNull(stageBounds, "stageBounds");
        }
    }
}
