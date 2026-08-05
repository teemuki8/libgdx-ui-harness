package dev.gdx.uiharness.core.locator;

import dev.gdx.uiharness.core.model.Role;
import java.util.Objects;

public record RoleLocator(Role role) implements Locator {
    public RoleLocator {
        Objects.requireNonNull(role, "role");
    }
}
