package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import dev.gdx.uiharness.core.model.Role;

/** Contributes inferred semantic values for actors of a registered class. */
@FunctionalInterface
public interface ActorSemanticAdapter<A extends Actor> {
    /** Adds semantic values to the bounded, validation-controlled target. */
    void contribute(A actor, Target target);

    /**
     * Public adapter view implemented by a package-private builder. Values are validated before
     * they can be published as immutable snapshot nodes.
     */
    interface Target {
        /** Sets the inferred role. */
        Target role(Role value);

        /** Sets the inferred accessible name. */
        Target accessibleName(String value);

        /** Sets normalized visible text. */
        Target text(String value);

        /** Sets an inferred label. */
        Target label(String value);

        /** Sets an inferred test identifier. */
        Target testId(String value);

        /** Marks whether the widget is enabled. */
        Target enabled(boolean value);

        /** Marks whether the widget is checked. */
        Target checked(boolean value);

        /** Marks whether the widget has a selection. */
        Target selected(boolean value);

        /** Marks whether the widget is expanded. */
        Target expanded(boolean value);

        /** Marks whether the widget is editable. */
        Target editable(boolean value);

        /** Marks whether the widget supports focus. */
        Target focusable(boolean value);

        /** Adds or replaces a custom semantic property. */
        Target property(String key, String value);
    }
}
