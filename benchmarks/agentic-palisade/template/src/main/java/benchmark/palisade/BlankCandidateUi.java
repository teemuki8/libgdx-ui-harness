package benchmark.palisade;

import com.badlogic.gdx.scenes.scene2d.Stage;

/** Empty candidate used only to prove that an untouched template can launch. */
final class BlankCandidateUi implements CandidateUi {
    private final Stage stage = new Stage();

    @Override public Stage stage() {
        return stage;
    }

    @Override public void showInitial() {
        stage.clear();
    }

    @Override public CandidateState snapshotState() {
        return CandidateState.empty();
    }

    @Override public void dispose() {
        stage.dispose();
    }
}
