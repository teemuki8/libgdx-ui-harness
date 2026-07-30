package dev.gdx.uiharness.core.typography;

/** Machine-readable reason why a typography fact is unavailable. */
public enum UnavailableReason {
    UNSUPPORTED,
    NOT_REGISTERED,
    NOT_EXPOSED,
    MISSING,
    UNKNOWN,
    NON_INVERTIBLE;

    /** Lowercase protocol spelling. */
    public String protocolValue() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
