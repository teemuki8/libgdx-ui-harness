/*
 * Scroll and glyph arithmetic adapted from libGDX 1.14.2 (modified read-only observation).
 * Copyright 2011 See AUTHORS at https://github.com/libgdx/libgdx/blob/1.14.2/AUTHORS.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.Version;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.FloatArray;
import dev.gdx.uiharness.core.model.Bounds;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Read-only observation of libGDX 1.14.2's standard single-line field and horizontal scrolling.
 * Fixed internal field handles are never selected by protocol input or exposed to callers.
 * Unknown subclasses, versions and inaccessible internals remain explicitly unavailable.
 */
final class Scene2dTextFieldGeometry {
    private static final int MAX_CHARS = 4096;
    private static final int MAX_ANCESTORS = 128;
    private static final Access ACCESS = access();

    private Scene2dTextFieldGeometry() {}

    static Optional<Scene2dTextGeometry.Placement> placement(TextField field) {
        if (ACCESS == null || field.getClass() != TextField.class) {
            return Optional.empty();
        }
        CharSequence observed = (CharSequence) ACCESS.displayText().get(field);
        if (observed == null || observed.length() > MAX_CHARS) {
            return Optional.empty();
        }
        String text = observed.toString();
        FloatArray positions = (FloatArray) ACCESS.glyphPositions().get(field);
        if (positions == null || positions.size != text.length() + 1
                || positions.size > MAX_CHARS + 1) {
            return Optional.empty();
        }
        // Copy mutable internal arrays on the owner's render thread. Never retain the actor.
        float[] glyphs = positions.toArray();
        if (!finite(glyphs)) {
            return Optional.empty();
        }
        float fontOffset = (float) ACCESS.fontOffset().get(field);
        float textHeight = (float) ACCESS.textHeight().get(field);
        float offset = (float) ACCESS.renderOffset().get(field);
        var style = field.getStyle();
        BitmapFont font = style.font;
        if (font == null || font.getClass() != BitmapFont.class
                || !finite(fontOffset, textHeight, offset, field.getWidth(), field.getHeight(),
                        font.getScaleX(), font.getScaleY(), font.getDescent(), font.getCapHeight(),
                        font.getData().ascent, font.getData().down)
                || field.getWidth() < 0 || field.getHeight() < 0
                || font.getScaleX() <= 0 || font.getScaleY() <= 0 || font.getCapHeight() < 0) {
            return Optional.empty();
        }
        Drawable background = field.isDisabled() && style.disabledBackground != null
                ? style.disabledBackground
                : field.hasKeyboardFocus() && style.focusedBackground != null
                        ? style.focusedBackground : style.background;
        float left = background == null ? 0 : background.getLeftWidth();
        float right = background == null ? 0 : background.getRightWidth();
        float bottom = background == null ? 0 : background.getBottomHeight();
        float top = background == null ? 0 : background.getTopHeight();
        // Preserve calculateOffsets' arithmetic order, including fractional padding.
        float width = field.getWidth() - (left + right);
        if (!finite(left, right, bottom, top, width)) {
            return Optional.empty();
        }
        var drawPosition = drawPosition(field);
        if (drawPosition.isEmpty()) {
            return Optional.empty();
        }
        Slice slice = visibleSlice(field, glyphs, offset, fontOffset, width);
        if (slice.start() > slice.end() || slice.end() > text.length()
                || !Float.isFinite(slice.offset())) {
            return Optional.empty();
        }
        float textY = textHeight / 2 + font.getDescent()
                + (field.getHeight() - top - bottom) / 2 + bottom;
        if (!Float.isFinite(textY)) {
            return Optional.empty();
        }
        if (font.usesIntegerPositions()) {
            textY = (int) textY;
        }
        float yOffset = font.isFlipped() ? -textHeight : 0;
        String literal = text.substring(slice.start(), slice.end());
        if (font.getData().markupEnabled) {
            // Escaped brackets retain literal input without changing the application-owned font.
            // The temporary string is bounded by twice MAX_CHARS; it still has <= MAX_CHARS glyphs.
            literal = literal.replace("[", "[[");
        }
        var layout = new GlyphLayout(font, literal);
        float x = left + slice.offset();
        float y = textY + yOffset;
        float layoutBottom = font.isFlipped() ? y : y - layout.height;
        if (!finite(x, y, layoutBottom, layout.width, layout.height)
                || layout.width < 0 || layout.height < 0) {
            return Optional.empty();
        }
        var ink = renderedInk(layout, font, drawPosition.orElseThrow(),
                left, slice.offset(), textY, yOffset);
        if (ink.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Scene2dTextGeometry.Placement(x, y, y,
                new Bounds(x, layoutBottom, layout.width, layout.height), ink.orElseThrow()));
    }

