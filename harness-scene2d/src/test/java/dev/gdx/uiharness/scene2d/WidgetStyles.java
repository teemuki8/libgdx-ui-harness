package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle;

final class WidgetStyles {
    private static final BitmapFont FONT =
            new BitmapFont(new BitmapFontData(), new TextureRegion(), false);

    private WidgetStyles() {}

    static LabelStyle label() {
        return new LabelStyle(FONT, Color.WHITE);
    }

    static TextButtonStyle textButton() {
        TextButtonStyle style = new TextButtonStyle();
        style.font = FONT;
        style.fontColor = Color.WHITE;
        return style;
    }

    static CheckBoxStyle checkBox() {
        CheckBoxStyle style = new CheckBoxStyle();
        style.font = FONT;
        style.fontColor = Color.WHITE;
        return style;
    }

    static TextFieldStyle textField() {
        TextFieldStyle style = new TextFieldStyle();
        style.font = FONT;
        style.fontColor = Color.WHITE;
        return style;
    }

    static ListStyle list() {
        ListStyle style = new ListStyle();
        style.font = FONT;
        style.fontColorSelected = Color.WHITE;
        style.fontColorUnselected = Color.WHITE;
        style.selection = new com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable();
        return style;
    }

    static SelectBoxStyle selectBox() {
        SelectBoxStyle style = new SelectBoxStyle();
        style.font = FONT;
        style.fontColor = Color.WHITE;
        style.scrollStyle = new ScrollPaneStyle();
        style.listStyle = list();
        return style;
    }

    static WindowStyle window() {
        WindowStyle style = new WindowStyle();
        style.titleFont = FONT;
        style.titleFontColor = Color.WHITE;
        return style;
    }
}
