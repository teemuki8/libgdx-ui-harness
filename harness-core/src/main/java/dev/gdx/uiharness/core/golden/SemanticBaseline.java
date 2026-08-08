package dev.gdx.uiharness.core.golden;

import java.util.Objects;

/**
 * Versioned golden semantic baseline. Unknown major versions fail closed; minor versions are
 * additive and retained. The canonical digest binds the complete versioned baseline: the
 * identifier and digest together identify immutable content.
 */
public record SemanticBaseline(
        int majorVersion,
        int minorVersion,
        String id,
        BaselineNode root,
        boolean strictNodes,
        String digest) {
    public static final int CURRENT_MAJOR_VERSION = 1;

    /** Validates the version, identifier, root expectation, and digest identity. */
    public SemanticBaseline {
        if (majorVersion != CURRENT_MAJOR_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported baseline major version: " + majorVersion);
        }
        if (minorVersion < 0) {
            throw new IllegalArgumentException("minorVersion must be non-negative");
        }
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("baseline id must not be blank");
        }
        Objects.requireNonNull(root, "root");
        if (!BaselineDigest.isValidFormat(digest)) {
            throw new IllegalArgumentException(
                    "baseline digest must be 64 lowercase hex characters");
        }
    }

    /**
     * Creates a registered baseline whose digest is computed from the complete content. This
     * is the preferred construction path for immutable, digest-addressed baselines.
     */
    public static SemanticBaseline registered(
            int majorVersion, int minorVersion, String id, BaselineNode root,
            boolean strictNodes) {
        return new SemanticBaseline(majorVersion, minorVersion, id, root, strictNodes,
                digestFor(majorVersion, minorVersion, id, root, strictNodes));
    }

    /**
     * Backward-compatible constructor that computes the canonical digest. Retained for
     * callers released before the digest component was introduced.
     */
    public SemanticBaseline(
            int majorVersion, int minorVersion, String id, BaselineNode root,
            boolean strictNodes) {
        this(majorVersion, minorVersion, id, root, strictNodes,
                digestFor(majorVersion, minorVersion, id, root, strictNodes));
    }

    private static String digestFor(
            int majorVersion, int minorVersion, String id, BaselineNode root,
            boolean strictNodes) {
        SemanticBaseline provisional = new SemanticBaseline(
                majorVersion, minorVersion, id, root, strictNodes,
                "0".repeat(BaselineDigest.HEX_LENGTH));
        return BaselineDigest.canonical(provisional);
    }
}
