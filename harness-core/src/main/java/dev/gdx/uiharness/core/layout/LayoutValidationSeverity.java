package dev.gdx.uiharness.core.layout;

/** Closed finding severity used by the CI gate. */
public enum LayoutValidationSeverity {
    /** Informational, such as an unavailable check. */
    INFO,
    /** Reported but below the default CI gate. */
    WARNING,
    /** Blocks the CI gate at its configured threshold. */
    ERROR
}
