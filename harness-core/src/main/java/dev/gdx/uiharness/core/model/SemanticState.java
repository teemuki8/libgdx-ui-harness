package dev.gdx.uiharness.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Observable state for a semantic node. Widget-specific states use {@link Optional} so an
 * unsupported state remains distinct from a supported state whose value is {@code false}.
 *
 * @param visible whether the actor is effectively visible
 * @param touchable whether the actor participates in touch handling
 * @param enabled enabled state, or empty when the actor has no enabled state
 * @param checked checked state, or empty when not meaningful
 * @param selected selected state, or empty when not meaningful
 * @param expanded expanded state, or empty when not meaningful
 * @param editable editable state, or empty when not meaningful
 * @param focused whether the actor currently has focus
 * @param focusable whether the actor can receive focus
 * @param effectiveAlpha effective color alpha in the inclusive range {@code [0, 1]}
 * @param clipped whether clipping affects the actor
 * @param viewportIntersecting whether the actor intersects its viewport
 * @param hitTarget whether hit testing selects the actor
 */
public record SemanticState(
        boolean visible,
        boolean touchable,
        Optional<Boolean> enabled,
        Optional<Boolean> checked,
        Optional<Boolean> selected,
        Optional<Boolean> expanded,
        Optional<Boolean> editable,
        boolean focused,
        boolean focusable,
        double effectiveAlpha,
        boolean clipped,
        boolean viewportIntersecting,
        boolean hitTarget) {
    /** Validates state values and absence markers. */
    public SemanticState {
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(checked, "checked");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(expanded, "expanded");
        Objects.requireNonNull(editable, "editable");
        if (!Double.isFinite(effectiveAlpha) || effectiveAlpha < 0.0 || effectiveAlpha > 1.0) {
            throw new IllegalArgumentException("effectiveAlpha must be finite and between 0 and 1");
        }
    }
}
