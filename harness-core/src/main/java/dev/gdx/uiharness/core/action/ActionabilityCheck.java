package dev.gdx.uiharness.core.action;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Result of applying normal or forced action requirements to one observation. */
public record ActionabilityCheck(boolean actionable, Set<Requirement> unmet) {
    public ActionabilityCheck {
        Objects.requireNonNull(unmet, "unmet");
        EnumSet<Requirement> copy = unmet.isEmpty()
                ? EnumSet.noneOf(Requirement.class)
                : EnumSet.copyOf(unmet);
        unmet = Collections.unmodifiableSet(copy);
        if (actionable != unmet.isEmpty()) {
            throw new IllegalArgumentException("actionable must agree with unmet requirements");
        }
    }

    static ActionabilityCheck evaluate(Actionability state, boolean force) {
        Objects.requireNonNull(state, "state");
        EnumSet<Requirement> unmet = EnumSet.noneOf(Requirement.class);
        addUnless(state.attached(), Requirement.ATTACHED, unmet);
        addUnless(state.enabled(), Requirement.ENABLED, unmet);
        addUnless(state.touchable(), Requirement.TOUCHABLE, unmet);
        if (!force) {
            addUnless(state.visible(), Requirement.VISIBLE, unmet);
            addUnless(state.stable(), Requirement.STABLE, unmet);
            addUnless(state.viewportIntersecting(), Requirement.VIEWPORT_INTERSECTING, unmet);
            addUnless(state.hitTarget(), Requirement.HIT_TARGET, unmet);
        }
        return new ActionabilityCheck(unmet.isEmpty(), unmet);
    }

    private static void addUnless(
            boolean satisfied, Requirement requirement, EnumSet<Requirement> unmet) {
        if (!satisfied) {
            unmet.add(requirement);
        }
    }

    /** Stable names used in timeout and diagnostics evidence. */
    public enum Requirement {
        ATTACHED,
        VISIBLE,
        ENABLED,
        TOUCHABLE,
        STABLE,
        VIEWPORT_INTERSECTING,
        HIT_TARGET
    }
}
