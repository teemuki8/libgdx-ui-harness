package dev.gdx.uiharness.core.typography;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Completes actor-attributed typography observations for one current capture. */
@FunctionalInterface
public interface TypographyEvidenceProvider {
    /** Inspects the matching rendered frame and computes per-control raster residuals. */
    CompletionStage<List<TypographyObservation>> inspect(
            TypographyReference reference, CapturedImage current, Deadline deadline);
}
