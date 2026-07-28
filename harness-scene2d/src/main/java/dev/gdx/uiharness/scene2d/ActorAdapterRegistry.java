package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Class-dispatched registry for built-in and application actor semantic adapters. */
public final class ActorAdapterRegistry {
    private final Map<Class<? extends Actor>, ActorSemanticAdapter<?>> adapters =
            new LinkedHashMap<>();

    /** Creates a registry preloaded with the built-in Scene2D.UI adapters. */
    public ActorAdapterRegistry() {
        BuiltinWidgetAdapters.registerInto(this);
    }

    /** Registers or replaces the adapter for an exact actor class. */
    public synchronized <A extends Actor> void register(
            Class<A> actorClass, ActorSemanticAdapter<? super A> adapter) {
        adapters.put(
                Objects.requireNonNull(actorClass, "actorClass"),
                Objects.requireNonNull(adapter, "adapter"));
    }

    void contribute(Actor actor, SemanticNodeBuilder target) {
        ActorSemanticAdapter<?> selected = null;
        int selectedDistance = Integer.MAX_VALUE;
        synchronized (this) {
            for (Map.Entry<Class<? extends Actor>, ActorSemanticAdapter<?>> entry
                    : adapters.entrySet()) {
                Class<? extends Actor> registeredClass = entry.getKey();
                if (!registeredClass.isInstance(actor)) {
                    continue;
                }
                int distance = inheritanceDistance(actor.getClass(), registeredClass);
                if (distance < selectedDistance) {
                    selected = entry.getValue();
                    selectedDistance = distance;
                }
            }
        }
        if (selected != null) {
            invoke(selected, actor, target);
        }
    }

    @SuppressWarnings("unchecked")
    private static void invoke(
            ActorSemanticAdapter<?> adapter, Actor actor, SemanticNodeBuilder target) {
        ((ActorSemanticAdapter<Actor>) adapter).contribute(actor, target);
    }

    private static int inheritanceDistance(Class<?> concrete, Class<?> ancestor) {
        int distance = 0;
        for (Class<?> current = concrete; current != null; current = current.getSuperclass()) {
            if (current == ancestor) {
                return distance;
            }
            distance++;
        }
        return Integer.MAX_VALUE;
    }
}
