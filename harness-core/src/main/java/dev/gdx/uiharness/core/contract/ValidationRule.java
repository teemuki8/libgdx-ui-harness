package dev.gdx.uiharness.core.contract;

/** Public validation constraints; absent numeric bounds are represented by null. */
public record ValidationRule(
        String format, ContractValue minimum, ContractValue maximum, ContractValue step) {
    public ValidationRule {
        ContractSupport.text(format, "format");
    }
}
