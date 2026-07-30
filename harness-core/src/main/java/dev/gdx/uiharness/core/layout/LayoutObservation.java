package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.DisplayObservation;
import dev.gdx.uiharness.core.typography.TransformChain;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Capture-bound current layout and clip evidence for one selected actor. */
public record LayoutObservation(
        String schemaVersion,
        String controlId,
        String actorId,
        String parentActorId,
        String layoutOwnerId,
        String scrollOwnerId,
        String observedClipOwnerId,
        String layoutRole,
        long revision,
        long frame,
        long layoutRevision,
        String currentArtifactId,
        String captureSha256,
        String layoutSha256,
        DisplayObservation display,
        TransformChain transforms,
        List<CoordinateBounds> bounds,
        LayoutPadding padding,
        List<LayoutClip> clipChain,
        CoordinateBounds visibleIntersection,
        LayoutScroll scroll) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final EnumSet<CoordinateSpace> SPACES = EnumSet.of(
            CoordinateSpace.LOCAL,
            CoordinateSpace.STAGE,
            CoordinateSpace.SCREEN,
            CoordinateSpace.FRAMEBUFFER);

    /** Validates identities, capture binding, mappings, and bounded geometry. */
    public LayoutObservation {
        if (!"layout/v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be layout/v1");
        }
        LayoutSupport.nonBlank(controlId, "controlId");
        LayoutSupport.nonBlank(actorId, "actorId");
        LayoutSupport.nonBlank(parentActorId, "parentActorId");
        LayoutSupport.nonBlank(layoutOwnerId, "layoutOwnerId");
        LayoutSupport.optionalId(scrollOwnerId, "scrollOwnerId");
        LayoutSupport.optionalId(observedClipOwnerId, "observedClipOwnerId");
        LayoutSupport.nonBlank(layoutRole, "layoutRole");
        if (revision < 0 || frame < 0 || layoutRevision < 0) {
            throw new IllegalArgumentException("revisions and frame must be non-negative");
        }
        LayoutSupport.nonBlank(currentArtifactId, "currentArtifactId");
        requireSha(captureSha256, "captureSha256");
        requireSha(layoutSha256, "layoutSha256");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(transforms, "transforms");
        bounds = List.copyOf(Objects.requireNonNull(bounds, "bounds"));
        if (bounds.size() != 4
                || !EnumSet.copyOf(bounds.stream().map(CoordinateBounds::space).toList())
                        .equals(SPACES)) {
            throw new IllegalArgumentException(
                    "bounds must contain local, stage, screen, and framebuffer exactly once");
        }
        Objects.requireNonNull(padding, "padding");
        clipChain = List.copyOf(Objects.requireNonNull(clipChain, "clipChain"));
        if (clipChain.size() > 128) {
            throw new IllegalArgumentException("clipChain exceeds 128 entries");
        }
        visibleIntersection = LayoutSupport.space(
                Objects.requireNonNull(visibleIntersection),
                "visibleIntersection",
                "FRAMEBUFFER");
        Objects.requireNonNull(scroll, "scroll");
    }

    /** Returns observed actor bounds in one named space. */
    public CoordinateBounds bounds(CoordinateSpace space) {
        return bounds.stream()
                .filter(value -> value.space() == space)
                .findFirst()
                .orElseThrow();
    }

    private static void requireSha(String value, String name) {
        if (!SHA_256.matcher(Objects.requireNonNull(value, name)).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }
}
