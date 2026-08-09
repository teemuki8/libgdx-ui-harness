package dev.gdx.uiharness.core.trace;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded validation summary produced by streaming a trace archive. */
public record TraceReplay(
        TraceManifest manifest,
        List<Long> semanticRevisions,
        Causality causality,
        boolean partial,
        List<String> diagnostics,
        String archiveSha256,
        Integrity integrity) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Defensively copies replay summaries. */
    public TraceReplay {
        manifest = Objects.requireNonNull(manifest, "manifest");
        semanticRevisions = List.copyOf(semanticRevisions);
        causality = Objects.requireNonNull(causality, "causality");
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(archiveSha256, "archiveSha256");
        Objects.requireNonNull(integrity, "integrity");
        if (integrity == Integrity.VERIFIED && !SHA256.matcher(archiveSha256).matches()) {
            throw new IllegalArgumentException(
                    "verified replays require a SHA-256 archive digest");
        }
    }

    /** Legacy constructor for callers that predate archive digest verification:
     *  reports an unverified replay without a digest. */
    public TraceReplay(
            TraceManifest manifest,
            List<Long> semanticRevisions,
            Causality causality,
            boolean partial,
            List<String> diagnostics) {
        this(manifest, semanticRevisions, causality, partial, diagnostics,
                "", Integrity.UNVERIFIED);
    }

    /** Whether every manifest digest binding was recomputed and matched. */
    public enum Integrity {
        /** Every v2 binding (events, artifacts, counts, byte totals) matched. */
        VERIFIED,
        /** Legacy v1 archive without bindings; causally validated only. */
        UNVERIFIED
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
