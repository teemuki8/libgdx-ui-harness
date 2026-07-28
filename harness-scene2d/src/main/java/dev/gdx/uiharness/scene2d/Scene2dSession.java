package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.Objects;

/** Non-owning semantic extraction session attached to one Scene2D stage. */
public final class Scene2dSession implements AutoCloseable {
    private final Stage stage;
    private final Semantics semantics;
    private final ActorAdapterRegistry adapters;
    private final Scene2dSnapshotter snapshotter;
    private volatile boolean open = true;

    /** Attaches a session using the default publication limits. */
    public Scene2dSession(Stage stage) {
        this(stage, HarnessLimits.defaults());
    }

    /** Attaches a session using explicit publication limits. */
    public Scene2dSession(Stage stage, HarnessLimits limits) {
        this.stage = Objects.requireNonNull(stage, "stage");
        semantics = new Semantics(this::isOpen);
        adapters = new ActorAdapterRegistry();
        snapshotter = new Scene2dSnapshotter(limits, semantics, adapters);
    }

    /** Returns the metadata facade owned by this session. */
    public Semantics semantics() {
        return semantics;
    }

    /** Returns this session's built-in/custom adapter registry. */
    public ActorAdapterRegistry adapters() {
        requireOpen();
        return adapters;
    }

    /** Captures the attached stage at the supplied semantic revision and frame. */
    public SemanticSnapshot snapshot(long revision, long frame) {
        requireOpen();
        return snapshotter.snapshot(stage, revision, frame);
    }

    /** Returns whether the session still accepts requests. */
    public boolean isOpen() {
        return open;
    }

    /** Closes metadata ownership without disposing the application-owned stage. */
    @Override public void close() {
        if (open) {
            open = false;
            semantics.close();
        }
    }

    private void requireOpen() {
        if (!open) {
            throw new HarnessException(
                    ErrorCode.SESSION_CLOSED,
                    "Scene2D session is closed",
                    ErrorEvidence.empty());
        }
    }
}
