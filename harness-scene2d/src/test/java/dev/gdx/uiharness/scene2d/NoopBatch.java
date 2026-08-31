package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.Matrix4;

final class NoopBatch implements Batch {
    private final Color color = new Color(Color.WHITE);
    private final Matrix4 projection = new Matrix4();
    private final Matrix4 transform = new Matrix4();
    private boolean drawing;

    static void installGraphics() {
        java.lang.reflect.InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("getWidth")
                    || method.getName().equals("getHeight")
                    || method.getName().equals("getBackBufferWidth")
                    || method.getName().equals("getBackBufferHeight")) {
                return method.getName().contains("Height") ? 600 : 800;
            }
            Class<?> type = method.getReturnType();
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == float.class) {
                return 0f;
            }
            if (type == double.class) {
                return 0d;
            }
            if (type == long.class) {
                return 0L;
            }
            return 0;
        };
        ClassLoader loader = NoopBatch.class.getClassLoader();
        com.badlogic.gdx.Gdx.app = (com.badlogic.gdx.Application)
                java.lang.reflect.Proxy.newProxyInstance(
                        loader, new Class<?>[] {com.badlogic.gdx.Application.class}, handler);
        com.badlogic.gdx.Gdx.files = (com.badlogic.gdx.Files)
                java.lang.reflect.Proxy.newProxyInstance(
                        loader, new Class<?>[] {com.badlogic.gdx.Files.class}, handler);
        com.badlogic.gdx.Gdx.graphics = (com.badlogic.gdx.Graphics)
                java.lang.reflect.Proxy.newProxyInstance(
                        loader, new Class<?>[] {com.badlogic.gdx.Graphics.class}, handler);
        com.badlogic.gdx.Gdx.gl = (com.badlogic.gdx.graphics.GL20)
                java.lang.reflect.Proxy.newProxyInstance(
                        loader, new Class<?>[] {com.badlogic.gdx.graphics.GL20.class}, handler);
        com.badlogic.gdx.Gdx.gl20 = com.badlogic.gdx.Gdx.gl;
    }

    @Override public void begin() {
        drawing = true;
    }

    @Override public void end() {
        drawing = false;
    }

    @Override public void setColor(Color tint) {
        color.set(tint);
    }

    @Override public void setColor(float red, float green, float blue, float alpha) {
        color.set(red, green, blue, alpha);
    }

    @Override public Color getColor() {
        return color;
    }

    @Override public void setPackedColor(float packedColor) {
        Color.abgr8888ToColor(color, packedColor);
    }

    @Override public float getPackedColor() {
        return color.toFloatBits();
    }

    @Override public void draw(Texture texture, float x, float y, float originX, float originY,
            float width, float height, float scaleX, float scaleY, float rotation, int srcX,
            int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {}

    @Override public void draw(Texture texture, float x, float y, float width, float height,
            int srcX, int srcY, int srcWidth, int srcHeight, boolean flipX, boolean flipY) {}

    @Override public void draw(
            Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {}

    @Override public void draw(Texture texture, float x, float y, float width, float height,
            float sourceX, float sourceY, float sourceWidth, float sourceHeight) {}

    @Override public void draw(Texture texture, float x, float y) {}

    @Override public void draw(Texture texture, float x, float y, float width, float height) {}

    @Override public void draw(Texture texture, float[] spriteVertices, int offset, int count) {}

    @Override public void draw(TextureRegion region, float x, float y) {}

    @Override public void draw(TextureRegion region, float x, float y, float width, float height) {}

    @Override public void draw(TextureRegion region, float x, float y, float originX, float originY,
            float width, float height, float scaleX, float scaleY, float rotation) {}

    @Override public void draw(TextureRegion region, float x, float y, float originX, float originY,
            float width, float height, float scaleX, float scaleY, float rotation,
            boolean clockwise) {}

    @Override public void draw(TextureRegion region, float width, float height, Affine2 transform) {}

    @Override public void flush() {}

    @Override public void disableBlending() {}

    @Override public void enableBlending() {}

    @Override public void setBlendFunction(int source, int destination) {}

    @Override public void setBlendFunctionSeparate(
            int sourceColor, int destinationColor, int sourceAlpha, int destinationAlpha) {}

    @Override public int getBlendSrcFunc() {
        return 0;
    }

    @Override public int getBlendDstFunc() {
        return 0;
    }

    @Override public int getBlendSrcFuncAlpha() {
        return 0;
    }

    @Override public int getBlendDstFuncAlpha() {
        return 0;
    }

    @Override public Matrix4 getProjectionMatrix() {
        return projection;
    }

    @Override public Matrix4 getTransformMatrix() {
        return transform;
    }

    @Override public void setProjectionMatrix(Matrix4 value) {
        projection.set(value);
    }

    @Override public void setTransformMatrix(Matrix4 value) {
        transform.set(value);
    }

    @Override public void setShader(ShaderProgram shader) {}

    @Override public ShaderProgram getShader() {
        return null;
    }

    @Override public boolean isBlendingEnabled() {
        return false;
    }

    @Override public boolean isDrawing() {
        return drawing;
    }

    @Override public void dispose() {}
}
