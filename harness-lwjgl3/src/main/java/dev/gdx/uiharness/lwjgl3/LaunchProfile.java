package dev.gdx.uiharness.lwjgl3;

import java.time.Duration;
import java.util.Objects;

/** Immutable public identity for one host-registered LWJGL3 restart profile. */
public record LaunchProfile(int schemaVersion, String id, String applicationId) {
    public static final int SCHEMA_VERSION = 1;
    static final Duration MAX_TIMING = Duration.ofMinutes(10);
    private static final int MAX_IDENTIFIER_LENGTH = 256;

    public LaunchProfile {
        schemaVersion = supportedSchemaVersion(schemaVersion);
        id = identifier(id, "id");
        applicationId = identifier(applicationId, "applicationId");
    }

    static int supportedSchemaVersion(int value) {
        if (value != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion must be the supported version " + SCHEMA_VERSION);
        }
        return value;
    }

    static String identifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds 256 characters");
        }
        return value;
    }

    static Duration timing(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (value.compareTo(MAX_TIMING) > 0) {
            throw new IllegalArgumentException(name + " exceeds PT10M");
        }
        return value;
    }
}
