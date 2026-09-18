package dev.gdx.uiharness.lwjgl3;

import com.badlogic.gdx.Gdx;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Session-free bounded framebuffer capture for tools and tests that render their own window without a
 * harness session. The PNG bytes come from the same encoder the session capture uses, so a tool's
 * image is byte-identical to what {@code ui_screenshot} returns for the same completed frame, and
 * framebuffer row order is handled in one place rather than by every tool author.
 *
 * <p>Readback must run on the graphics thread: libGDX GL access is render-thread confined.
 */
public final class Lwjgl3FramebufferCapture {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private Lwjgl3FramebufferCapture() {
    }

    /** Output ceilings, enforced before allocation. */
    public record Limits(int maxPixels, int maxPngBytes) {
        /** Validates both ceilings. */
        public Limits {
            if (maxPixels <= 0) {
                throw new IllegalArgumentException("maxPixels must be positive");
            }
            if (maxPngBytes <= 0) {
                throw new IllegalArgumentException("maxPngBytes must be positive");
            }
        }

        /** Sixteen megapixels and eight mebibytes: the ceilings a bounded tool capture should carry. */
        public static Limits defaults() {
            return new Limits(16 * 1024 * 1024, 8 * 1024 * 1024);
        }
    }

    /** One readback: top-left-origin PNG bytes and the SHA-256 of exactly those bytes. */
    public record Image(byte[] pngBytes, String sha256, int width, int height) {
        /** Validates metadata and takes ownership of a defensive byte copy. */
        public Image {
            Objects.requireNonNull(pngBytes, "pngBytes");
            if (pngBytes.length == 0) {
                throw new IllegalArgumentException("pngBytes must not be empty");
            }
            pngBytes = pngBytes.clone();
            sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
            if (!SHA_256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 must contain 64 lowercase hex digits");
            }
            if (width <= 0) {
                throw new IllegalArgumentException("width must be positive");
            }
            if (height <= 0) {
                throw new IllegalArgumentException("height must be positive");
            }
        }

        /** Returns a defensive copy of the PNG bytes. */
        @Override public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    /** Captures the whole back buffer of the current window. */
    public static Image captureBackBuffer(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        return captureRegion(
                0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), limits);
    }

    /** Captures a bottom-left-origin region of the back buffer. */
    public static Image captureRegion(int x, int y, int width, int height, Limits limits) {
        Objects.requireNonNull(limits, "limits");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        long pixels = (long) width * height;
        if (pixels > limits.maxPixels()) {
            throw limitExceeded("pixels", pixels, limits.maxPixels());
        }
        ByteBuffer rgba = BackBufferReadback.read(x, y, width, height);
        PngEncoder.Encoded encoded = new PngEncoder().encode(rgba, width, height, limits.maxPngBytes());
        return new Image(encoded.bytes(), encoded.sha256(), width, height);
    }

    private static HarnessException limitExceeded(String dimension, long actual, long limit) {
        return new HarnessException(
                ErrorCode.LIMIT_EXCEEDED,
                dimension + " exceeds its configured capture limit",
                ErrorEvidence.ofDetails(Map.of(
                        "actual", Long.toString(actual),
                        "dimension", dimension,
                        "limit", Long.toString(limit))));
    }
}
