package dev.gdx.uiharness.core.matrix;

/** Closed terminal classification of one matrix case. */
public enum MatrixCaseStatus {
    /** All carried assertions passed. */
    PASSED,
    /** At least one carried assertion failed or the case failed to start. */
    FAILED,
    /** The run terminated before this case started. */
    UNSTARTED,
    /** The case was cancelled after starting. */
    CANCELLED,
    /** The case was rejected before application because a display dimension is unsupported. */
    UNSUPPORTED,
    /** The case was applied but the observed display state did not match the request. */
    MISAPPLIED
}
