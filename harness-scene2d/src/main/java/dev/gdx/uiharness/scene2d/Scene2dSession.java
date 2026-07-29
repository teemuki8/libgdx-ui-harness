package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Non-owning semantic extraction session attached to one Scene2D stage. */
public final class Scene2dSession implements AutoCloseable {
    private final Stage stage;
    private final Semantics semantics;
    private final ActorAdapterRegistry adapters;
    private final Scene2dSnapshotter snapshotter;
    private final Scene2dContractSnapshotter contractSnapshotter;
    private final ActorTokens actorTokens = new ActorTokens();
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
        contractSnapshotter =
                new Scene2dContractSnapshotter(stage, semantics, adapters, snapshotter);
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

    /** Captures the evaluator-complete contract after a completed frame. */
    public StateActionContract stateActionContract(long revision, long frame) {
        requireOpen();
        return contractSnapshotter.snapshot(revision, frame);
    }

    /** Returns this session's stable weak-identity token without retaining the Actor. */
    long actorToken(Actor actor) {
        requireOpen();
        return actorTokens.token(Objects.requireNonNull(actor, "actor"));
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
            actorTokens.clear();
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

    private static final class ActorTokens {
        private final ReferenceQueue<Actor> staleActors = new ReferenceQueue<>();
        private final Map<IdentityWeakReference, Long> tokens = new HashMap<>();
        private long nextToken = 1;

        synchronized long token(Actor actor) {
            expungeStaleActors();
            IdentityWeakReference lookup = IdentityWeakReference.lookup(actor);
            Long existing = tokens.get(lookup);
            if (existing != null) {
                return existing;
            }
            long assigned = nextToken;
            nextToken = Math.incrementExact(nextToken);
            tokens.put(new IdentityWeakReference(actor, staleActors), assigned);
            return assigned;
        }

        synchronized void clear() {
            tokens.clear();
            while (staleActors.poll() != null) {
                // Drain the queue so a closed session retains no stale keys.
            }
        }

        private void expungeStaleActors() {
            IdentityWeakReference stale;
            while ((stale = (IdentityWeakReference) staleActors.poll()) != null) {
                tokens.remove(stale);
            }
        }
    }

    private static final class IdentityWeakReference extends WeakReference<Actor> {
        private final int identityHash;

        IdentityWeakReference(Actor actor, ReferenceQueue<Actor> queue) {
            super(actor, queue);
            identityHash = System.identityHashCode(actor);
        }

        private IdentityWeakReference(Actor actor) {
            super(actor);
            identityHash = System.identityHashCode(actor);
        }

        static IdentityWeakReference lookup(Actor actor) {
            return new IdentityWeakReference(actor);
        }

        @Override public int hashCode() {
            return identityHash;
        }

        @Override public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference reference)) {
                return false;
            }
            Actor actor = get();
            return actor != null && actor == reference.get();
        }
    }
}
