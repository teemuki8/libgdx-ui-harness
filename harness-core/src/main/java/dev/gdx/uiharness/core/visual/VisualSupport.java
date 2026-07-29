package dev.gdx.uiharness.core.visual;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

final class VisualSupport {
    private static final int MAX_TEXT = 16_384;

    private VisualSupport() {}

    static String identifier(String value, String name) {
        text(value, name);
        if (value.length() > 256 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(name + " is not a bounded identifier");
        }
        return value;
    }

    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_TEXT) {
            throw new IllegalArgumentException(name + " is blank or oversized");
        }
        return value;
    }

    static byte[] verifiedBytes(byte[] source, String expectedSha256, String name) {
        Objects.requireNonNull(source, name + " bytes");
        if (source.length == 0 || source.length > 64 * 1_024 * 1_024) {
            throw new IllegalArgumentException(name + " bytes are empty or oversized");
        }
        String observed;
        try {
            observed = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
        if (!observed.equals(expectedSha256)) {
            throw new IllegalArgumentException(name + " SHA-256 mismatch");
        }
        return source.clone();
    }
}
