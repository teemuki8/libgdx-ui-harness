package dev.gdx.uiharness.core.typography;

/** Whether one typography fact was observable without guessing. */
public enum Availability {
    /** The value was observed or explicitly registered. */
    AVAILABLE,
    /** The value could not be established. */
    UNAVAILABLE
}
