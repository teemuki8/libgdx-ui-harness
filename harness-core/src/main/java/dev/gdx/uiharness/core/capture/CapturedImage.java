package dev.gdx.uiharness.core.capture;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable completed-frame PNG evidence and its framebuffer metadata. */
public record CapturedImage(
        byte[] pngBytes,
        String sha256,
        long frame,
        long revision,
        int width,
        int height,
        Scale scale) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** Validates metadata and takes ownership of a defensive byte copy. */
    public CapturedImage {
        Objects.requireNonNull(pngBytes, "pngBytes");
        if (pngBytes.length == 0) {
            throw new IllegalArgumentException("pngBytes must not be empty");
        }
        pngBytes = pngBytes.clone();
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hex digits");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be non-negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        scale = Objects.requireNonNull(scale, "scale");
    }

    /** Returns a defensive copy of the encoded PNG bytes. */
    @Override public byte[] pngBytes() {
        return pngBytes.clone();
    }

    /** Framebuffer pixels per logical window unit on each axis. */
    public record Scale(double x, double y) {
        /** Validates finite positive scale components. */
        public Scale {
            requirePositiveFinite(x, "x");
            requirePositiveFinite(y, "y");
        }

        private static void requirePositiveFinite(double value, String name) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
        }
    }
}
