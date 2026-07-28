package dev.gdx.uiharness.core.error;

/** Stable categories for failures returned by the harness. */
public enum ErrorCode {
    /** Request contents are invalid. */
    INVALID_REQUEST,
    /** Requested behavior is unavailable in the active backend. */
    UNSUPPORTED_CAPABILITY,
    /** The requested session does not exist. */
    SESSION_NOT_FOUND,
    /** The requested session is already closed. */
    SESSION_CLOSED,
    /** No semantic node matches a locator. */
    NOT_FOUND,
    /** A strict operation matched more than one node. */
    STRICTNESS_VIOLATION,
    /** A selected node does not satisfy actionability requirements. */
    NOT_ACTIONABLE,
    /** An operation exceeded its deadline. */
    TIMEOUT,
    /** Work scheduled on the render thread failed. */
    RENDER_THREAD_FAILURE,
    /** Evidence capture failed. */
    CAPTURE_FAILURE,
    /** A configured hard limit was exceeded. */
    LIMIT_EXCEEDED,
    /** A request uses an incompatible protocol version. */
    PROTOCOL_VERSION_MISMATCH,
    /** An unexpected internal failure occurred. */
    INTERNAL_ERROR
}
