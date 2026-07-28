package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Streaming RGBA-to-PNG encoder with bounded output and simultaneous SHA-256. */
public final class PngEncoder {
    private static final byte[] SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] IHDR = {0x49, 0x48, 0x44, 0x52};
    private static final byte[] IDAT = {0x49, 0x44, 0x41, 0x54};
    private static final byte[] IEND = {0x49, 0x45, 0x4E, 0x44};
    private static final int DEFLATE_BUFFER_SIZE = 8_192;

    /**
     * Encodes bottom-left-origin tightly packed RGBA pixels as a conventional top-left PNG.
     * The byte cap is enforced before every output-buffer growth.
     */
    public Encoded encode(
            ByteBuffer bottomLeftRgba, int width, int height, int maxPngBytes) {
        Objects.requireNonNull(bottomLeftRgba, "bottomLeftRgba");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        if (maxPngBytes <= 0) {
            throw new IllegalArgumentException("maxPngBytes must be positive");
        }
        int rowBytes = checkedRowBytes(width);
        long required = Math.multiplyExact((long) rowBytes, height);
        if (required > bottomLeftRgba.remaining()) {
            throw new IllegalArgumentException("pixel buffer is smaller than the RGBA geometry");
        }

        BoundedDigestOutput output = new BoundedDigestOutput(maxPngBytes);
        output.write(SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8;
        header[9] = 6;
        writeChunk(output, IHDR, header, header.length);
        writePixels(output, bottomLeftRgba.slice(), rowBytes, height);
        writeChunk(output, IEND, new byte[0], 0);
        return output.finish();
    }

    private static void writePixels(
            BoundedDigestOutput output, ByteBuffer pixels, int rowBytes, int height) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        byte[] scanline = new byte[Math.addExact(rowBytes, 1)];
        byte[] compressed = new byte[DEFLATE_BUFFER_SIZE];
        try {
            for (int outputRow = 0; outputRow < height; outputRow++) {
                int sourceRow = height - 1 - outputRow;
                int sourceOffset = Math.multiplyExact(sourceRow, rowBytes);
                scanline[0] = 0;
                ByteBuffer source = pixels.duplicate();
                source.position(sourceOffset);
                source.get(scanline, 1, rowBytes);
                deflater.setInput(scanline);
                drainAvailable(deflater, output, compressed);
            }
            deflater.finish();
            while (!deflater.finished()) {
                int count = deflater.deflate(compressed);
                if (count <= 0) {
                    throw new HarnessException(
                            ErrorCode.CAPTURE_FAILURE,
                            "PNG compression made no forward progress",
                            ErrorEvidence.empty());
                }
                writeChunk(output, IDAT, compressed, count);
            }
        } finally {
            deflater.end();
        }
    }

    private static void drainAvailable(
            Deflater deflater, BoundedDigestOutput output, byte[] compressed) {
        while (!deflater.needsInput()) {
            int count = deflater.deflate(compressed);
            if (count == 0) {
                return;
            }
            writeChunk(output, IDAT, compressed, count);
        }
    }

    private static void writeChunk(
            BoundedDigestOutput output, byte[] type, byte[] payload, int length) {
        output.writeInt(length);
        output.write(type);
        output.write(payload, 0, length);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(payload, 0, length);
        output.writeInt((int) crc.getValue());
    }

    private static int checkedRowBytes(int width) {
        long rowBytes = Math.multiplyExact((long) width, 4L);
        if (rowBytes > Integer.MAX_VALUE - 1L) {
            throw limitExceeded("rowBytes", rowBytes, Integer.MAX_VALUE - 1L);
        }
        return (int) rowBytes;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static HarnessException limitExceeded(
            String dimension, long actual, long limit) {
        return new HarnessException(
                ErrorCode.LIMIT_EXCEEDED,
                dimension + " exceeds its configured capture limit",
                ErrorEvidence.ofDetails(Map.of(
                        "actual", Long.toString(actual),
                        "dimension", dimension,
                        "limit", Long.toString(limit))));
    }

    /** Exact encoded bytes and lowercase SHA-256 of those bytes. */
    public record Encoded(byte[] bytes, String sha256) {
        /** Takes ownership of an immutable byte copy and validates the digest. */
        public Encoded {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            sha256 = Objects.requireNonNull(sha256, "sha256");
        }

        /** Returns a defensive copy of the encoded bytes. */
        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final class BoundedDigestOutput {
        private final int limit;
        private final MessageDigest digest;
        private byte[] bytes;
        private int size;

        BoundedDigestOutput(int limit) {
            this.limit = limit;
            bytes = new byte[Math.min(limit, 1_024)];
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError("SHA-256 is required by the JDK", exception);
            }
        }

        void write(byte[] source) {
            write(source, 0, source.length);
        }

        void write(byte[] source, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, source.length);
            long required = (long) size + length;
            if (required > limit) {
                throw limitExceeded("pngBytes", required, limit);
            }
            ensureCapacity((int) required);
            System.arraycopy(source, offset, bytes, size, length);
            digest.update(source, offset, length);
            size += length;
        }

        void writeInt(int value) {
            byte[] encoded = {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
            };
            write(encoded);
        }

        Encoded finish() {
            byte[] result = Arrays.copyOf(bytes, size);
            String hash = HexFormat.of().formatHex(digest.digest());
            return new Encoded(result, hash);
        }

        private void ensureCapacity(int required) {
            if (required <= bytes.length) {
                return;
            }
            int doubled = bytes.length <= limit / 2 ? bytes.length * 2 : limit;
            int capacity = Math.max(required, doubled);
            bytes = Arrays.copyOf(bytes, capacity);
        }
    }
}
