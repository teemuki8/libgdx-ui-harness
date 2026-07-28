package dev.gdx.uiharness.core.wait;

import dev.gdx.uiharness.core.locator.QueryResult;
import dev.gdx.uiharness.core.model.SemanticSnapshot;

/** Snapshot-only predicate reevaluated after completed semantic frames. */
@FunctionalInterface
public interface WaitCondition {
    /** Returns whether the current fresh locator result satisfies this condition. */
    boolean isSatisfied(SemanticSnapshot snapshot, QueryResult result);

    /** Waits until the locator resolves to exactly one node. */
    static WaitCondition present() {
        return (snapshot, result) -> result.matches().size() == 1;
    }

    /** Waits until the locator resolves to exactly one effectively visible node. */
    static WaitCondition visible() {
        return (snapshot, result) ->
                result.matches().size() == 1 && result.matches().getFirst().state().visible();
    }
}
