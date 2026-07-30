package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.concurrent.CompletionStage;

/** Backend adapter that binds layout evidence to a completed capture. */
@FunctionalInterface
public interface LayoutEvidenceProvider {
    /** Observes the declared controls and their settling evidence. */
    CompletionStage<LayoutEvidence> observe(
            LayoutReference reference, CapturedImage current, Deadline deadline);
}
