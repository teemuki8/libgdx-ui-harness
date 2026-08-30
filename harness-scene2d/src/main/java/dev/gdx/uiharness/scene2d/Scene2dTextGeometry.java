package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.GlyphLayout.GlyphRun;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import dev.gdx.uiharness.core.model.Bounds;

/** Shared intrinsic glyph layout and ink placement for a real Scene2D Label. */
final class Scene2dTextGeometry {
    private Scene2dTextGeometry() {}

    static Placement placement(Label label) {
        GlyphLayout layout = label.getGlyphLayout();
        BitmapFont font = label.getStyle().font;
        float width = label.getWidth();
        float height = label.getHeight();
        float x = 0;
        float y = 0;
        Drawable background = label.getStyle().background;
        if (background != null) {
            x = background.getLeftWidth();
            y = background.getBottomHeight();
            width -= background.getLeftWidth() + background.getRightWidth();
            height -= background.getBottomHeight() + background.getTopHeight();
        }
        boolean multipleLines = label.getWrap() || label.getText().indexOf("\n") != -1;
        float textWidth = multipleLines ? layout.width : width;
        float scaleY = label.getFontScaleY() / Math.max(font.getScaleY(), 1e-12f);
        float textHeight = multipleLines ? layout.height : font.getCapHeight() * scaleY;
        int alignment = label.getLabelAlign();
        if ((alignment & Align.left) == 0) {
            x += (alignment & Align.right) != 0
                    ? width - textWidth
                    : (width - textWidth) / 2;
        }
        if ((alignment & Align.top) != 0) {
            y += font.isFlipped() ? 0 : height - textHeight;
            y += font.getDescent() * scaleY;
        } else if ((alignment & Align.bottom) != 0) {
            y += font.isFlipped() ? height - textHeight : 0;
            y -= font.getDescent() * scaleY;
        } else {
            y += (height - textHeight) / 2;
        }
        if (!font.isFlipped()) {
            y += textHeight;
        }
        Bounds ink = inkBounds(layout, x, y, label.getFontScaleX(), label.getFontScaleY());
        Bounds layoutBounds = new Bounds(x, y - layout.height, layout.width, layout.height);
        double baseline = layout.runs.isEmpty() ? y : y + layout.runs.first().y;
        return new Placement(x, y, baseline, layoutBounds, ink);
    }

    private static Bounds inkBounds(
            GlyphLayout layout, float originX, float originY, float scaleX, float scaleY) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (GlyphRun run : layout.runs) {
            float glyphX = originX + run.x;
            float glyphY = originY + run.y;
            for (int index = 0; index < run.glyphs.size; index++) {
                glyphX += run.xAdvances.get(index);
                Glyph glyph = run.glyphs.get(index);
                double left = glyphX + glyph.xoffset * scaleX;
                double bottom = glyphY + glyph.yoffset * scaleY;
                double right = left + glyph.width * scaleX;
                double top = bottom + glyph.height * scaleY;
                minX = Math.min(minX, left);
                minY = Math.min(minY, bottom);
                maxX = Math.max(maxX, right);
                maxY = Math.max(maxY, top);
            }
        }
        if (!Double.isFinite(minX)) {
            return new Bounds(originX, originY, 0, 0);
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }

    record Placement(
            double originX,
            double originY,
            double baselineY,
            Bounds layoutBounds,
            Bounds inkBounds) {}
}
