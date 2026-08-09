package benchmark.palisade;

import dev.gdx.markup.core.BuiltUi;
import java.util.Objects;

/** Empty candidate used only to prove that an untouched template can launch. */
final class BlankCandidateUi implements CandidateUi {
    @Override public void bind(BuiltUi ui) {
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(ui.root().findActor("skirmish-root"), "skirmish-root");
    }

    @Override public void showInitial() {
        // The shared markup resource already represents the initial state.
    }

    @Override public CandidateState snapshotState() {
        return CandidateState.empty();
    }

    @Override public void dispose() {
        // This controller owns no libGDX resources.
    }
}