    /** Pure equivalent of calculateOffsets; never moves cursor, selection or scroll state. */
    private static Slice visibleSlice(
            TextField field, float[] glyphs, float offset, float fontOffset, float width) {
        int cursor = Math.max(0, Math.min(field.getCursorPosition(), glyphs.length - 1));
        float distance = glyphs[Math.max(0, cursor - 1)] + offset;
        if (distance <= 0) {
            offset -= distance;
        } else {
            float minimum = glyphs[Math.min(glyphs.length - 1, cursor + 1)] - width;
            if (-offset < minimum) {
                offset = -minimum;
            }
        }
        float maximum = 0;
        float endWidth = glyphs[glyphs.length - 1];
        for (int i = glyphs.length - 2; i >= 0; i--) {
            if (endWidth - glyphs[i] > width) {
                break;
            }
            maximum = glyphs[i];
        }
        if (-offset > maximum) {
            offset = -maximum;
        }
        int start = 0;
        float startX = 0;
        for (int i = 0; i < glyphs.length; i++) {
            if (glyphs[i] >= -offset) {
                start = i;
                startX = glyphs[i];
                break;
            }
        }
        int end = start + 1;
        for (; end < glyphs.length; end++) {
            if (glyphs[end] > width - offset) {
                break;
            }
        }
        end = Math.max(0, end - 1);
        float textOffset;
        if ((field.getAlignment() & Align.left) == 0) {
            textOffset = width - glyphs[end] - fontOffset + startX;
            if ((field.getAlignment() & Align.center) != 0) {
                textOffset = Math.round(textOffset * .5f);
            }
        } else {
            textOffset = startX + offset;
        }
        return new Slice(start, end, textOffset);
    }

    /** Account for Group's temporary child translation before integer glyph snapping. */
    private static Optional<DrawPosition> drawPosition(TextField field) {
        if (field.getRotation() != 0 || field.getScaleX() != 1 || field.getScaleY() != 1) {
            // TextField.draw ignores its own rotation/scale; the general coordinate mapper cannot.
            return Optional.empty();
        }
        var translated = new ArrayList<Group>();
        boolean beforeTransform = true;
        int count = 0;
        for (Group parent = field.getParent(); parent != null; parent = parent.getParent()) {
            if (++count > MAX_ANCESTORS || !finite(parent.getX(), parent.getY(),
                    parent.getScaleX(), parent.getScaleY(), parent.getRotation())) {
                return Optional.empty();
            }
            if (!parent.isTransform()
                    && (parent.getRotation() != 0 || parent.getScaleX() != 1 || parent.getScaleY() != 1)) {
                return Optional.empty();
            }
            if (parent.isTransform()) {
                beforeTransform = false;
            } else if (beforeTransform) {
                translated.add(parent);
            }
        }
        float x = 0;
        float y = 0;
        for (int index = translated.size() - 1; index >= 0; index--) {
            x = translated.get(index).getX() + x;
            y = translated.get(index).getY() + y;
        }
        x = field.getX() + x;
        y = field.getY() + y;
        return finite(x, y) ? Optional.of(new DrawPosition(x, y)) : Optional.empty();
    }

    /** Mirrors BitmapFontCache.addText/addGlyph without drawing or changing the font's cache. */
    private static Optional<Bounds> renderedInk(
            GlyphLayout layout, BitmapFont font, DrawPosition position,
            float left, float textOffset, float textY, float yOffset) {
        float originX = position.x() + left + textOffset;
        float originY = position.y() + textY + yOffset + font.getData().ascent;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (var run : layout.runs) {
            float glyphX = originX + run.x;
            float glyphY = originY + run.y;
            for (int index = 0; index < run.glyphs.size; index++) {
                glyphX += run.xAdvances.get(index);
                var glyph = run.glyphs.get(index);
                float x = glyphX + glyph.xoffset * font.getScaleX();
                float y = glyphY + glyph.yoffset * font.getScaleY();
                float width = glyph.width * font.getScaleX();
                float height = glyph.height * font.getScaleY();
                if (!finite(x, y, width, height)) {
                    return Optional.empty();
                }
                if (font.getCache().usesIntegerPositions()) {
                    x = Math.round(x);
                    y = Math.round(y);
                    width = Math.round(width);
                    height = Math.round(height);
                }
                minX = Math.min(minX, Math.min(x, x + width));
                minY = Math.min(minY, Math.min(y, y + height));
                maxX = Math.max(maxX, Math.max(x, x + width));
                maxY = Math.max(maxY, Math.max(y, y + height));
            }
        }
        if (layout.glyphCount == 0) {
            minX = maxX = originX;
            minY = maxY = originY;
        }
        float localX = minX - position.x();
        float localY = minY - position.y();
        if (!finite(localX, localY, maxX - minX, maxY - minY)) {
            return Optional.empty();
        }
        return Optional.of(new Bounds(localX, localY, maxX - minX, maxY - minY));
    }

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static Access access() {
        if (!"1.14.2".equals(Version.VERSION)) {
            return null;
        }
        try {
            var lookup = MethodHandles.privateLookupIn(TextField.class, MethodHandles.lookup());
            return new Access(lookup.findVarHandle(TextField.class, "displayText", CharSequence.class),
                    lookup.findVarHandle(TextField.class, "glyphPositions", FloatArray.class),
                    lookup.findVarHandle(TextField.class, "fontOffset", float.class),
                    lookup.findVarHandle(TextField.class, "textHeight", float.class),
                    lookup.findVarHandle(TextField.class, "renderOffset", float.class));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private record Slice(int start, int end, float offset) {}
    private record DrawPosition(float x, float y) {}
    private record Access(VarHandle displayText, VarHandle glyphPositions, VarHandle fontOffset,
            VarHandle textHeight, VarHandle renderOffset) {}
}
