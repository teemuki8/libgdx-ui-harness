package dev.gdx.uiharness.scene2d;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Capture identity and framebuffer facts used to complete Scene2D typography evidence. */
public record TypographyCaptureContext(
        String applicationId,
        String viewportId,
        String currentArtifactId,
        String captureSha256,
        int windowWidth,
        int windowHeight,
        int framebufferWidth,
        int framebufferHeight,
        Map<String, Double> rasterResiduals) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** Validates stable identities, capture digest, dimensions, and residuals. */
    public TypographyCaptureContext {
        requireNonBlank(applicationId, "applicationId");
        requireNonBlank(viewportId, "viewportId");
        requireNonBlank(currentArtifactId, "currentArtifactId");
        if (!SHA_256.matcher(Objects.requireNonNull(captureSha256, "captureSha256")).matches()) {
            throw new IllegalArgumentException(
                    "captureSha256 must contain 64 lowercase hex digits");
        }
        if (windowWidth <= 0 || windowHeight <= 0
                || framebufferWidth <= 0 || framebufferHeight <= 0) {
            throw new IllegalArgumentException(
                    "window and framebuffer dimensions must be positive");
        }
        rasterResiduals =
                Map.copyOf(Objects.requireNonNull(rasterResiduals, "rasterResiduals"));
        rasterResiduals.forEach((controlId, residual) -> {
            requireNonBlank(controlId, "rasterResidual controlId");
            if (residual == null || !Double.isFinite(residual) || residual < 0) {
                throw new IllegalArgumentException(
                        "raster residuals must be finite and non-negative");
            }
        });
    }

    private static void requireNonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
