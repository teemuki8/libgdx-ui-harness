package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ReplacementProcessCoordinatorTest {
    @Test void cancellationBeforeLaunchNeverStartsReplacementJvm() {
        ArrayDeque<Runnable> pending = new ArrayDeque<>();
        Executor controlled = pending::add;
        AtomicInteger launches = new AtomicInteger();
        var coordinator = new ReplacementProcessCoordinator(
                "desktop-restart-1280x720", controlled, request -> {
                    launches.incrementAndGet();
                    throw new AssertionError("replacement process must not launch");
                });

        var handoff = coordinator.restart(request()).toCompletableFuture();
        assertTrue(handoff.cancel(false));
        pending.remove().run();

        assertTrue(handoff.isCancelled());
        assertEquals(0, launches.get());
        coordinator.close();
    }

    private static ScenarioRequest request() {
        return new ScenarioRequest(
                ScenarioDefinition.SCHEMA_VERSION,
                "reference-reset",
                1,
                Map.of(),
                "desktop-restart-1280x720",
                Deadline.after(System::nanoTime, Duration.ofSeconds(5)));
    }
}
