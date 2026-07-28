package dev.gdx.uiharness.core.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One backend-neutral node in a semantic snapshot.
 *
 * @param id snapshot-local identifier
 * @param parentId parent identifier, or {@code null} for the root
 * @param childIds ordered child identifiers
 * @param role semantic role
 * @param accessibleName accessible name, when available
 * @param text normalized visible text, when available
 * @param label associated label, when available
 * @param testId explicit automation identifier, when available
 * @param actorName backend actor name used for diagnostics, when available
 * @param actorType backend actor type used for diagnostics, when available
 * @param state observable semantic state
 * @param localBounds actor-local bounds
 * @param stageBounds stage-space bounds
 * @param screenBounds screen-space bounds
 * @param zIndex sibling z-order
 * @param properties bounded adapter-specific properties
 */
public record SemanticNode(
        String id,
        String parentId,
        List<String> childIds,
        Role role,
        String accessibleName,
        String text,
        String label,
        String testId,
        String actorName,
        String actorType,
        SemanticState state,
        Bounds localBounds,
        Bounds stageBounds,
        Bounds screenBounds,
        int zIndex,
        Map<String, String> properties) {
    private static final int MAX_STRING_LENGTH = 16_384;
    private static final int MAX_PROPERTIES = 256;

    /** Validates scalar values and defensively copies child and property collections. */
    public SemanticNode {
        validateRequired(id, "id");
        if (parentId != null) {
            validateRequired(parentId, "parentId");
            if (id.equals(parentId)) {
                throw new IllegalArgumentException("node must not be its own parent");
            }
        }

        Objects.requireNonNull(childIds, "childIds");
        childIds = List.copyOf(childIds);
        var distinctChildren = new HashSet<String>(childIds.size());
        for (String childId : childIds) {
            validateRequired(childId, "childId");
            if (id.equals(childId)) {
                throw new IllegalArgumentException("node must not be its own child");
            }
            if (!distinctChildren.add(childId)) {
                throw new IllegalArgumentException("duplicate child id: " + childId);
            }
        }

        Objects.requireNonNull(role, "role");
        validateOptional(accessibleName, "accessibleName");
        validateOptional(text, "text");
        validateOptional(label, "label");
        validateOptional(testId, "testId");
        validateOptional(actorName, "actorName");
        validateOptional(actorType, "actorType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(localBounds, "localBounds");
        Objects.requireNonNull(stageBounds, "stageBounds");
        Objects.requireNonNull(screenBounds, "screenBounds");

        Objects.requireNonNull(properties, "properties");
        if (properties.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("properties exceeds " + MAX_PROPERTIES + " entries");
        }
        for (Map.Entry<String, String> property : properties.entrySet()) {
            validateRequired(property.getKey(), "property key");
            Objects.requireNonNull(property.getValue(), "property value");
            validateLength(property.getValue(), "property value");
        }
        properties = Map.copyOf(properties);
    }

    private static void validateOptional(String value, String name) {
        if (value != null) {
            validateLength(value, name);
        }
    }

    private static void validateRequired(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        validateLength(value, name);
    }

    private static void validateLength(String value, String name) {
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_STRING_LENGTH + " characters");
        }
    }
}
