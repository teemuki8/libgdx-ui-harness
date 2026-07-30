package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.typography.EvidenceValue;
import java.util.List;
import java.util.Objects;

/** Explicit source-level font facts that Scene2D cannot recover reliably. */
public record TypographyMetadata(
        String sourceId,
        List<String> atlasPageIds,
        double nominalSize,
        double generatedGlyphSize,
        EvidenceValue<Double> weight,
        EvidenceValue<Double> letterSpacing,
        EvidenceValue<String> distanceField) {

    /** Defensively copies identifiers and validates positive declared sizes. */
    public TypographyMetadata {
        if (Objects.requireNonNull(sourceId, "sourceId").isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        atlasPageIds = List.copyOf(Objects.requireNonNull(atlasPageIds, "atlasPageIds"));
        if (atlasPageIds.isEmpty() || atlasPageIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("atlasPageIds must contain non-blank values");
        }
        if (!Double.isFinite(nominalSize) || nominalSize <= 0
                || !Double.isFinite(generatedGlyphSize) || generatedGlyphSize <= 0) {
            throw new IllegalArgumentException(
                    "nominalSize and generatedGlyphSize must be finite and positive");
        }
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(letterSpacing, "letterSpacing");
        Objects.requireNonNull(distanceField, "distanceField");
    }
}
