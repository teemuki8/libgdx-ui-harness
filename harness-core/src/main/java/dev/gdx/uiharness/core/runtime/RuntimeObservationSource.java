package dev.gdx.uiharness.core.runtime;

import dev.gdx.uiharness.core.model.RuntimeBinding;
import java.util.Optional;

/**
 * Optional application-supplied read-only runtime observation SPI. The harness never calls
 * arbitrary reflection or unrestricted runtime queries; integration with a runtime library is
 * optional and never a dependency of the core.
 */
public interface RuntimeObservationSource {
    /**
     * Observes one typed runtime value for a binding.
     *
     * @return an observation, or empty when the runtime source is unavailable or stale
     */
    Optional<RuntimeObservation> observe(RuntimeBinding binding);

    /** Returns whether this source is available for the binding. */
    default boolean available(RuntimeBinding binding) {
        return true;
    }
}
