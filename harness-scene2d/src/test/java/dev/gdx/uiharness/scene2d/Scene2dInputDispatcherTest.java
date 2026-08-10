package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import dev.gdx.uiharness.core.action.Action;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Scene2dInputDispatcherTest {
    @Test void directKeyTransitionsUseConfiguredProcessorWithoutTypedInput() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            RecordingKeys input = new RecordingKeys();
            Scene2dInputDispatcher dispatcher = new Scene2dInputDispatcher(stage, input);

            dispatcher.keyDown(Keys.SHIFT_LEFT);
            dispatcher.keyDown(Keys.A);
            dispatcher.keyUp(Keys.A);
            dispatcher.keyUp(Keys.SHIFT_LEFT);

            assertEquals(List.of(
                    "down:" + Keys.SHIFT_LEFT,
                    "down:" + Keys.A,
                    "up:" + Keys.A,
                    "up:" + Keys.SHIFT_LEFT), input.events);
        } finally {
            stage.dispose();
        }
    }

    @Test void existingPressStillSynthesizesTypedCharacter() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            TextField field = new TextField("", WidgetStyles.textField());
            field.setBounds(10, 10, 100, 30);
            stage.addActor(field);
            RecordingKeys input = new RecordingKeys();
            Scene2dInputDispatcher dispatcher = new Scene2dInputDispatcher(stage, input);

            dispatcher.dispatch(field, Action.press(Keys.A));

            assertEquals(List.of(
                    "down:" + Keys.A, "typed:a", "up:" + Keys.A), input.events);
        } finally {
            stage.dispose();
        }
    }

    @Test void clickMapsNestedActorCenterToScreenAndUsesConfiguredProcessor() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            Group parent = new Group();
            parent.setBounds(90, 70, 300, 200);
            parent.setScale(1.5f);
            TextButton button = new TextButton("Save", WidgetStyles.textButton());
            button.setBounds(40, 30, 120, 50);
            parent.addActor(button);
            stage.addActor(parent);
            Scene2dInputDispatcher dispatcher =
                    new Scene2dInputDispatcher(stage, new InputMultiplexer(stage));

            dispatcher.dispatch(button, Action.click());

            assertTrue(button.isChecked());
        } finally {
            stage.dispose();
        }
    }

    @Test void focusAndFillProduceRealTextFieldState() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            TextField field = new TextField("old value", WidgetStyles.textField());
            field.setOnlyFontChars(false);
            field.setBounds(100, 100, 240, 50);
            stage.addActor(field);
            Scene2dInputDispatcher dispatcher = new Scene2dInputDispatcher(stage, stage);

            dispatcher.dispatch(field, Action.focus());
            dispatcher.dispatch(field, Action.fill("new value"));

            assertSame(field, stage.getKeyboardFocus());
            assertEquals("new value", field.getText());
        } finally {
            stage.dispose();
        }
    }

    @Test void scrollSetsScrollFocusAndMovesRealScrollPane() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            Group content = new Group();
            content.setSize(200, 800);
            ScrollPane pane = new ScrollPane(content, new ScrollPaneStyle());
            pane.setBounds(50, 50, 200, 180);
            stage.addActor(pane);
            pane.validate();
            Scene2dInputDispatcher dispatcher = new Scene2dInputDispatcher(stage, stage);

            dispatcher.dispatch(pane, Action.scroll(0, 4));

            assertSame(pane, stage.getScrollFocus());
            assertTrue(pane.getScrollY() > 0f);
        } finally {
            stage.dispose();
        }
    }

    @Test void dragUsesStagePointerCaptureAndChangesSliderValue() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            Slider slider = new Slider(0, 100, 1, false, sliderStyle());
            slider.setBounds(100, 100, 300, 40);
            slider.setValue(20);
            stage.addActor(slider);
            Scene2dInputDispatcher dispatcher = new Scene2dInputDispatcher(stage, stage);

            dispatcher.dispatch(slider, Action.drag(120, 0));

            assertTrue(slider.getValue() > 20f);
        } finally {
            stage.dispose();
        }
    }

    @Test void pointerDownAndUpMaintainPointerIdentityAndClickButton() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            TextButton button = new TextButton("Toggle", WidgetStyles.textButton());
            button.setBounds(100, 100, 180, 50);
            stage.addActor(button);
            Scene2dInputDispatcher dispatcher = new Scene2dInputDispatcher(stage, stage);

            dispatcher.dispatch(button,
                    Action.pointer(Action.PointerPhase.DOWN, 0, 0, 3, Buttons.LEFT));
            assertTrue(button.isPressed());
            dispatcher.dispatch(button,
                    Action.pointer(Action.PointerPhase.UP, 0, 0, 3, Buttons.LEFT));

            assertTrue(button.isChecked());
        } finally {
            stage.dispose();
        }
    }
    private static SliderStyle sliderStyle() {
        SliderStyle style = new SliderStyle();
        style.background = new BaseDrawable();
        style.knob = new BaseDrawable();
        style.knob.setMinWidth(10);
        style.knob.setMinHeight(10);
        return style;
    }

    private static final class RecordingKeys extends InputAdapter {
        private final List<String> events = new ArrayList<>();

        @Override public boolean keyDown(int keycode) {
            events.add("down:" + keycode);
            return true;
        }

        @Override public boolean keyUp(int keycode) {
            events.add("up:" + keycode);
            return true;
        }

        @Override public boolean keyTyped(char character) {
            events.add("typed:" + character);
            return true;
        }
    }

}
