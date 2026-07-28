package dev.gdx.uiharness.core.locator;

import java.util.Objects;

record RelationLocator(Locator anchor, Locator target, Relation relation) implements Locator {
    RelationLocator {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(relation, "relation");
    }
}
