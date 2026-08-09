package benchmark.palisade;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.DefaultSkin;
import dev.gdx.markup.core.MarkupBuilder;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.SemanticSink;
import dev.gdx.markup.core.style.CssDocument;
import dev.gdx.markup.core.style.CssParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Owns the one Stage and bounded markup construction path shared by both treatments. */
public final class CandidateMarkupStage implements AutoCloseable {
    private static final int MAX_RESOURCE_BYTES = 1_048_576;
    private static final String XML_RESOURCE = "ui/skirmish.xml";
    private static final String CSS_RESOURCE = "ui/skirmish.css";

    private final CandidateUi candidate;
    private final Stage stage = new Stage(new ScreenViewport());
    private final Skin skin = DefaultSkin.create();
    private boolean built;
    private boolean closed;

    /** Creates the Stage shell. Call {@link #build} once after selecting the treatment sink. */
    public CandidateMarkupStage(CandidateUi candidate) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        stage.getRoot().setName("candidate-markup-stage");
    }

    /** Returns the template-owned Stage so a harness session can attach before markup is built. */
    public Stage stage() {
        requireOpen();
        return stage;
    }

    /** Parses and builds the fixed classpath resources exactly once on the render thread. */
    public BuiltUi build(SemanticSink sink) {
        requireOpen();
        if (built) {
            throw new IllegalStateException("candidate markup is already built");
        }
        Objects.requireNonNull(sink, "sink");
        MarkupDocument document = new MarkupParser().parse(readResource(XML_RESOURCE));
        CssDocument css = new CssParser().parse(readResource(CSS_RESOURCE));
        BuiltUi ui = MarkupBuilder.build(document, css, skin, sink);
        ui.root().setSize(stage.getViewport().getWorldWidth(),
                stage.getViewport().getWorldHeight());
        stage.addActor(ui.root());
        candidate.bind(ui);
        candidate.showInitial();
        built = true;
        return ui;
    }

    /** Returns candidate-owned bounded state after markup binding. */
    public CandidateState snapshotState() {
        if (!built) {
            throw new IllegalStateException("candidate markup is not built");
        }
        CandidateState state = candidate.snapshotState();
        if (state == null) {
            throw new IllegalStateException("Candidate returned a null state snapshot");
        }
        return state;
    }

    @Override public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            candidate.dispose();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            skin.dispose();
        } catch (RuntimeException closeFailure) {
            failure = append(failure, closeFailure);
        }
        try {
            stage.dispose();
        } catch (RuntimeException closeFailure) {
            failure = append(failure, closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String readResource(String name) {
        ClassLoader loader = CandidateMarkupStage.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Required markup resource is missing: " + name);
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IllegalStateException("Markup resource exceeds byte limit: " + name);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read markup resource: " + name, failure);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("candidate markup stage is closed");
        }
    }

    private static RuntimeException append(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}
