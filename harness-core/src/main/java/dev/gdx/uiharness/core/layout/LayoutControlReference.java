package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import java.util.List;

/** Immutable expected ownership and geometry for one stable control. */
public record LayoutControlReference(
        String controlId,
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
    /** Validates values through the bound evaluator shape. */
    public LayoutControlReference {
        expectedBounds = List.copyOf(expectedBounds);
        new LayoutExpectation(
                controlId, "validation-app", "validation-viewport",
                0, 0, 0, "validation-reference", "validation-current",
                expectedParentActorId, expectedLayoutOwnerId, expectedScrollOwnerId,
                expectedClipOwnerId, expectedLayoutRole, expectedBounds,
                expectedVisibleIntersection, expectedPadding,
                boundsTolerance, paddingTolerance, relationship, expectedLayoutSha256);
    }

    /** Binds this reference to one completed current capture. */
    public LayoutExpectation bind(
            LayoutReference reference,
            long revision,
            long frame,
            long layoutRevision,
            String currentArtifactId) {
        return new LayoutExpectation(
                controlId,
                reference.applicationId(),
                reference.viewportId(),
                revision,
                frame,
                layoutRevision,
                reference.referenceArtifactId(),
                currentArtifactId,
                expectedParentActorId,
                expectedLayoutOwnerId,
                expectedScrollOwnerId,
                expectedClipOwnerId,
                expectedLayoutRole,
                expectedBounds,
                expectedVisibleIntersection,
                expectedPadding,
                boundsTolerance,
                paddingTolerance,
                relationship,
                expectedLayoutSha256);
    }
}
