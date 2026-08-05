package dev.gdx.uiharness.core.golden;

import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One partial semantic expectation. Omitted optional properties are unconstrained by default;
 * strict-node mode requires complete coverage.
 */
public record BaselineNode(
        Role role,
        String accessibleName,
        String text,
        String label,
        String testId,
        String actorName,
        String actorType,
        Boolean visible,
        Boolean enabled,
        Boolean checked,
        Boolean selected,
        Boolean expanded,
        Boolean editable,
        Boolean focused,
        Boolean focusable,
        Bounds stageBounds,
        String placement,
        Map<String, String> properties,
        List<BaselineNode> children) {
    private static final int MAX_PROPERTIES = 256;
    private static final int MAX_CHILDREN = 10_000;

    /** Validates bounded optional fields and defensively copies collections. */
    public BaselineNode {
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        if (properties.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("baseline properties exceed 256 entries");
        }
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        if (children.size() > MAX_CHILDREN) {
            throw new IllegalArgumentException("baseline children exceed 10000 entries");
        }
        if (placement != null && placement.isBlank()) {
            throw new IllegalArgumentException("placement must not be blank");
        }
    }
}
