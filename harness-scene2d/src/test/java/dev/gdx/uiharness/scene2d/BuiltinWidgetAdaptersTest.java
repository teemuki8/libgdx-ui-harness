package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class BuiltinWidgetAdaptersTest {
    @Test void dispatchesAllBuiltInWidgetRolesAndVisibleText() {
        Stage stage = stage();
        Label label = named(new Label("Status ready", WidgetStyles.label()), "label");
        TextButton button = named(new TextButton("Save", WidgetStyles.textButton()), "button");
        CheckBox checkBox = named(new CheckBox("Remember", WidgetStyles.checkBox()), "checkbox");
        TextField textField = named(new TextField("", WidgetStyles.textField()), "field");
        textField.setOnlyFontChars(false);
        textField.setText("Ada");
        SelectBox<String> selectBox = named(new SelectBox<>(WidgetStyles.selectBox()), "select");
        selectBox.setItems("Small", "Large");
        selectBox.setSelected("Large");
        Slider slider = named(new Slider(0, 10, 0.5f, false, new SliderStyle()), "slider");
        com.badlogic.gdx.scenes.scene2d.ui.List<String> list =
                named(new com.badlogic.gdx.scenes.scene2d.ui.List<>(WidgetStyles.list()), "list");
        list.setItems("One", "Two");
        list.setSelected("Two");
        ScrollPane pane = named(
                new ScrollPane(new Actor(), new ScrollPaneStyle()), "scroll");
        Window window = named(new Window("Preferences", WidgetStyles.window()), "window");
        Dialog dialog = named(new Dialog("Confirm", WidgetStyles.window()), "dialog");
        Actor[] actors = {label, button, checkBox, textField, selectBox, slider, list, pane, window, dialog};
        for (int index = 0; index < actors.length; index++) {
            actors[index].setBounds(index * 70, 10, 60, 30);
            stage.addActor(actors[index]);
        }

        Map<String, SemanticNode> nodes = byActorName(snapshot(stage));

        assertSemantic(nodes.get("label"), Role.LABEL, "Status ready", "Status ready");
        assertSemantic(nodes.get("button"), Role.BUTTON, "Save", "Save");
        assertSemantic(nodes.get("checkbox"), Role.CHECKBOX, "Remember", "Remember");
        assertSemantic(nodes.get("field"), Role.TEXT_FIELD, "Ada", "Ada");
        assertSemantic(nodes.get("select"), Role.SELECT, "Large", "Large");
        assertEquals(Role.SLIDER, nodes.get("slider").role());
        assertSemantic(nodes.get("list"), Role.LIST, "Two", "Two");
        assertEquals(Role.SCROLL_PANE, nodes.get("scroll").role());
        assertSemantic(nodes.get("window"), Role.WINDOW, "Preferences", "Preferences");
        assertSemantic(nodes.get("dialog"), Role.DIALOG, "Confirm", "Confirm");
    }

    @Test void extractsBuiltInCheckedEnabledEditableSelectedAndRangeState() {
        Stage stage = stage();
        CheckBox checkBox = named(new CheckBox("Terms", WidgetStyles.checkBox()), "checkbox");
        checkBox.setChecked(true);
        checkBox.setDisabled(true);
        checkBox.setBounds(0, 0, 100, 30);
        TextField field = named(new TextField("", WidgetStyles.textField()), "field");
        field.setDisabled(true);
        field.setBounds(110, 0, 100, 30);
        Slider slider = named(new Slider(0, 10, 0.5f, false, new SliderStyle()), "slider");
        slider.setValue(7.5f);
        slider.setDisabled(true);
        slider.setBounds(220, 0, 100, 30);
        com.badlogic.gdx.scenes.scene2d.ui.List<String> list =
                named(new com.badlogic.gdx.scenes.scene2d.ui.List<>(WidgetStyles.list()), "list");
        list.setItems("One", "Two");
        list.setSelectedIndex(1);
        list.setBounds(330, 0, 100, 30);
        stage.addActor(checkBox);
        stage.addActor(field);
        stage.addActor(slider);
        stage.addActor(list);

        Map<String, SemanticNode> nodes = byActorName(snapshot(stage));

        assertTrue(nodes.get("checkbox").state().checked().orElseThrow());
        assertFalse(nodes.get("checkbox").state().enabled().orElseThrow());
        assertFalse(nodes.get("field").state().editable().orElseThrow());
        assertFalse(nodes.get("slider").state().enabled().orElseThrow());
        assertEquals("7.5", nodes.get("slider").properties().get("value"));
        assertEquals("0.0", nodes.get("slider").properties().get("min"));
        assertEquals("10.0", nodes.get("slider").properties().get("max"));
        assertEquals("0.5", nodes.get("slider").properties().get("step"));
        assertTrue(nodes.get("list").state().selected().orElseThrow());
        assertEquals("1", nodes.get("list").properties().get("selectedIndex"));
    }

    @Test void dispatchesInheritedAdapterToWidgetSubclass() {
        Stage stage = stage();
        SpecialButton button = named(new SpecialButton("Special", WidgetStyles.textButton()), "special");
        button.setBounds(0, 0, 100, 30);
        stage.addActor(button);

        SemanticNode node = byActorName(snapshot(stage)).get("special");

        assertEquals(Role.BUTTON, node.role());
        assertEquals("Special", node.accessibleName());
    }

    private static void assertSemantic(
            SemanticNode node, Role role, String accessibleName, String text) {
        assertEquals(role, node.role());
        assertEquals(accessibleName, node.accessibleName());
        assertEquals(text, node.text());
    }

    private static SemanticSnapshot snapshot(Stage stage) {
        return new Scene2dSnapshotter().snapshot(stage, 1, 1);
    }

    private static Map<String, SemanticNode> byActorName(SemanticSnapshot snapshot) {
        return snapshot.nodes().values().stream()
                .filter(node -> node.actorName() != null)
                .collect(Collectors.toMap(SemanticNode::actorName, Function.identity()));
    }

    private static Stage stage() {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        NoopBatch.installGraphics();
        FitViewport viewport = new FitViewport(800, 600);
        viewport.setScreenBounds(0, 0, 800, 600);
        viewport.getCamera().position.set(400, 300, 0);
        viewport.getCamera().update();
        return new Stage(viewport, new NoopBatch());
    }

    private static <A extends Actor> A named(A actor, String name) {
        actor.setName(name);
        return actor;
    }

    private static final class SpecialButton extends TextButton {
        SpecialButton(String text, TextButtonStyle style) {
            super(text, style);
        }
    }
}
