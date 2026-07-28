package dev.gdx.uiharness.core.wait;

import dev.gdx.uiharness.core.locator.QueryResult;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.Objects;

/** Fresh immutable snapshot and locator result that satisfied a wait condition. */
public record WaitResult(SemanticSnapshot snapshot, QueryResult queryResult) {
    /** Validates the completed wait result. */
    public WaitResult {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(queryResult, "queryResult");
    }
}
