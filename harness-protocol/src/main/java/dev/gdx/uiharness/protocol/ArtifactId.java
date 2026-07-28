package dev.gdx.uiharness.protocol;

import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque random artifact identifier with no caller-controlled path semantics. */
public record ArtifactId(String value) {
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{32}");

    /** Rejects traversal strings and every non-canonical identifier representation. */
    public ArtifactId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("artifact id must be 32 lowercase hexadecimal digits");
        }
    }

    @Override public String toString() {
        return value;
    }
}
