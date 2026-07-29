package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.contract.ControlKind;
import dev.gdx.uiharness.core.contract.ControlOption;
import dev.gdx.uiharness.core.contract.ValidationRule;
import dev.gdx.uiharness.core.contract.ValidationStatus;
import java.util.List;
import java.util.Objects;

/** Explicit application-domain definition for one Scene2D control. */
public record ControlMetadata(
        String id,
        int order,
        int focusOrder,
        ControlKind kind,
        List<ControlOption> options,
        ContractValue defaultValue,
        ValidationRule validationRule,
        ValidationStatus validationStatus) {
    public ControlMetadata {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (order < 0 || focusOrder < 0) {
            throw new IllegalArgumentException("control orders must be non-negative");
        }
        Objects.requireNonNull(kind, "kind");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(validationRule, "validationRule");
        Objects.requireNonNull(validationStatus, "validationStatus");
    }
}
