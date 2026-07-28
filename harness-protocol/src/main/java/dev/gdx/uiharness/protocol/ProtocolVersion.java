package dev.gdx.uiharness.protocol;

/** Stable semantic version of the transport-neutral harness protocol. */
public record ProtocolVersion(int major, int minor) {
    /** The only version implemented by this module. */
    public static final ProtocolVersion V1 = new ProtocolVersion(1, 0);

    /** Validates non-negative version components. */
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("version components must be non-negative");
        }
    }

    /** Returns the stable dotted representation. */
    @Override public String toString() {
        return major + "." + minor;
    }
}
