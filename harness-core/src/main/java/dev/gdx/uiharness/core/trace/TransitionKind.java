package dev.gdx.uiharness.core.trace;

/** Closed transition classifications for compact trace projections. */
public enum TransitionKind {
    /** Node appeared between adjacent observations. */
    APPEARED,
    /** Node disappeared between adjacent observations. */
    DISAPPEARED,
    /** Node became enabled. */
    ENABLED,
    /** Node became disabled. */
    DISABLED,
    /** Visible text changed. */
    TEXT_CHANGED,
    /** Stage bounds changed. */
    BOUNDS_CHANGED,
    /** Focus moved. */
    FOCUS_CHANGED,
    /** Modal boundary changed. */
    MODAL_CHANGED,
    /** Obscuration state changed. */
    OBSCURATION_CHANGED,
    /** Sibling z-order changed. */
    Z_ORDER_CHANGED,
    /** Identity could not be correlated unambiguously. */
    IDENTITY_AMBIGUOUS
}
