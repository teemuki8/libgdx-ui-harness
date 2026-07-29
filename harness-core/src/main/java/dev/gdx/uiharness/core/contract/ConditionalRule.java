package dev.gdx.uiharness.core.contract;

import java.util.Objects;

/** Equality-controlled relationship and its effective visibility/actionability effects. */
public record ConditionalRule(
        String controllerId,
        ContractValue equalsValue,
        String dependentId,
        boolean visibleWhenEqual,
        boolean actionableWhenEqual,
        String restoreFocusTo) {
    public ConditionalRule {
        ContractSupport.text(controllerId, "controllerId");
        Objects.requireNonNull(equalsValue, "equalsValue");
        ContractSupport.text(dependentId, "dependentId");
        if (restoreFocusTo != null) {
            ContractSupport.text(restoreFocusTo, "restoreFocusTo");
        }
    }
}
