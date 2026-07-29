package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.model.Role;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Mutable, package-confined collection point whose output is validated before publication. */
final class SemanticNodeBuilder implements ActorSemanticAdapter.Target {
    static final int MAX_PROPERTIES = 256;

    Role role = Role.GENERIC;
    String accessibleName;
    String text;
    String label;
    String testId;
    Boolean enabled;
    Boolean checked;
    Boolean selected;
    Boolean expanded;
    Boolean editable;
    boolean focusable;
    ContractValue currentValue;
    final Map<String, String> properties = new LinkedHashMap<>();

    /** Sets the inferred role. */
    public SemanticNodeBuilder role(Role value) {
        role = Objects.requireNonNull(value, "role");
        return this;
    }

    /** Sets the inferred accessible name. */
    public SemanticNodeBuilder accessibleName(String value) {
        accessibleName = value;
        return this;
    }

    /** Sets normalized visible text. */
    public SemanticNodeBuilder text(String value) {
        text = normalizeVisibleText(value);
        return this;
    }

    /** Sets an inferred label. */
    public SemanticNodeBuilder label(String value) {
        label = value;
        return this;
    }

    /** Sets an inferred test identifier. */
    public SemanticNodeBuilder testId(String value) {
        testId = value;
        return this;
    }

    /** Marks whether the widget is enabled. */
    public SemanticNodeBuilder enabled(boolean value) {
        enabled = value;
        return this;
    }

    /** Marks whether the widget is checked. */
    public SemanticNodeBuilder checked(boolean value) {
        checked = value;
        return this;
    }

    /** Marks whether the widget has a selection. */
    public SemanticNodeBuilder selected(boolean value) {
        selected = value;
        return this;
    }

    /** Marks whether the widget is expanded. */
    public SemanticNodeBuilder expanded(boolean value) {
        expanded = value;
        return this;
    }

    /** Marks whether the widget is editable. */
    public SemanticNodeBuilder editable(boolean value) {
        editable = value;
        return this;
    }

    /** Marks whether the widget supports focus. */
    public SemanticNodeBuilder focusable(boolean value) {
        focusable = value;
        return this;
    }

    @Override public SemanticNodeBuilder currentValue(ContractValue value) {
        currentValue = Objects.requireNonNull(value, "value");
        return this;
    }

    /** Adds or replaces a custom semantic property. */
    public SemanticNodeBuilder property(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (!properties.containsKey(key) && properties.size() >= MAX_PROPERTIES) {
            throw propertyLimitExceeded(properties.size() + 1);
        }
        properties.put(key, value);
        return this;
    }

    void apply(ActorMetadata metadata) {
        if (metadata.role() != null) {
            role = metadata.role();
        }
        if (metadata.accessibleName() != null) {
            accessibleName = metadata.accessibleName();
        }
        if (metadata.text() != null) {
            text = normalizeVisibleText(metadata.text());
        }
        if (metadata.label() != null) {
            label = metadata.label();
        }
        if (metadata.testId() != null) {
            testId = metadata.testId();
        }
        if (metadata.currentValue() != null) {
            currentValue = metadata.currentValue();
        }
        metadata.properties().forEach(this::property);
    }

    private static HarnessException propertyLimitExceeded(int actual) {
        String actualValue = Integer.toString(actual);
        String limitValue = Integer.toString(MAX_PROPERTIES);
        return new HarnessException(
                ErrorCode.LIMIT_EXCEEDED,
                "properties exceeds configured limit "
                        + limitValue
                        + " (actual "
                        + actualValue
                        + ")",
                ErrorEvidence.ofDetails(Map.of(
                        "dimension", "properties",
                        "actual", actualValue,
                        "limit", limitValue)));
    }

    private static String normalizeVisibleText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.appendCodePoint(codePoint);
            }
        }
        return normalized.toString();
    }
}
