package dev.gdx.uiharness.core.locator;

import dev.gdx.uiharness.core.model.Role;
import java.util.Objects;

/**
 * Immutable lazy description of a semantic query. A locator never retains a snapshot node or a
 * backend actor.
 */
public sealed interface Locator permits RoleLocator, TextLocator, TestIdLocator,
        ActorLocator, RelationLocator, FilteredLocator, IndexedLocator {
    /** Creates a locator for a semantic role. */
    static Locator role(Role role) {
        return new RoleLocator(role);
    }

    /** Creates a locator for visible semantic text. */
    static Locator text(TextMatch text) {
        return new TextLocator(TextField.TEXT, text);
    }

    /** Creates a locator for an associated label. */
    static Locator label(TextMatch label) {
        return new TextLocator(TextField.LABEL, label);
    }

    /** Creates an exact locator for an explicit test identifier. */
    static Locator testId(String testId) {
        return new TestIdLocator(testId);
    }

    /** Creates a locator for a backend actor name used as a fallback. */
    static Locator actorName(TextMatch actorName) {
        return new ActorLocator(ActorField.NAME, actorName);
    }

    /** Creates a locator for a backend actor type used as a fallback. */
    static Locator actorType(TextMatch actorType) {
        return new ActorLocator(ActorField.TYPE, actorType);
    }

    /** Adds a predicate to this locator. */
    default Locator filter(LocatorFilter filter) {
        return new FilteredLocator(this, filter);
    }

    /** Selects matching descendants of nodes matched by this locator. */
    default Locator descendant(Locator child) {
        return new RelationLocator(this, child, Relation.DESCENDANT);
    }

    /** Selects matching direct children of nodes matched by this locator. */
    default Locator child(Locator child) {
        return new RelationLocator(this, child, Relation.CHILD);
    }

    /** Selects matching direct parents of nodes matched by this locator. */
    default Locator parent(Locator parent) {
        return new RelationLocator(this, parent, Relation.PARENT);
    }

    /** Selects matching siblings of nodes matched by this locator. */
    default Locator sibling(Locator sibling) {
        return new RelationLocator(this, sibling, Relation.SIBLING);
    }

    /** Restricts this locator by accessible name. */
    default Locator withName(TextMatch name) {
        return filter(LocatorFilter.name(name));
    }

    /** Restricts this locator to nodes containing a matching descendant. */
    default Locator has(Locator descendant) {
        return filter(LocatorFilter.has(descendant));
    }

    /** Restricts this locator to nodes whose subtree contains matching text. */
    default Locator hasText(TextMatch text) {
        return filter(LocatorFilter.hasText(text));
    }

    /** Selects a zero-based match and marks the resulting query as structurally fragile. */
    default Locator atIndex(int index) {
        return new IndexedLocator(this, index);
    }
}

record RoleLocator(Role role) implements Locator {
    RoleLocator {
        Objects.requireNonNull(role, "role");
    }
}

enum TextField {
    TEXT,
    LABEL
}

record TextLocator(TextField field, TextMatch text) implements Locator {
    TextLocator {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(text, "text");
    }
}

record TestIdLocator(String testId) implements Locator {
    TestIdLocator {
        TextMatch.requireBounded(testId, "testId");
    }
}

enum ActorField {
    NAME,
    TYPE
}

record ActorLocator(ActorField field, TextMatch text) implements Locator {
    ActorLocator {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(text, "text");
    }
}

enum Relation {
    CHILD,
    DESCENDANT,
    PARENT,
    SIBLING
}

record RelationLocator(Locator anchor, Locator target, Relation relation) implements Locator {
    RelationLocator {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(relation, "relation");
    }
}

record FilteredLocator(Locator locator, LocatorFilter filter) implements Locator {
    FilteredLocator {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(filter, "filter");
    }
}

record IndexedLocator(Locator locator, int index) implements Locator {
    IndexedLocator {
        Objects.requireNonNull(locator, "locator");
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
    }
}
