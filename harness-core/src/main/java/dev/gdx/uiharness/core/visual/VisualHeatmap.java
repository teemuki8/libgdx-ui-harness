package dev.gdx.uiharness.core.visual;

import java.nio.ByteBuffer;

/** Immutable bounded PNG heatmap for one exact comparison. */
public record VisualHeatmap(
        byte[] pngBytes, String sha256, int width, int height) {
    /** Verifies dimensions, encoded bytes, and content identity. */
    public VisualHeatmap {
        pngBytes = VisualSupport.verifiedBytes(pngBytes, sha256, "visual heatmap");
        if (width <= 0 || height <= 0 || (long) width * height > 33_554_432L) {
            throw new IllegalArgumentException("invalid visual heatmap dimensions");
        }
    }

    @Override public byte[] pngBytes() {
        return pngBytes.clone();
    }

    /** Returns a fresh read-only view of the owned PNG bytes; the backing array never escapes. */
    public ByteBuffer pngView() {
        return ByteBuffer.wrap(pngBytes).asReadOnlyBuffer();
    }
}
