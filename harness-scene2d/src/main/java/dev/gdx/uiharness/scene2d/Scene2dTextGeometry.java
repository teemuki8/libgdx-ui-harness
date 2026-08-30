package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.GlyphLayout.GlyphRun;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import dev.gdx.uiharness.core.model.Bounds;

/** Shared intrinsic glyph layout and ink placement for a real Scene2D Label. */
final class Scene2dTextGeometry {
    private Scene2dTextGeometry() {}

    static Placement placement(Label label) {
        GlyphLayout layout = label.getGlyphLayout();
        Bounds unplacedInk =
                inkBounds(layout, 0, 0, label.getFontScaleX(), label.getFontScaleY());
        Bounds renderedInk = renderedInk(label, layout);
        double originX = renderedInk.x() - unplacedInk.x();
        double originY = renderedInk.y() - unplacedInk.y();
        Bounds layoutBounds =
                new Bounds(originX, originY - layout.height, layout.width, layout.height);
        double baseline = layout.runs.isEmpty()
                ? originY
                : originY + layout.runs.first().y;
        return new Placement(originX, originY, baseline, layoutBounds, renderedInk);
    }

    private static Bounds renderedInk(Label label, GlyphLayout layout) {
        int usedPages = usedPageCount(layout);
        if (usedPages == 0) {
            return new Bounds(0, 0, 0, 0);
        }
        GlyphCaptureBatch batch = new GlyphCaptureBatch(usedPages);
        label.draw(batch, 1);
        Bounds parentBounds = batch.bounds();
        return new Bounds(
                parentBounds.x() - label.getX(),
                parentBounds.y() - label.getY(),
                parentBounds.width(),
                parentBounds.height());
    }

    private static int usedPageCount(GlyphLayout layout) {
        int highestPage = -1;
        for (GlyphRun run : layout.runs) {
            for (Glyph glyph : run.glyphs) {
                highestPage = Math.max(highestPage, glyph.page);
            }
        }
        if (highestPage < 0) {
            return 0;
        }
        boolean[] used = new boolean[highestPage + 1];
        int count = 0;
        for (GlyphRun run : layout.runs) {
            for (Glyph glyph : run.glyphs) {
                if (!used[glyph.page]) {
                    used[glyph.page] = true;
                    count++;
                }
            }
        }
        return count;
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
    private static final class GlyphCaptureBatch implements Batch {
        private final Color color = new Color(Color.WHITE);
        private final Matrix4 projection = new Matrix4();
        private final Matrix4 transform = new Matrix4();
        private final double[] minX;
        private final double[] minY;
        private final double[] maxX;
        private final double[] maxY;
        private int calls;

        GlyphCaptureBatch(int retainedCalls) {
            minX = new double[retainedCalls];
            minY = new double[retainedCalls];
            maxX = new double[retainedCalls];
            maxY = new double[retainedCalls];
        }

        Bounds bounds() {
            if (calls < minX.length) {
                throw new IllegalStateException("Label rendered fewer font pages than its layout");
            }
            double left = Double.POSITIVE_INFINITY;
            double bottom = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double top = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < minX.length; index++) {
                left = Math.min(left, minX[index]);
                bottom = Math.min(bottom, minY[index]);
                right = Math.max(right, maxX[index]);
                top = Math.max(top, maxY[index]);
            }
            return new Bounds(left, bottom, right - left, top - bottom);
        }

        @Override public void draw(
                Texture texture, float[] vertices, int offset, int count) {
            int slot = calls++ % minX.length;
            double left = Double.POSITIVE_INFINITY;
            double bottom = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double top = Double.NEGATIVE_INFINITY;
            for (int index = offset; index < offset + count; index += 5) {
                left = Math.min(left, vertices[index]);
                bottom = Math.min(bottom, vertices[index + 1]);
                right = Math.max(right, vertices[index]);
                top = Math.max(top, vertices[index + 1]);
            }
            minX[slot] = left;
            minY[slot] = bottom;
            maxX[slot] = right;
            maxY[slot] = top;
        }

        @Override public void begin() {}
        @Override public void end() {}
        @Override public void setColor(Color tint) { color.set(tint); }
        @Override public void setColor(float r, float g, float b, float a) {
            color.set(r, g, b, a);
        }
        @Override public Color getColor() { return color; }
        @Override public void setPackedColor(float packedColor) {
            Color.abgr8888ToColor(color, packedColor);
        }
        @Override public float getPackedColor() { return color.toFloatBits(); }
        @Override public void draw(
                Texture texture, float x, float y, float originX, float originY,
                float width, float height, float scaleX, float scaleY, float rotation,
                int srcX, int srcY, int srcWidth, int srcHeight,
                boolean flipX, boolean flipY) {}
        @Override public void draw(
                Texture texture, float x, float y, float width, float height,
                int srcX, int srcY, int srcWidth, int srcHeight,
                boolean flipX, boolean flipY) {}
        @Override public void draw(
                Texture texture, float x, float y,
                int srcX, int srcY, int srcWidth, int srcHeight) {}
        @Override public void draw(
                Texture texture, float x, float y, float width, float height,
                float sourceX, float sourceY, float sourceWidth, float sourceHeight) {}
        @Override public void draw(Texture texture, float x, float y) {}
        @Override public void draw(
                Texture texture, float x, float y, float width, float height) {}
        @Override public void draw(TextureRegion region, float x, float y) {}
        @Override public void draw(
                TextureRegion region, float x, float y, float width, float height) {}
        @Override public void draw(
                TextureRegion region, float x, float y, float originX, float originY,
                float width, float height, float scaleX, float scaleY, float rotation) {}
        @Override public void draw(
                TextureRegion region, float x, float y, float originX, float originY,
                float width, float height, float scaleX, float scaleY, float rotation,
                boolean clockwise) {}
        @Override public void draw(
                TextureRegion region, float width, float height, Affine2 affine) {}
        @Override public void flush() {}
        @Override public void disableBlending() {}
        @Override public void enableBlending() {}
        @Override public void setBlendFunction(int source, int destination) {}
        @Override public void setBlendFunctionSeparate(
                int sourceColor, int destinationColor, int sourceAlpha, int destinationAlpha) {}
        @Override public int getBlendSrcFunc() { return 0; }
        @Override public int getBlendDstFunc() { return 0; }
        @Override public int getBlendSrcFuncAlpha() { return 0; }
        @Override public int getBlendDstFuncAlpha() { return 0; }
        @Override public Matrix4 getProjectionMatrix() { return projection; }
        @Override public Matrix4 getTransformMatrix() { return transform; }
        @Override public void setProjectionMatrix(Matrix4 value) { projection.set(value); }
        @Override public void setTransformMatrix(Matrix4 value) { transform.set(value); }
        @Override public void setShader(ShaderProgram shader) {}
        @Override public ShaderProgram getShader() { return null; }
        @Override public boolean isBlendingEnabled() { return false; }
        @Override public boolean isDrawing() { return true; }
        @Override public void dispose() {}
    }


    record Placement(
            double originX,
            double originY,
            double baselineY,
            Bounds layoutBounds,
            Bounds inkBounds) {}
}
