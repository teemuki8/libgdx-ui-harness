package dev.gdx.uiharness.core.scenario;

/** Closed terminal failure categories for deterministic scenario execution. */
public enum ScenarioFailure {
    UNKNOWN_SCENARIO,
    INCOMPATIBLE_SCENARIO,
    UNSUPPORTED_PROFILE,
    SETUP_REJECTED,
    RESET_REJECTED,
    READINESS_DEADLINE,
    PROCESS_REPLACED,
    SESSION_REPLACED,
    STALE_REVISION,
    CLEANUP_FAILED,
    NONDETERMINISTIC_INITIAL_STATE,
    DISPATCH_FAILED,
    CANCELLED
}
