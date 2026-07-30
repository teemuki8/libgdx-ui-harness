package dev.gdx.uiharness.core.typography;

import dev.gdx.uiharness.core.capture.CapturedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable named typography raster and per-control expectations. */
public record TypographyReference(
        String referenceId,
        String applicationId,
        String viewportId,
        String referenceArtifactId,
        byte[] pngBytes,
        String sha256,
        int width,
        int height,
        CapturedImage.Scale scale,
        List<TypographyControlReference> controls) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** Copies the raster and indexes unique bounded control references. */
    public TypographyReference {
        requireNonBlank(referenceId, "referenceId");
        requireNonBlank(applicationId, "applicationId");
        requireNonBlank(viewportId, "viewportId");
        requireNonBlank(referenceArtifactId, "referenceArtifactId");
        pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        if (pngBytes.length == 0) {
            throw new IllegalArgumentException("pngBytes must not be empty");
        }
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hex digits");
        }
        if (!sha256.equals(sha256(pngBytes))) {
            throw new IllegalArgumentException("sha256 must describe pngBytes");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("reference dimensions must be positive");
        }
        Objects.requireNonNull(scale, "scale");
        controls = List.copyOf(Objects.requireNonNull(controls, "controls"));
        if (controls.isEmpty() || controls.size() > 256) {
            throw new IllegalArgumentException("controls must contain between 1 and 256 entries");
        }
        LinkedHashMap<String, TypographyControlReference> unique = new LinkedHashMap<>();
        for (TypographyControlReference control : controls) {
            if (unique.putIfAbsent(control.controlId(), control) != null) {
                throw new IllegalArgumentException(
                        "duplicate typography control: " + control.controlId());
            }
        }
    }

    /** Returns a defensive raster copy. */
    @Override public byte[] pngBytes() {
        return pngBytes.clone();
    }

    /** Returns references indexed in declared order. */
    public Map<String, TypographyControlReference> controlsById() {
        LinkedHashMap<String, TypographyControlReference> result = new LinkedHashMap<>();
        controls.forEach(control -> result.put(control.controlId(), control));
        return Collections.unmodifiableMap(result);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
