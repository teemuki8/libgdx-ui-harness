package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.util.Map;
import java.util.Objects;

/** Converts actor-relative actions to screen coordinates and routes them through libGDX input APIs. */
public final class Scene2dInputDispatcher {
    private final Stage stage;
    private final InputProcessor input;
    private final Vector2 point = new Vector2();

    /** Uses the explicitly configured input processor; it may be a Stage or an InputMultiplexer. */
    public Scene2dInputDispatcher(Stage stage, InputProcessor input) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.input = Objects.requireNonNull(input, "input");
    }

    /** Dispatches at the actor's transformed center. */
    public void dispatch(Actor actor, Action action) {
        Objects.requireNonNull(actor, "actor");
        Vector2 center = new CoordinateMapper(stage).localToStage(
                actor, actor.getWidth() * 0.5f, actor.getHeight() * 0.5f);
        dispatchAt(actor, action, center.x, center.y);
    }

    /** Dispatches at a previously validated stage-space point during the same render-thread turn. */
    void dispatchAt(Actor actor, Action action, float stageX, float stageY) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        if (actor.getStage() != stage) {
            throw new HarnessException(
                    ErrorCode.NOT_ACTIONABLE,
                    "Selected actor detached before input dispatch",
                    ErrorEvidence.ofDetails(Map.of("unmet", "ATTACHED")));
        }
        Vector2 screen = stage.stageToScreenCoordinates(point.set(stageX, stageY));
        int screenX = Math.round(screen.x);
        int screenY = Math.round(screen.y);
        switch (action) {
            case Action.Click click -> click(screenX, screenY, click);
            case Action.Hover ignored -> input.mouseMoved(screenX, screenY);
            case Action.Focus ignored -> stage.setKeyboardFocus(actor);
            case Action.Fill fill -> fill(actor, fill.value());
            case Action.Press press -> press(actor, press.keycode());
            case Action.Scroll scroll -> scroll(actor, screenX, screenY, scroll);
            case Action.Drag drag -> drag(screenX, screenY, drag);
            case Action.Pointer pointer -> pointer(screenX, screenY, pointer);
        }
    }

    private void click(int x, int y, Action.Click click) {
        input.touchDown(x, y, click.pointer(), click.button());
        input.touchUp(x, y, click.pointer(), click.button());
    }

    private void fill(Actor actor, String value) {
        if (!(actor instanceof TextField field)) {
            throw new HarnessException(
                    ErrorCode.UNSUPPORTED_CAPABILITY,
                    "Fill requires a Scene2D TextField",
                    ErrorEvidence.ofDetails(Map.of("actorType", actor.getClass().getName())));
        }
        stage.setKeyboardFocus(actor);
        input.keyDown(Keys.END);
        input.keyUp(Keys.END);
        int existingCharacters = field.getText().length();
        for (int index = 0; index < existingCharacters; index++) {
            input.keyTyped('\b');
        }
        for (int index = 0; index < value.length(); index++) {
            input.keyTyped(value.charAt(index));
        }
    }

    private void press(Actor actor, int keycode) {
        stage.setKeyboardFocus(actor);
        input.keyDown(keycode);
        Character typed = typedCharacter(keycode);
        if (typed != null) {
            input.keyTyped(typed);
        }
        input.keyUp(keycode);
    }

    private static Character typedCharacter(int keycode) {
        if (keycode == Keys.BACKSPACE) {
            return '\b';
        }
        if (keycode == Keys.ENTER) {
            return '\r';
        }
        if (keycode == Keys.TAB) {
            return '\t';
        }
        if (keycode == Keys.SPACE) {
            return ' ';
        }
        String name = Keys.toString(keycode);
        return name != null && name.length() == 1
                ? Character.toLowerCase(name.charAt(0))
                : null;
    }

    private void scroll(
            Actor actor, int screenX, int screenY, Action.Scroll scroll) {
        stage.setScrollFocus(actor);
        input.mouseMoved(screenX, screenY);
        input.scrolled(scroll.amountX(), scroll.amountY());
    }

    private void drag(int startX, int startY, Action.Drag drag) {
        int endX = Math.round(startX + drag.deltaX());
        int endY = Math.round(startY + drag.deltaY());
        input.touchDown(startX, startY, drag.pointer(), drag.button());
        input.touchDragged(endX, endY, drag.pointer());
        input.touchUp(endX, endY, drag.pointer(), drag.button());
    }

    private void pointer(int originX, int originY, Action.Pointer pointer) {
        int x = Math.round(originX + pointer.offsetX());
        int y = Math.round(originY + pointer.offsetY());
        switch (pointer.phase()) {
            case DOWN -> input.touchDown(x, y, pointer.pointer(), pointer.button());
            case MOVE -> input.touchDragged(x, y, pointer.pointer());
            case UP -> input.touchUp(x, y, pointer.pointer(), pointer.button());
        }
    }
}
