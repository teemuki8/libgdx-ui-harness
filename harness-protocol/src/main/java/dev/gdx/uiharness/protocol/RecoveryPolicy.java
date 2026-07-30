package dev.gdx.uiharness.protocol;

/** Precommitted finite workflow recovery and cost ceilings. */
public record RecoveryPolicy(
        int maxSchemaRecoveries,
        int maxStateRetries,
        int maxUnchangedInspectCycles,
        int maxUnchangedBuilds,
        int maxUnchangedLaunches,
        long maxWallTimeMillis) {
    public static final String VERSION = "recovery-policy/v1";

    /** Requires every ceiling to be finite and positive. */
    public RecoveryPolicy {
        if (maxSchemaRecoveries < 1 || maxStateRetries < 1
                || maxUnchangedInspectCycles < 1 || maxUnchangedBuilds < 1
                || maxUnchangedLaunches < 1 || maxWallTimeMillis < 1) {
            throw new IllegalArgumentException("recovery ceilings must be positive");
        }
    }
}
