package dev.gdx.uiharness.core.assertion;

import java.util.Objects;

/** Bounded semantic evidence from the latest pure assertion evaluation. */
public record AssertionEvidence(
        String nodeId, String expected, String observed, long revision, long frame) {
    private static final int MAX_STRING_LENGTH = 16_384;

    public AssertionEvidence {
        nodeId = bounded(nodeId, "nodeId");
        expected = bounded(expected, "expected");
        observed = bounded(observed, "observed");
        if (revision < 0 || frame < 0) {
            throw new IllegalArgumentException("revision and frame must be non-negative");
        }
    }

    private static String bounded(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_STRING_LENGTH + " characters");
        }
        return value;
    }
}
