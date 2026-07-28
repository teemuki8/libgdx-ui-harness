package dev.gdx.uiharness.core.locator;

import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;

/** Backend-neutral evaluator for immutable locators against supplied semantic snapshots. */
public interface LocatorEngine {
    /** Evaluates a locator against this supplied snapshot only. */
    QueryResult query(SemanticSnapshot snapshot, Locator locator);

    /** Resolves exactly one node or throws a typed not-found or strictness failure. */
    SemanticNode resolveStrict(SemanticSnapshot snapshot, Locator locator);
}
