package benchmark.palisade;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import java.lang.reflect.InvocationTargetException;

/** Owns the candidate Stage and advances it by one fixed step per rendered frame. */
public final class CandidateApplication extends ApplicationAdapter {
    static final String CANDIDATE_CLASS = "benchmark.palisade.SkirmishConfigurationUi";
    private static final float FIXED_STEP_SECONDS = 1f / 60f;

    private final BenchmarkControl control;
    private CandidateUi candidate;

    /** Creates an application controlled by one finite command stream. */
    public CandidateApplication(BenchmarkControl control) {
        this.control = control;
    }

    @Override public void create() {
        TrustedStructuralProbe.verifyLoaded();
        candidate = loadCandidate();
        Stage stage = requireStage();
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        candidate.showInitial();
        Gdx.input.setInputProcessor(stage);
    }

    @Override public void render() {
        Stage stage = requireStage();
        control.beforeFrame(stage);
        stage.act(FIXED_STEP_SECONDS);
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.draw();
        CandidateState state = candidate.snapshotState();
        if (state == null) {
            throw new IllegalStateException("Candidate returned a null state snapshot");
        }
        control.afterCompletedFrame(stage, state);
        if (control.exitRequested()) {
            Gdx.app.exit();
        }
    }

    @Override public void resize(int width, int height) {
        if (candidate != null) {
            requireStage().getViewport().update(width, height, true);
        }
    }

    @Override public void dispose() {
        try {
            if (candidate != null) {
                candidate.dispose();
            }
        } finally {
            control.close();
        }
    }

    private Stage requireStage() {
        Stage stage = candidate.stage();
        if (stage == null) {
            throw new IllegalStateException("Candidate returned a null Stage");
        }
        return stage;
    }

    private static CandidateUi loadCandidate() {
        Class<?> type;
        try {
            type = Class.forName(CANDIDATE_CLASS);
        } catch (ClassNotFoundException absent) {
            return new BlankCandidateUi();
        }
        if (!CandidateUi.class.isAssignableFrom(type)) {
            throw new IllegalStateException(CANDIDATE_CLASS + " must implement CandidateUi");
        }
        try {
            return (CandidateUi) type.getConstructor().newInstance();
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                    CANDIDATE_CLASS + " must have a public no-argument constructor", failure);
        } catch (InstantiationException | IllegalAccessException
                | InvocationTargetException failure) {
            throw new IllegalStateException("Could not construct " + CANDIDATE_CLASS, failure);
        }
    }

}
