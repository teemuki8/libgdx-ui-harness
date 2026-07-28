package dev.gdx.uiharness.core.action;

/** One fresh actionability observation of a selected backend element. */
public record Actionability(
        boolean attached,
        boolean visible,
        boolean enabled,
        boolean touchable,
        boolean stable,
        boolean viewportIntersecting,
        boolean hitTarget) {
    /** Evaluates this observation using normal or forced action semantics. */
    public ActionabilityCheck check(boolean force) {
        return ActionabilityCheck.evaluate(this, force);
    }
}
