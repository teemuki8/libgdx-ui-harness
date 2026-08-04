package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class Scene2dScenarioRunnerTest {
    @Test void lifecycleAndStageWorkRunOnRenderThreadAndReadinessNeedsCompletedFrame() {
        try (Fixture fixture = new Fixture()) {
            Thread renderThread = Thread.currentThread();
            List<Thread> hookThreads = new ArrayList<>();
            fixture.register(new RecordingLifecycle(hookThreads, true, "ready"));

            CompletionStage<ScenarioResult> started;
            try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
                started = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> fixture.start(Duration.ofSeconds(1)), caller)
                        .join();
            }
            fixture.scheduler.drain();

            assertFalse(started.toCompletableFuture().isDone());
            assertEquals(List.of(renderThread, renderThread), hookThreads);

            fixture.completedFrame();

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(List.of(renderThread, renderThread, renderThread, renderThread, renderThread), hookThreads);
            assertEquals(1, result.startFrame());
            assertEquals(1, result.readyFrame());
            assertEquals(1, result.startRevision());
            assertEquals(1, result.readyRevision());
            assertEquals("ready", result.startStateIdentity());
            assertTrue(result.cleanupCompleted());
            assertTrue(result.failure().isEmpty());
        }
    }

    @Test void completedStageFramesCannotBeReadOffTheRenderThread() {
        try (Fixture fixture = new Fixture();
                ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var failure = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> java.util.concurrent.CompletableFuture
                            .runAsync(
                                    () -> fixture.session.completedFrame(fixture.runner, 1, 1),
                                    caller)
                            .join());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

    @Test void monotonicReadinessDeadlineIsDistinctFromSetupRejection() {
        try (Fixture deadline = new Fixture()) {
            deadline.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = deadline.start(Duration.ofMillis(10));
            deadline.scheduler.drain();
            deadline.completedFrame();
            assertEquals(
                    ScenarioFailure.READINESS_DEADLINE,
                    started.toCompletableFuture().join().failure().orElseThrow());
        }

        try (Fixture rejected = new Fixture()) {
            rejected.register(new ScenarioLifecycle() {
                @Override public void setup(ScenarioRequest request) {
                    throw new IllegalStateException("rejected");
                }
                @Override public void reset(ScenarioRequest request) {}
                @Override public boolean ready(ScenarioRequest request) { return true; }
                @Override public String startStateIdentity(
                        ScenarioRequest request, SemanticSnapshot snapshot) { return "unused"; }
                @Override public void cleanup(ScenarioRequest request) {}
            });
            CompletionStage<ScenarioResult> started = rejected.start(Duration.ofSeconds(1));
            rejected.scheduler.drain();
            assertEquals(
                    ScenarioFailure.SETUP_REJECTED,
                    started.toCompletableFuture().join().failure().orElseThrow());
        }
    }

    @Test void cancellationSchedulesRenderThreadCleanup() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanupThread.set(Thread.currentThread());
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();

            assertTrue(started.toCompletableFuture().cancel(false));
            assertFalse(started.toCompletableFuture().isDone());
            fixture.scheduler.drain();

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.CANCELLED, result.failure().orElseThrow());
            assertEquals(Thread.currentThread(), cleanupThread.get());
            assertTrue(result.cleanupCompleted());
        }
    }
    @Test void rejectedInitialSubmissionTerminalizesWithoutCleanup() {
        try (Fixture fixture = new Fixture(1)) {
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanupThread.set(Thread.currentThread());
                }
            });
            fixture.scheduler.close();

            ScenarioResult result =
                    fixture.start(Duration.ofSeconds(1)).toCompletableFuture().join();

            assertEquals(ScenarioFailure.DISPATCH_FAILED, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted());
            assertEquals(null, cleanupThread.get());
        }
    }

    @Test void rejectedFrameSubmissionTerminalizesWithoutCleanup() {
        try (Fixture fixture = new Fixture(1)) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.scheduler.close();

            fixture.session.completedFrame(fixture.runner, 1, 1);

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.DISPATCH_FAILED, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted());
        }
    }

    @Test void rejectedCancellationSubmissionTerminalizesInsteadOfRemainingCancelling() {
        try (Fixture fixture = new Fixture(1)) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.scheduler.close();

            assertTrue(started.toCompletableFuture().cancel(false));

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.DISPATCH_FAILED, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted());
        }
    }

    @Test void deadlineExpirySchedulesRenderThreadCleanupWithoutAnotherFrame() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanupThread.set(Thread.currentThread());
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofMillis(10));
            fixture.scheduler.drain();

            fixture.clock.advance(Duration.ofMillis(10));
            fixture.deadlines.expire();
            assertFalse(started.toCompletableFuture().isDone());
            fixture.scheduler.drain();

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.READINESS_DEADLINE, result.failure().orElseThrow());
            assertEquals(Thread.currentThread(), cleanupThread.get());
            assertTrue(result.cleanupCompleted());
        }
    }

    @Test void terminalCompletionInvalidatesScheduledDeadline() {
        try (Fixture fixture = new Fixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready"));

            fixture.complete(fixture.start(Duration.ofSeconds(1)));

            assertTrue(fixture.deadlines.cancelled);
        }
    }


    @Test void repeatedInputsRetainIdentityOrReportNondeterminism() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<String> identity = new AtomicReference<>("stable");
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "unused") {
                @Override public String startStateIdentity(
                        ScenarioRequest request, SemanticSnapshot snapshot) {
                    return identity.get();
                }
            });

            ScenarioResult first = fixture.complete(fixture.start(Duration.ofSeconds(1)));
            ScenarioResult repeated = fixture.complete(fixture.start(Duration.ofSeconds(1)));
            assertEquals(first.startStateIdentity(), repeated.startStateIdentity());
            assertTrue(repeated.failure().isEmpty());

            identity.set("changed");
            ScenarioResult changed = fixture.complete(fixture.start(Duration.ofSeconds(1)));
            assertEquals(
                    ScenarioFailure.NONDETERMINISTIC_INITIAL_STATE,
                    changed.failure().orElseThrow());
        }
    }

    private static class RecordingLifecycle implements ScenarioLifecycle {
        private final List<Thread> hookThreads;
        private final boolean ready;
        private final String identity;

        RecordingLifecycle(List<Thread> hookThreads, boolean ready, String identity) {
            this.hookThreads = hookThreads;
            this.ready = ready;
            this.identity = identity;
        }

        @Override public void setup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public void reset(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public boolean ready(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
            return ready;
        }

        @Override public String startStateIdentity(
                ScenarioRequest request, SemanticSnapshot snapshot) {
            hookThreads.add(Thread.currentThread());
            return identity;
        }

        @Override public void cleanup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }
    }
    private static final class ManualDeadlineScheduler
            implements Scene2dScenarioDeadlineScheduler {
        private Runnable task;
        private boolean cancelled;

        @Override public Cancellation schedule(Duration delay, Runnable task) {
            this.task = task;
            return () -> cancelled = true;
        }

        void expire() {
            if (!cancelled) {
                task.run();
            }
        }
    }


    private static final class Fixture implements AutoCloseable {
        private static final Duration STEP = Duration.ofMillis(10);
        final Stage stage = Scene2dTestSupport.stage();
        final ControlledStageClock clock = new ControlledStageClock(stage, STEP);
        final RenderThreadScheduler scheduler;
        final ManualDeadlineScheduler deadlines = new ManualDeadlineScheduler();
        final Scene2dSession session = new Scene2dSession(stage);
        final ScenarioRegistry registry = new ScenarioRegistry();
        final Scene2dScenarioRunner runner;

        Fixture() {
            this(16);
        }

        Fixture(int schedulerCapacity) {
            scheduler = new RenderThreadScheduler(schedulerCapacity);
            runner = new Scene2dScenarioRunner(registry, scheduler, clock, deadlines);
        }

        void register(ScenarioLifecycle lifecycle) {
            registry.register(
                    new ScenarioDefinition(
                            ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready",
                            "1.0.0",
                            "test-app",
                            List.of("desktop"),
                            1,
                            Duration.ofSeconds(2)),
                    lifecycle);
        }

        CompletionStage<ScenarioResult> start(Duration timeout) {
            return runner.start(
                    new ScenarioRequest(
                            ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready",
                            42L,
                            Map.of("locale", "en", "account", "agent"),
                            "desktop",
                            Deadline.after(clock, timeout)),
                    "test-app",
                    "process-1",
                    "session-1");
        }

        void completedFrame() {
            clock.advance(STEP);
            session.completedFrame(runner, clock.revision(), clock.frame());
            scheduler.drain();
        }

        ScenarioResult complete(CompletionStage<ScenarioResult> started) {
            scheduler.drain();
            completedFrame();
            return started.toCompletableFuture().join();
        }

        @Override public void close() {
            runner.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }
    }
}
