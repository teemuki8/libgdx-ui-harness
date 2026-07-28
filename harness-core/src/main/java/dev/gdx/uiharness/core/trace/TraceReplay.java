package dev.gdx.uiharness.core.trace;

import java.util.List;
import java.util.Objects;

/** Bounded validation summary produced by streaming a trace archive. */
public record TraceReplay(
        TraceManifest manifest,
        List<Long> semanticRevisions,
        Causality causality,
        boolean partial,
        List<String> diagnostics) {

    /** Defensively copies replay summaries. */
    public TraceReplay {
        manifest = Objects.requireNonNull(manifest, "manifest");
        semanticRevisions = List.copyOf(semanticRevisions);
        causality = Objects.requireNonNull(causality, "causality");
        diagnostics = List.copyOf(diagnostics);
    }

    /** Causal validation result. */
    public record Causality(List<String> errors) {
        /** Defensively copies causal diagnostics. */
        public Causality {
            errors = List.copyOf(errors);
        }

        /** Returns whether every observed causal invariant was valid. */
        public boolean isValid() {
            return errors.isEmpty();
        }
    }
}
