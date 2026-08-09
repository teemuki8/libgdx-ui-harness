package benchmark.palisade;

import dev.gdx.markup.core.BuiltUi;

/** UI contract implemented by the measured candidate. */
public interface CandidateUi {
    /** Binds candidate behavior to the template-owned markup actor tree. */
    void bind(BuiltUi ui);

    /** Restores the initial observable state. */
    void showInitial();

    /** Returns a bounded, JSON-compatible state snapshot. */
    CandidateState snapshotState();

    /** Releases resources owned by the candidate. */
    void dispose();
}
