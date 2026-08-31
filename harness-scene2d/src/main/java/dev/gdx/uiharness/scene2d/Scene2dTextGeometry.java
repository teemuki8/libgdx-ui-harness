package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.GlyphLayout.GlyphRun;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import dev.gdx.uiharness.core.model.Bounds;
import java.util.Optional;

/**
 * Shared intrinsic glyph layout and ink placement for a real Scene2D Label.
 *
 * <p>Only public Label and GlyphLayout state is inspected. With {@code wrap=true}, no explicit
 * newline, and no publicly observable second line, libGDX does not expose whether a private
 * ellipsis disabled wrapping. A single non-left-aligned run exposes the target width through its
 * public x offset and is exact. Otherwise both internal states are evaluated, and placement is
 * unavailable exactly when they produce different conceptual origins; publishing either origin
 * would guess the private ellipsis state.
 */
final class Scene2dTextGeometry {
    private Scene2dTextGeometry() {}

    static Optional<Placement> placement(Label label) {
        GlyphLayout layout = label.getGlyphLayout();
        Optional<EffectiveFontMetrics> metrics = effectiveFontMetrics(label, layout);
        if (metrics.isEmpty()) {
            return Optional.empty();
        }
        boolean explicitNewline = label.getText().indexOf("\n") != -1;
        if (!label.getWrap() || explicitNewline || hasMultipleVisualLines(layout)) {
            return Optional.of(placement(
                    label, layout, explicitNewline || label.getWrap(), metrics.get()));
        }

        Boolean effectiveWrap = effectiveWrapFromLineOffset(label, layout);
        if (effectiveWrap != null) {
            return Optional.of(placement(label, layout, effectiveWrap, metrics.get()));
        }

        Placement wrapped = placement(label, layout, true, metrics.get());
        Placement ellipsized = placement(label, layout, false, metrics.get());
        if (Float.compare((float) wrapped.originX(), (float) ellipsized.originX()) != 0
                || Float.compare((float) wrapped.originY(), (float) ellipsized.originY()) != 0) {
            return Optional.empty();
        }
        return Optional.of(wrapped);
    }

    private static boolean hasMultipleVisualLines(GlyphLayout layout) {
        if (layout.runs.size < 2) {
            return false;
        }
        float firstBaseline = layout.runs.first().y;
        for (GlyphRun run : layout.runs) {
            if (Float.compare(run.y, firstBaseline) != 0) {
                return true;
            }
        }
        return false;
    }

    private static Boolean effectiveWrapFromLineOffset(Label label, GlyphLayout layout) {
        int lineAlign = label.getLineAlign();
        if (layout.runs.size != 1 || (lineAlign & Align.left) != 0) {
            return null;
        }
        float availableWidth = label.getWidth();
        Drawable background = label.getStyle().background;
        if (background != null) {
            availableWidth -= background.getLeftWidth() + background.getRightWidth();
        }
        GlyphRun run = layout.runs.first();
        float unwrappedOffset = (lineAlign & Align.right) != 0
                ? availableWidth - run.width
                : (availableWidth - run.width) / 2;
        return Float.compare(run.x, unwrappedOffset) != 0;
    }

    private static Placement placement(
            Label label,
            GlyphLayout layout,
            boolean multipleLines,
            EffectiveFontMetrics metrics) {
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

        float textWidth = multipleLines ? layout.width : width;
        float textHeight = layout.height;
        int alignment = label.getLabelAlign();
        if (multipleLines && (alignment & Align.left) == 0) {
            x += (alignment & Align.right) != 0
                    ? width - textWidth
                    : (width - textWidth) / 2;
        }
        if ((alignment & Align.top) != 0) {
            y += font.isFlipped() ? 0 : height - textHeight;
            y += metrics.descent();
        } else if ((alignment & Align.bottom) != 0) {
            y += font.isFlipped() ? height - textHeight : 0;
            y -= metrics.descent();
        } else {
            y += (height - textHeight) / 2;
        }
        if (!font.isFlipped()) {
            y += textHeight;
        }

        Bounds ink = inkBounds(
                layout, x, y, metrics.scaleX(), metrics.scaleY());
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

    private static Optional<EffectiveFontMetrics> effectiveFontMetrics(
            Label label, GlyphLayout layout) {
        BitmapFont font = label.getStyle().font;
        float lastBaselineDistance = 0;
        for (GlyphRun run : layout.runs) {
            lastBaselineDistance = Math.max(lastBaselineDistance, Math.abs(run.y));
        }
        float effectiveCapHeight = layout.height - lastBaselineDistance;
        if (Float.compare(effectiveCapHeight, font.getCapHeight()) == 0) {
            return Optional.of(new EffectiveFontMetrics(
                    font.getScaleX(), font.getScaleY(), font.getDescent()));
        }

        float scaleY = label.getFontScaleY() / font.getScaleY();
        if (Float.compare(effectiveCapHeight, font.getCapHeight() * scaleY) != 0) {
            return Optional.empty();
        }
        return Optional.of(new EffectiveFontMetrics(
                label.getFontScaleX(),
                label.getFontScaleY(),
                font.getDescent() * scaleY));
    }

    private record EffectiveFontMetrics(float scaleX, float scaleY, float descent) {}

    record Placement(
            double originX,
            double originY,
            double baselineY,
            Bounds layoutBounds,
            Bounds inkBounds) {}
}
