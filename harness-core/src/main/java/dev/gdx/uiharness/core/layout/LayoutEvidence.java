package dev.gdx.uiharness.core.layout;

import java.util.List;
import java.util.Objects;

/** Selected actor observations plus the bounded settling proof. */
public record LayoutEvidence(
        List<LayoutObservation> observations,
        LayoutQuiescenceResult settling,
        LayoutQuiescenceResult captures) {
    /** Copies selected evidence. */
    public LayoutEvidence {
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        Objects.requireNonNull(settling, "settling");
        Objects.requireNonNull(captures, "captures");
    }
}
