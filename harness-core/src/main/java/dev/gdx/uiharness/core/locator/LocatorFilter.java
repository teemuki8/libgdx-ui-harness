package dev.gdx.uiharness.core.locator;


/** Immutable predicate composed with a {@link Locator}. */
public sealed interface LocatorFilter permits NameFilter, HasFilter, HasTextFilter, StateFilter {
    /** Boolean semantic states available to locator filters. */
    enum State {
        /** Effective visibility. */
        VISIBLE,
        /** Touch handling participation. */
        TOUCHABLE,
        /** Widget enabled state. */
        ENABLED,
        /** Widget checked state. */
        CHECKED,
        /** Widget selected state. */
        SELECTED,
        /** Widget expanded state. */
        EXPANDED,
        /** Widget editable state. */
        EDITABLE,
        /** Current focus state. */
        FOCUSED,
        /** Ability to receive focus. */
        FOCUSABLE,
        /** Whether clipping affects the node. */
        CLIPPED,
        /** Whether the node intersects its viewport. */
        VIEWPORT_INTERSECTING,
        /** Whether hit testing selects the node. */
        HIT_TARGET
    }

    /** Creates an accessible-name filter. */
    static LocatorFilter name(TextMatch name) {
        return new NameFilter(name);
    }

    /** Creates a filter requiring a matching descendant. */
    static LocatorFilter has(Locator descendant) {
        return new HasFilter(descendant);
    }

    /** Creates a filter requiring matching text in the node's subtree. */
    static LocatorFilter hasText(TextMatch text) {
        return new HasTextFilter(text);
    }

    /** Creates an exact boolean state filter. Unsupported optional states never match. */
    static LocatorFilter state(State state, boolean expected) {
        return new StateFilter(state, expected);
    }
}

