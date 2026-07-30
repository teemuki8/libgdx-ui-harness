package dev.gdx.uiharness.core.typography;

/** Fail-closed classification of a typography comparison. */
public enum TypographyStatus {
    PIXEL_SHARP,
    NOT_PIXEL_SHARP,
    INCOMPLETE,
    NOT_DIAGNOSABLE,
    STALE,
    NOT_STABLE
}
