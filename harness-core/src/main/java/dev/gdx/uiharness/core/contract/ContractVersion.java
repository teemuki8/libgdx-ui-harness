package dev.gdx.uiharness.core.contract;

/** Public state/action schema version. */
public record ContractVersion(int major, int minor) {
    /** Initial evaluator-complete contract version. */
    public static final ContractVersion V1 = new ContractVersion(1, 0);

    /** Validates non-negative version components. */
    public ContractVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("contract version components must be non-negative");
        }
    }

    /** Returns the stable wire name. */
    public String wireName() {
        return "state-action/v" + major + "." + minor;
    }
}
