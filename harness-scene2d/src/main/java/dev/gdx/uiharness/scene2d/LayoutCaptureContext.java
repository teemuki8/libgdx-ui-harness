package dev.gdx.uiharness.scene2d;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Capture and viewport identity plus the bounded selected layout controls. */
public record LayoutCaptureContext(
        String applicationId,
        String viewportId,
        String currentArtifactId,
        String captureSha256,
        int windowWidth,
        int windowHeight,
        int framebufferWidth,
        int framebufferHeight,
        long layoutRevision,
        Set<String> controlIds) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** Validates explicit display/capture facts and selected IDs. */
    public LayoutCaptureContext {
        nonBlank(applicationId, "applicationId");
        nonBlank(viewportId, "viewportId");
        nonBlank(currentArtifactId, "currentArtifactId");
        if (!SHA_256.matcher(Objects.requireNonNull(captureSha256, "captureSha256")).matches()) {
            throw new IllegalArgumentException("captureSha256 must be lowercase SHA-256");
        }
        if (windowWidth <= 0 || windowHeight <= 0
                || framebufferWidth <= 0 || framebufferHeight <= 0) {
            throw new IllegalArgumentException("display dimensions must be positive");
        }
        if (layoutRevision < 0) {
            throw new IllegalArgumentException("layoutRevision must be non-negative");
        }
        controlIds = Set.copyOf(Objects.requireNonNull(controlIds, "controlIds"));
        if (controlIds.isEmpty() || controlIds.size() > 256) {
            throw new IllegalArgumentException(
                    "controlIds must contain between 1 and 256 entries");
        }
        controlIds.forEach(value -> nonBlank(value, "controlId"));
    }

    private static void nonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
    }
}
