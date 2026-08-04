package dev.gdx.uiharness.core.assertion;

import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.wait.FrameSignal;

/** Supplies immutable semantic snapshots for assertion evaluation. */
public interface AssertionSnapshotSource {
    /** Returns the current immutable snapshot for the assertion's initial evaluation. */
    SemanticSnapshot currentSnapshot();

    /** Returns the immutable snapshot captured for exactly the delivered completed frame. */
    SemanticSnapshot snapshotFor(FrameSignal.Frame frame);
}
