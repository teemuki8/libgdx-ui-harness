package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Test @Timeout(30) void closeDuringLaunchClosesChildAndTerminalizesHandoff() throws Exception {
        CountDownLatch launched = new CountDownLatch(1);
        CountDownLatch returnFromLauncher = new CountDownLatch(1);
        AtomicReference<ReplacementProcess> child = new AtomicReference<>();
        var coordinator = new ReplacementProcessCoordinator(
                "desktop-restart-1280x720",
                command -> new Thread(command, "replacement-launch-test").start(),
                request -> {
                    ReplacementProcess process = ReplacementProcess.launch(request);
                    child.set(process);
                    launched.countDown();
                    assertTrue(returnFromLauncher.await(10, TimeUnit.SECONDS));
                    return process;
                });

        var handoff = coordinator.restart(request()).toCompletableFuture();
        assertTrue(launched.await(10, TimeUnit.SECONDS));
        coordinator.close();
        returnFromLauncher.countDown();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> handoff.get(10, TimeUnit.SECONDS));
        assertTrue(handoff.isCancelled());
        assertTrue(child.get().isClosed());
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
