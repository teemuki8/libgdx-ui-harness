package dev.gdx.uiharness.core.typography;

import java.util.List;
import java.util.Objects;

/** Backend-neutral facts about one rendered font and atlas. */
public record FontObservation(
        EvidenceValue<String> sourceId,
        List<String> atlasPageIds,
        EvidenceValue<Double> nominalSize,
        EvidenceValue<Double> generatedGlyphSize,
        double effectiveSizeX,
        double effectiveSizeY,
        double bitmapScaleX,
        double bitmapScaleY,
        EvidenceValue<String> minificationFilter,
        EvidenceValue<String> magnificationFilter,
        EvidenceValue<String> distanceField,
        EvidenceValue<Double> weight,
        EvidenceValue<Double> letterSpacing) {

    /** Defensively copies pages and validates finite positive rendering sizes. */
    public FontObservation {
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        atlasPageIds = List.copyOf(Objects.requireNonNull(atlasPageIds, "atlasPageIds"));
        nominalSize = Objects.requireNonNull(nominalSize, "nominalSize");
        generatedGlyphSize =
                Objects.requireNonNull(generatedGlyphSize, "generatedGlyphSize");
        TypographySupport.requirePositiveFinite(effectiveSizeX, "effectiveSizeX");
        TypographySupport.requirePositiveFinite(effectiveSizeY, "effectiveSizeY");
        TypographySupport.requirePositiveFinite(bitmapScaleX, "bitmapScaleX");
        TypographySupport.requirePositiveFinite(bitmapScaleY, "bitmapScaleY");
        minificationFilter =
                Objects.requireNonNull(minificationFilter, "minificationFilter");
        magnificationFilter =
                Objects.requireNonNull(magnificationFilter, "magnificationFilter");
        distanceField = Objects.requireNonNull(distanceField, "distanceField");
        weight = Objects.requireNonNull(weight, "weight");
        letterSpacing = Objects.requireNonNull(letterSpacing, "letterSpacing");
    }

    /** Returns a copy with another observed magnification filter. */
    public FontObservation withMagnificationFilter(EvidenceValue<String> value) {
        return new FontObservation(sourceId, atlasPageIds, nominalSize, generatedGlyphSize,
                effectiveSizeX, effectiveSizeY, bitmapScaleX, bitmapScaleY,
                minificationFilter, value, distanceField, weight, letterSpacing);
    }

    /** Returns a copy with another weight observation. */
    public FontObservation withWeight(EvidenceValue<Double> value) {
        return new FontObservation(sourceId, atlasPageIds, nominalSize, generatedGlyphSize,
                effectiveSizeX, effectiveSizeY, bitmapScaleX, bitmapScaleY,
                minificationFilter, magnificationFilter, distanceField, value, letterSpacing);
    }

    /** Returns a copy with another source identity. */
    public FontObservation withSourceId(EvidenceValue<String> value) {
        return new FontObservation(value, atlasPageIds, nominalSize, generatedGlyphSize,
                effectiveSizeX, effectiveSizeY, bitmapScaleX, bitmapScaleY,
                minificationFilter, magnificationFilter, distanceField, weight, letterSpacing);
    }

    /** Returns a copy with another nominal size. */
    public FontObservation withNominalSize(EvidenceValue<Double> value) {
        return new FontObservation(sourceId, atlasPageIds, value, generatedGlyphSize,
                effectiveSizeX, effectiveSizeY, bitmapScaleX, bitmapScaleY,
                minificationFilter, magnificationFilter, distanceField, weight, letterSpacing);
    }

    /** Returns a copy with another generated glyph size. */
    public FontObservation withGeneratedGlyphSize(EvidenceValue<Double> value) {
        return new FontObservation(sourceId, atlasPageIds, nominalSize, value,
                effectiveSizeX, effectiveSizeY, bitmapScaleX, bitmapScaleY,
                minificationFilter, magnificationFilter, distanceField, weight, letterSpacing);
    }

    /** Returns a copy with another bitmap scale. */
    public FontObservation withBitmapScale(double x, double y) {
        return new FontObservation(sourceId, atlasPageIds, nominalSize, generatedGlyphSize,
                effectiveSizeX, effectiveSizeY, x, y,
                minificationFilter, magnificationFilter, distanceField, weight, letterSpacing);
    }

    /** Returns a copy with another minification filter. */
    public FontObservation withMinificationFilter(EvidenceValue<String> value) {
        return new FontObservation(sourceId, atlasPageIds, nominalSize, generatedGlyphSize,
                effectiveSizeX, effectiveSizeY, bitmapScaleX, bitmapScaleY,
                value, magnificationFilter, distanceField, weight, letterSpacing);
    }

    /** Returns a copy with another letter-spacing observation. */
    public FontObservation withLetterSpacing(EvidenceValue<Double> value) {
        return new FontObservation(sourceId, atlasPageIds, nominalSize, generatedGlyphSize,
                effectiveSizeX, effectiveSizeY, bitmapScaleX, bitmapScaleY,
                minificationFilter, magnificationFilter, distanceField, weight, value);
    }
}
