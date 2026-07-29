package dev.gdx.uiharness.core.contract;

/** One stable option value and human-readable label. */
public record ControlOption(ContractValue value, String label) {
    public ControlOption {
        java.util.Objects.requireNonNull(value, "value");
        ContractSupport.text(label, "label");
    }
}
