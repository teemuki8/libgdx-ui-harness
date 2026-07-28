package dev.gdx.uiharness.core.locator;

import dev.gdx.uiharness.core.model.Role;
import java.util.Objects;

record RoleLocator(Role role) implements Locator {
    RoleLocator {
        Objects.requireNonNull(role, "role");
    }
}
