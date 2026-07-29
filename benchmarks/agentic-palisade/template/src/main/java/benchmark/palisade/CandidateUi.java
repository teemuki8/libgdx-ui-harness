package benchmark.palisade;

import com.badlogic.gdx.scenes.scene2d.Stage;

/** UI contract implemented by the measured candidate. */
public interface CandidateUi {
    /** Returns the root Scene2D stage. */
    Stage stage();

    /** Restores the initial observable state. */
    void showInitial();

    /** Returns a bounded, JSON-compatible state snapshot. */
    CandidateState snapshotState();

    /** Releases resources owned by the candidate. */
    void dispose();
}
