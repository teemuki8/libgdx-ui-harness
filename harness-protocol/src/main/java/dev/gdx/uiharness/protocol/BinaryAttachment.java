package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.visual.VisualHeatmap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bounded immutable byte payload for internal artifact publication, backed by a read-only
 * {@link ByteBuffer}. There is no {@code byte[]} accessor and no mutable array can escape;
 * consumers read through fresh read-only views or the copy-free {@link #writeTo(OutputStream)}
 * bridge. Not part of the supported public API.
 */
public final class BinaryAttachment {
    /** Hard bound shared with the protocol screenshot limit. */
    static final int MAX_BYTES = HarnessResponse.Result.Screenshot.MAX_PNG_BYTES;

    private final ByteBuffer bytes; // read-only; position 0, limit = length
    private final String sha256;

    private BinaryAttachment(ByteBuffer bytes, String sha256) {
        this.bytes = bytes;
        this.sha256 = sha256;
    }

    /**
     * Defensively copies the supplied bytes. The caller array is cloned into a locally owned
     * array FIRST and the digest is computed over that owned clone only, so a concurrent caller
     * mutation can never desynchronize the digest from the stored content.
     */
    public static BinaryAttachment of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        requireBounded(bytes.length);
        byte[] owned = bytes.clone();
        return new BinaryAttachment(
                ByteBuffer.wrap(owned).asReadOnlyBuffer(), sha256(ByteBuffer.wrap(owned)));
    }

    /**
     * Trusted internal transfer from an immutable capture owner. Accepts NO caller-supplied
     * buffer: the factory reads the owner's read-only {@code pngView()} itself, so a caller can
     * never supply a read-only alias over mutable storage. Retains a duplicate slice without
     * copying; the backing array never escapes and can never be mutated through this value.
     */
    static BinaryAttachment takeCaptured(CapturedImage image) {
        Objects.requireNonNull(image, "image");
        return takeView(image.pngView());
    }

    /** Trusted internal transfer from an immutable heatmap owner (same provenance contract). */
    static BinaryAttachment takeCaptured(VisualHeatmap heatmap) {
        Objects.requireNonNull(heatmap, "heatmap");
        return takeView(heatmap.pngView());
    }

    private static BinaryAttachment takeView(ByteBuffer readOnly) {
        requireBounded(readOnly.remaining());
        ByteBuffer retained = readOnly.duplicate();
        return new BinaryAttachment(retained.asReadOnlyBuffer(), sha256(retained));
    }

    /** Returns the number of owned bytes. */
    public int length() {
        return bytes.remaining();
    }

    /** Returns the canonical lowercase SHA-256 of the owned bytes. */
    public String sha256() {
        return sha256;
    }

    /** Returns a fresh read-only view of the owned bytes. */
    public ByteBuffer asByteBuffer() {
        return bytes.duplicate();
    }

    /** Writes the owned bytes to the supplied sink in bounded chunks without a full copy. */
    public void writeTo(OutputStream sink) throws IOException {
        Objects.requireNonNull(sink, "sink");
        ByteBuffer local = bytes.duplicate();
        byte[] chunk = new byte[8_192];
        while (local.hasRemaining()) {
            int count = Math.min(chunk.length, local.remaining());
            local.get(chunk, 0, count);
            sink.write(chunk, 0, count);
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof BinaryAttachment that
                && Arrays.equals(readAll(bytes), readAll(that.bytes));
    }

    @Override public int hashCode() {
        return Arrays.hashCode(readAll(bytes));
    }

    private static void requireBounded(int length) {
        if (length < 1 || length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "attachment length must be between 1 and " + MAX_BYTES + ": " + length);
        }
    }

    private static byte[] readAll(ByteBuffer view) {
        ByteBuffer local = view.duplicate();
        byte[] bytes = new byte[local.remaining()];
        local.get(bytes);
        return bytes;
    }

    private static String sha256(ByteBuffer view) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer local = view.duplicate();
            byte[] chunk = new byte[8_192];
            while (local.hasRemaining()) {
                int count = Math.min(chunk.length, local.remaining());
                local.get(chunk, 0, count);
                digest.update(chunk, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }
}
