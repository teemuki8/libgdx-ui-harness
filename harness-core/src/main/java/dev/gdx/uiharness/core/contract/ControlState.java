package dev.gdx.uiharness.core.contract;

import dev.gdx.uiharness.core.model.Role;
import java.util.List;
import java.util.Objects;

/** Complete ordered definition and observable state for one stable control. */
public record ControlState(
        String id,
        Role role,
        ControlKind kind,
        String accessibleName,
        List<ControlOption> options,
        ContractValue defaultValue,
        ContractValue currentValue,
        boolean visible,
        boolean enabled,
        boolean actionable,
        boolean focusable,
        boolean focused,
        ValidationRule validationRule,
        ValidationStatus validationStatus) {
    public ControlState {
        ContractSupport.text(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(kind, "kind");
        ContractSupport.text(accessibleName, "accessibleName");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (options.size() > 256) {
            throw new IllegalArgumentException("options exceeds 256 entries");
        }
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(currentValue, "currentValue");
        Objects.requireNonNull(validationRule, "validationRule");
        Objects.requireNonNull(validationStatus, "validationStatus");
        if (focused && !focusable) {
            throw new IllegalArgumentException("focused control must be focusable");
        }
        if (actionable && (!visible || !enabled)) {
            throw new IllegalArgumentException("actionable control must be visible and enabled");
        }
    }
}
