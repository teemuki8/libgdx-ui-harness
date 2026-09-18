package dev.gdx.uiharness.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.BufferUtils;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;

/**
 * Bounded RGBA readback of a bottom-left-origin back-buffer region, shared by the session capture and
 * the session-free tool capture so both paths read the framebuffer identically.
 */
final class BackBufferReadback {
    private BackBufferReadback() {
    }

    /** Reads one region; the returned buffer is rewound and ready to encode. */
    static ByteBuffer read(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        long pixels = (long) width * height;
        long bytes = Math.multiplyExact(pixels, 4L);
        if (bytes > Integer.MAX_VALUE) {
            throw new HarnessException(
                    ErrorCode.LIMIT_EXCEEDED,
                    "readback exceeds the addressable pixel buffer",
                    ErrorEvidence.ofDetails(Map.of("pixels", Long.toString(pixels))));
        }
        ByteBuffer rgba = BufferUtils.newByteBuffer((int) bytes);
        IntBuffer previousPackAlignment = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetIntegerv(GL20.GL_PACK_ALIGNMENT, previousPackAlignment);
        try {
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
            Gdx.gl.glReadPixels(x, y, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, rgba);
        } catch (RuntimeException | LinkageError failure) {
            throw new HarnessException(
                    ErrorCode.CAPTURE_FAILURE,
                    "LWJGL3 framebuffer readback failed",
                    ErrorEvidence.ofDetails(Map.of(
                            "width", Integer.toString(width),
                            "height", Integer.toString(height))),
                    failure);
        } finally {
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, previousPackAlignment.get(0));
        }
        rgba.rewind();
        return rgba;
    }
}
