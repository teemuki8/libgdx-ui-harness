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
    CANCELLED
}
