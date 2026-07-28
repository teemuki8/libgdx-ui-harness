package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.model.Role;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/** Attaches semantic metadata to actors without requiring actor subclasses. */
public final class Semantics {
    private final ReferenceQueue<Actor> staleActors = new ReferenceQueue<>();
    private final Map<IdentityWeakReference, ActorMetadata> metadata = new HashMap<>();
    private final BooleanSupplier open;

    Semantics(BooleanSupplier open) {
        this.open = Objects.requireNonNull(open, "open");
    }

    /** Overrides the inferred semantic role. */
    public void setRole(Actor actor, Role role) {
        Objects.requireNonNull(role, "role");
        update(actor, value -> value.withRole(role));
    }

    /** Overrides the inferred accessible name. */
    public void setAccessibleName(Actor actor, String accessibleName) {
        Objects.requireNonNull(accessibleName, "accessibleName");
        update(actor, value -> value.withAccessibleName(accessibleName));
    }

    /** Overrides the inferred visible text. */
    public void setText(Actor actor, String text) {
        Objects.requireNonNull(text, "text");
        update(actor, value -> value.withText(text));
    }

    /** Associates a human-readable label with an actor. */
    public void setLabel(Actor actor, String label) {
        Objects.requireNonNull(label, "label");
        update(actor, value -> value.withLabel(label));
    }

    /** Associates a stable automation identifier with an actor. */
    public void setTestId(Actor actor, String testId) {
        Objects.requireNonNull(testId, "testId");
        update(actor, value -> value.withTestId(testId));
    }

    /** Adds or replaces one bounded custom property. */
    public void setProperty(Actor actor, String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        update(actor, metadata -> metadata.withProperty(key, value));
    }

    /** Removes all explicit semantic metadata for one actor. */
    public synchronized void clear(Actor actor) {
        requireOpen();
        expungeStaleEntries();
        metadata.remove(IdentityWeakReference.lookup(Objects.requireNonNull(actor, "actor")));
    }

    synchronized ActorMetadata metadata(Actor actor) {
        requireOpen();
        expungeStaleEntries();
        ActorMetadata value = metadata.get(IdentityWeakReference.lookup(actor));
        return value == null ? ActorMetadata.EMPTY : value;
    }

    synchronized void close() {
        metadata.clear();
        while (staleActors.poll() != null) {
            // Drain references so a closed session retains no stale keys.
        }
    }

    private synchronized void update(Actor actor, UnaryOperator<ActorMetadata> change) {
        requireOpen();
        Objects.requireNonNull(actor, "actor");
        expungeStaleEntries();
        IdentityWeakReference lookup = IdentityWeakReference.lookup(actor);
        ActorMetadata current = metadata.get(lookup);
        ActorMetadata updated = change.apply(current == null ? ActorMetadata.EMPTY : current);
        metadata.put(new IdentityWeakReference(actor, staleActors), updated);
    }

    private void requireOpen() {
        if (!open.getAsBoolean()) {
            throw new HarnessException(
                    ErrorCode.SESSION_CLOSED,
                    "Scene2D session is closed",
                    ErrorEvidence.empty());
        }
    }

    private void expungeStaleEntries() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) staleActors.poll()) != null) {
            metadata.remove(reference);
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
            return new IdentityWeakReference(Objects.requireNonNull(actor, "actor"));
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
