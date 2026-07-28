package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.model.Role;
import java.util.Map;
import java.util.Objects;

/** Immutable semantic values explicitly associated with one Scene2D actor. */
public record ActorMetadata(
        Role role,
        String accessibleName,
        String text,
        String label,
        String testId,
        Map<String, String> properties) {
    static final ActorMetadata EMPTY =
            new ActorMetadata(null, null, null, null, null, Map.of());

    /** Defensively copies the custom property map. */
    public ActorMetadata {
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    ActorMetadata withRole(Role value) {
        return new ActorMetadata(value, accessibleName, text, label, testId, properties);
    }

    ActorMetadata withAccessibleName(String value) {
        return new ActorMetadata(role, value, text, label, testId, properties);
    }

    ActorMetadata withText(String value) {
        return new ActorMetadata(role, accessibleName, value, label, testId, properties);
    }

    ActorMetadata withLabel(String value) {
        return new ActorMetadata(role, accessibleName, text, value, testId, properties);
    }

    ActorMetadata withTestId(String value) {
        return new ActorMetadata(role, accessibleName, text, label, value, properties);
    }

    ActorMetadata withProperty(String key, String value) {
        var updated = new java.util.LinkedHashMap<>(properties);
        updated.put(key, value);
        return new ActorMetadata(role, accessibleName, text, label, testId, updated);
    }
}
