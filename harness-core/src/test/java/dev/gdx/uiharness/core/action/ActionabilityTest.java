package dev.gdx.uiharness.core.action;

import static dev.gdx.uiharness.core.action.ActionabilityCheck.Requirement.ATTACHED;
import static dev.gdx.uiharness.core.action.ActionabilityCheck.Requirement.ENABLED;
import static dev.gdx.uiharness.core.action.ActionabilityCheck.Requirement.HIT_TARGET;
import static dev.gdx.uiharness.core.action.ActionabilityCheck.Requirement.STABLE;
import static dev.gdx.uiharness.core.action.ActionabilityCheck.Requirement.TOUCHABLE;
import static dev.gdx.uiharness.core.action.ActionabilityCheck.Requirement.VIEWPORT_INTERSECTING;
import static dev.gdx.uiharness.core.action.ActionabilityCheck.Requirement.VISIBLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

final class ActionabilityTest {
    @Test void reportsEveryFailedClickRequirement() {
        Actionability state = new Actionability(false, false, false, false, false, false, false);

        ActionabilityCheck result = state.check(false);

        assertFalse(result.actionable());
        assertEquals(
                EnumSet.of(ATTACHED, VISIBLE, ENABLED, TOUCHABLE, STABLE,
                        VIEWPORT_INTERSECTING, HIT_TARGET),
                result.unmet());
    }

    @Test void forceBypassesOnlyVisibilityStabilityAndHitTesting() {
        Actionability state = new Actionability(false, false, false, false, false, false, false);

        ActionabilityCheck forced = state.check(true);

        assertFalse(forced.actionable());
        assertEquals(EnumSet.of(ATTACHED, ENABLED, TOUCHABLE), forced.unmet());
    }

    @Test void attachedEnabledTouchableTargetIsForceActionable() {
        Actionability state = new Actionability(true, false, true, true, false, false, false);

        assertTrue(state.check(true).actionable());
        assertEquals(EnumSet.noneOf(ActionabilityCheck.Requirement.class),
                state.check(true).unmet());
    }

    @Test void normalActionRequiresAllChecks() {
        Actionability state = new Actionability(true, true, true, true, true, true, true);

        assertTrue(state.check(false).actionable());
    }
}
