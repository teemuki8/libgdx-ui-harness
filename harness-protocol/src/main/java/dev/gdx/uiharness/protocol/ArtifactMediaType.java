package dev.gdx.uiharness.protocol;

import java.util.Arrays;

/** Closed allowlist of artifact media types accepted by the protocol store. */
public enum ArtifactMediaType {
    PNG("image/png"),
    JSON("application/json"),
    NDJSON("application/x-ndjson"),
    ZIP("application/zip"),
    OCTET_STREAM("application/octet-stream");

    private final String value;

    ArtifactMediaType(String value) {
        this.value = value;
    }

    /** Returns the canonical Internet media type. */
    public String value() {
        return value;
    }

    /** Resolves only an explicitly registered canonical media type. */
    public static ArtifactMediaType fromValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported artifact media type: " + value));
    }
}
