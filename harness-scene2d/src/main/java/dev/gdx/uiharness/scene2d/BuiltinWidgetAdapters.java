package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextArea;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import dev.gdx.uiharness.core.model.Role;

/** Registers semantic inference for standard Scene2D.UI widgets. */
public final class BuiltinWidgetAdapters {
    private BuiltinWidgetAdapters() {}

    /** Installs all built-in adapters into a registry. */
    public static void registerInto(ActorAdapterRegistry registry) {
        registry.register(Label.class, BuiltinWidgetAdapters::label);
        registry.register(Button.class, (button, target) -> target
                .role(Role.BUTTON)
                .enabled(!button.isDisabled())
                .checked(button.isChecked())
                .focusable(true));
        registry.register(TextButton.class, (button, target) -> text(
                        target.role(Role.BUTTON)
                                .enabled(!button.isDisabled())
                                .checked(button.isChecked())
                                .focusable(true),
                        Role.BUTTON,
                        button.getText()));
        registry.register(CheckBox.class, (checkBox, target) -> text(
                        target.role(Role.CHECKBOX)
                                .enabled(!checkBox.isDisabled())
                                .checked(checkBox.isChecked())
                                .focusable(true),
                        Role.CHECKBOX,
                        checkBox.getText()));
        registry.register(TextField.class, (field, target) -> textField(field, target, Role.TEXT_FIELD));
        registry.register(TextArea.class, (field, target) -> textField(field, target, Role.TEXT_AREA));
        registry.register(SelectBox.class, BuiltinWidgetAdapters::selectBox);
        registry.register(ProgressBar.class, (bar, target) ->
                progressBar(bar, target.role(Role.PROGRESS_BAR)));
        registry.register(Slider.class, (slider, target) -> progressBar(slider,
                target.role(Role.SLIDER).focusable(true)));
        registry.register(List.class, BuiltinWidgetAdapters::list);
        registry.register(ScrollPane.class, (pane, target) -> target
                .role(Role.SCROLL_PANE)
                .focusable(true)
                .property("scrollX", Float.toString(pane.getScrollX()))
                .property("scrollY", Float.toString(pane.getScrollY()))
                .property("maxX", Float.toString(pane.getMaxX()))
                .property("maxY", Float.toString(pane.getMaxY())));
        registry.register(Image.class, (image, target) -> target.role(Role.IMAGE));
        registry.register(Window.class, (window, target) -> window(window, target, Role.WINDOW));
        registry.register(Dialog.class, (dialog, target) -> window(dialog, target, Role.DIALOG));
    }

    private static void label(Label label, ActorSemanticAdapter.Target target) {
        if (!(label.getParent() instanceof TextButton)) {
            text(target, Role.LABEL, label.getText());
        }
    }

    private static void textField(
            TextField field, ActorSemanticAdapter.Target target, Role role) {
        text(target.role(role)
                        .enabled(!field.isDisabled())
                        .editable(!field.isDisabled())
                        .focusable(true),
                role,
                field.getText());
    }

    private static void selectBox(SelectBox<?> selectBox, ActorSemanticAdapter.Target target) {
        Object selected = selectBox.getSelected();
        target.role(Role.SELECT)
                .enabled(!selectBox.isDisabled())
                .selected(selected != null)
                .focusable(true)
                .property("selectedIndex", Integer.toString(selectBox.getSelectedIndex()));
        if (selected != null) {
            text(target, Role.SELECT, selected.toString());
        }
    }

    private static void progressBar(
            ProgressBar progressBar, ActorSemanticAdapter.Target target) {
        target.enabled(!progressBar.isDisabled())
                .property("value", Float.toString(progressBar.getValue()))
                .property("min", Float.toString(progressBar.getMinValue()))
                .property("max", Float.toString(progressBar.getMaxValue()))
                .property("step", Float.toString(progressBar.getStepSize()));
    }

    private static void list(List<?> list, ActorSemanticAdapter.Target target) {
        Object selected = list.getSelected();
        target.role(Role.LIST)
                .selected(selected != null)
                .focusable(true)
                .property("selectedIndex", Integer.toString(list.getSelectedIndex()));
        if (selected != null) {
            text(target, Role.LIST, selected.toString());
        }
    }

    private static void window(
            Window window, ActorSemanticAdapter.Target target, Role role) {
        CharSequence title = window.getTitleLabel().getText();
        text(target.role(role).focusable(true), role, title);
    }

    private static ActorSemanticAdapter.Target text(
            ActorSemanticAdapter.Target target, Role role, CharSequence value) {
        String stringValue = value == null ? null : value.toString();
        return target.role(role).accessibleName(stringValue).text(stringValue);
    }
}
