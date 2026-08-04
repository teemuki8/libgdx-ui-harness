package dev.gdx.uiharness.core.scenario;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded metadata for one application-registered scenario. */
public record ScenarioDefinition(
        int schemaVersion,
        String id,
        String definitionVersion,
        String applicationId,
        List<String> supportedProfileIds,
        int maxSetupAttempts,
        Duration maxDuration) {
    static final int MAX_IDENTIFIER_LENGTH = 256;
    static final int MAX_STRING_LENGTH = 16_384;
    static final int MAX_ENTRIES = 256;
    static final int MAX_SETUP_ATTEMPTS = 16;

    public ScenarioDefinition {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        id = identifier(id, "id");
        definitionVersion = text(definitionVersion, "definitionVersion");
        applicationId = identifier(applicationId, "applicationId");
        supportedProfileIds = List.copyOf(
                Objects.requireNonNull(supportedProfileIds, "supportedProfileIds"));
        if (supportedProfileIds.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("supportedProfileIds exceeds 256 entries");
        }
        var distinctProfiles = new HashSet<String>(supportedProfileIds.size());
        for (String profileId : supportedProfileIds) {
            if (!distinctProfiles.add(identifier(profileId, "supportedProfileId"))) {
                throw new IllegalArgumentException("supportedProfileIds contains a duplicate");
            }
        }
        if (maxSetupAttempts < 1 || maxSetupAttempts > MAX_SETUP_ATTEMPTS) {
            throw new IllegalArgumentException("maxSetupAttempts must be between 1 and 16");
        }
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
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

    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds 16384 characters");
        }
        return value;
    }
}
