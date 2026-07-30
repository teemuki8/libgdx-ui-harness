package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Named expected layout identity, ownership, geometry, and tolerance. */
public record LayoutExpectation(
        String controlId,
        String applicationId,
        String viewportId,
        long revision,
        long frame,
        long layoutRevision,
        String referenceArtifactId,
        String currentArtifactId,
        String expectedParentActorId,
        String expectedLayoutOwnerId,
        String expectedScrollOwnerId,
        String expectedClipOwnerId,
        String expectedLayoutRole,
        List<CoordinateBounds> expectedBounds,
        CoordinateBounds expectedVisibleIntersection,
        LayoutPadding expectedPadding,
        double boundsTolerance,
        double paddingTolerance,
        LayoutRelationship relationship,
        String expectedLayoutSha256) {
    /** Validates required identity, geometry, and finite tolerances. */
    public LayoutExpectation {
        LayoutSupport.nonBlank(controlId, "controlId");
        LayoutSupport.nonBlank(applicationId, "applicationId");
        LayoutSupport.nonBlank(viewportId, "viewportId");
        if (revision < 0 || frame < 0 || layoutRevision < 0) {
            throw new IllegalArgumentException("revisions and frame must be non-negative");
        }
        LayoutSupport.nonBlank(referenceArtifactId, "referenceArtifactId");
        LayoutSupport.nonBlank(currentArtifactId, "currentArtifactId");
        LayoutSupport.nonBlank(expectedParentActorId, "expectedParentActorId");
        LayoutSupport.nonBlank(expectedLayoutOwnerId, "expectedLayoutOwnerId");
        LayoutSupport.optionalId(expectedScrollOwnerId, "expectedScrollOwnerId");
        LayoutSupport.optionalId(expectedClipOwnerId, "expectedClipOwnerId");
        LayoutSupport.nonBlank(expectedLayoutRole, "expectedLayoutRole");
        expectedBounds = List.copyOf(Objects.requireNonNull(expectedBounds, "expectedBounds"));
        if (expectedBounds.size() != 4
                || !EnumSet.copyOf(expectedBounds.stream()
                                .map(CoordinateBounds::space).toList())
                        .equals(EnumSet.of(
                                CoordinateSpace.LOCAL,
                                CoordinateSpace.STAGE,
                                CoordinateSpace.SCREEN,
                                CoordinateSpace.FRAMEBUFFER))) {
            throw new IllegalArgumentException(
                    "expectedBounds must contain all four spaces exactly once");
        }
        expectedVisibleIntersection = LayoutSupport.space(
                Objects.requireNonNull(expectedVisibleIntersection),
                "expectedVisibleIntersection",
                "FRAMEBUFFER");
        Objects.requireNonNull(expectedPadding, "expectedPadding");
        if (!Double.isFinite(boundsTolerance) || boundsTolerance < 0
                || !Double.isFinite(paddingTolerance) || paddingTolerance < 0) {
            throw new IllegalArgumentException("tolerances must be finite and non-negative");
        }
        LayoutSupport.nonBlank(expectedLayoutSha256, "expectedLayoutSha256");
    }
}
