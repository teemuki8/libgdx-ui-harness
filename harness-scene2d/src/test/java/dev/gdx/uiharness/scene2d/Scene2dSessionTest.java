package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.navigation.NavigationInput;
import dev.gdx.uiharness.core.navigation.NavigationRequest;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import dev.gdx.uiharness.core.navigation.NavigationStep;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Task 6 (#22) RED wave: the session's two-runner {@code completedFrame} must gate snapshot
 * building on active runner subscriptions. Idle and post-terminal frames must build no
 * snapshot; an active run enables exactly the frames it observes; the shared snapshot is
 * built at most once per decided frame even when both runners are active.
 */
final class Scene2dSessionTest {
    @Test void idleFramesBuildNoRunnerSnapshots() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.completedFrame();
            fixture.completedFrame();
            fixture.completedFrame();
            assertEquals(0, fixture.rootReads(), "idle frames must not build runner snapshots");
            assertEquals(3, fixture.frame());
        }
    }

    @Test void startingARunEnablesSnapshotsThroughItsFirstObservation() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(2));
            fixture.scheduler.drain();

            fixture.completedFrame();
            assertEquals(1, fixture.rootReads(),
                    "the first frame after start must reach the waiting run");
            fixture.completedFrame();
            assertEquals(2, fixture.rootReads());
            assertFalse(started.toCompletableFuture().isDone());
        }
    }

    @Test void cancellingTheLastRunReturnsTheSessionToIdle() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(2));
            fixture.scheduler.drain();
            fixture.completedFrame();
            assertEquals(1, fixture.rootReads());

            assertTrue(started.toCompletableFuture().cancel(false));
            fixture.scheduler.drain();
            fixture.completedFrame();
            assertEquals(1, fixture.rootReads(),
                    "terminal runs must stop the per-frame snapshot stream");
        }
    }

    @Test void navigationRunsEnableTheSharedSnapshotStreamAndIdleDisablesIt() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.completedFrame();
            assertEquals(0, fixture.rootReads());

            fixture.route(Keys.TAB, fixture.second);
            CompletionStage<NavigationResult> inspect =
                    fixture.navigation.inspect(fixture.request(List.of(NavigationInput.TAB)));
            fixture.nextFrame(); // scenario acquire observes the first completed frame
            assertTrue(fixture.rootReads() > 0,
                    "an active navigation run must enable snapshots");
            fixture.drainFrames(8);
            assertTrue(inspect.toCompletableFuture().isDone(),
                    "navigation must complete once its step is observed");

            long after = fixture.rootReads();
            fixture.completedFrame();
            assertEquals(after, fixture.rootReads(),
                    "after the last run finishes, completed frames build no snapshot");
        }
    }

    @Test void firstStartRaceNeverLosesTheRunFirstObservation() throws Exception {
        try (GatedFixture fixture = new GatedFixture();
                ExecutorService launcher = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch launched = new CountDownLatch(1);
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready"));
            CompletableFuture<CompletionStage<ScenarioResult>> startedHolder =
                    new CompletableFuture<>();
            launcher.submit(() -> {
                // The run is added to `active` synchronously at launch; begin() is queued.
                startedHolder.complete(fixture.start(Duration.ofSeconds(2)));
                launched.countDown();
            });

            // Owner thread: wait for the launch barrier (run active, begin still queued),
            // then decide the frame BEFORE any drain runs begin().
            assertTrue(launched.await(5, TimeUnit.SECONDS), "launch must reach the barrier");
            fixture.clock.advance(GatedFixture.STEP);
            fixture.session.completedFrame(fixture.scenarios, fixture.navigation,
                    fixture.clock.revision(), fixture.clock.frame());
            fixture.scheduler.drain();
            ScenarioResult result = startedHolder.join().toCompletableFuture().join();
            assertTrue(result.startFrame() > 0,
                    "the run must observe the frame decided while it was active");
            assertTrue(fixture.rootReads() >= 1);
        }
    }

    @Test void postTerminalFramesInvokeNoSupplier() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready"));
            CompletionStage<Scene2dScenarioRunner.Lease> acquired =
                    fixture.acquire(Duration.ofSeconds(2));
            fixture.scheduler.drain();
            fixture.completedFrame();
            Scene2dScenarioRunner.Lease lease = acquired.toCompletableFuture().join();

            // Sequential post-terminal gating (not a race): release, drain, then decide.
            CompletionStage<ScenarioResult> released = lease.release();
            fixture.scheduler.drain();
            released.toCompletableFuture().join();

            long before = fixture.rootReads();
            fixture.completedFrame();
            assertEquals(before, fixture.rootReads(),
                    "a post-terminal frame decision must never build a snapshot");
        }
    }

    /**
     * The shared-snapshot contract: with an active navigation run (and the scenario lease it
     * holds), one decided frame builds exactly one snapshot that both runner reservations
     * observe — the memoized supplier never builds twice for the same frame.
     */
    @Test void exactOnceWithBothRunnersActiveBuildsOneSharedSnapshot() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.route(Keys.TAB, fixture.second);
            CompletionStage<NavigationResult> inspect =
                    fixture.navigation.inspect(fixture.request(List.of(NavigationInput.TAB)));
            fixture.nextFrame(); // scenario acquire observes the first completed frame
            assertFalse(inspect.toCompletableFuture().isDone());

            // Both runners now have active subscriptions: the navigation run itself and the
            // scenario lease it holds. One shared snapshot must serve both reservations.
            long before = fixture.rootReads();
            fixture.completedFrame();
            assertEquals(before + 1, fixture.rootReads(),
                    "both active runners must share exactly one snapshot build");
            assertTrue(inspect.toCompletableFuture().isDone(),
                    "the shared frame must reach the waiting navigation step");
            NavigationResult result = inspect.toCompletableFuture().join();
            assertEquals(2, result.path().steps().get(0).afterFrame(),
                    "the navigation observation must carry the decided shared frame");
        }
    }

    @Test void onDemandSnapshotsAndFrameCorrelationKeepWorkingWhileIdle() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.completedFrame();
            fixture.completedFrame();
            SemanticSnapshot snapshot = fixture.session.snapshot(
                    fixture.clock.revision(), fixture.clock.frame());
            assertEquals(2, snapshot.frame());
            assertEquals(2, snapshot.revision());
        }
    }

    private static final class RecordingLifecycle implements ScenarioLifecycle {
        private final List<Thread> hookThreads;
        private final boolean readyImmediately;
        private final String identity;

        RecordingLifecycle(List<Thread> hookThreads, boolean readyImmediately, String identity) {
            this.hookThreads = hookThreads;
            this.readyImmediately = readyImmediately;
            this.identity = identity;
        }

        @Override public void setup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public void reset(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public boolean ready(ScenarioRequest request) {
            return readyImmediately;
        }

        @Override public String startStateIdentity(
                ScenarioRequest request, SemanticSnapshot snapshot) {
            return identity;
        }

        @Override public void cleanup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }
    }

    private static final class GatedFixture implements AutoCloseable {
        private static final Duration STEP = Duration.ofMillis(10);
        final CountingStage stage;
        final ControlledStageClock clock;
        final RenderThreadScheduler scheduler;
        final Scene2dSession session;
        final ScenarioRegistry registry = new ScenarioRegistry();
        final Scene2dScenarioRunner scenarios;
        final Scene2dNavigationRunner navigation;
        final RoutingInput input = new RoutingInput();
        final AtomicLong dispatches = new AtomicLong();
        final TextButton first;
        final TextButton second;

        GatedFixture() {
            GdxNativesLoader.load();
            NoopBatch.installGraphics();
            stage = new CountingStage();
            clock = new ControlledStageClock(stage, STEP);
            scheduler = new RenderThreadScheduler(64);
            session = new Scene2dSession(stage);
            registry.register(
                    new ScenarioDefinition(ScenarioDefinition.SCHEMA_VERSION,
                            "gated-nav", "1.0.0", "app", List.of("desktop"), 1,
                            Duration.ofSeconds(2)),
                    new ScenarioLifecycle() {
                        @Override public void setup(ScenarioRequest request) {}
                        @Override public void reset(ScenarioRequest request) {
                            stage.setKeyboardFocus(first);
                        }
                        @Override public boolean ready(ScenarioRequest request) { return true; }
                        @Override public String startStateIdentity(
                                ScenarioRequest request, SemanticSnapshot snapshot) {
                            return "ready";
                        }
                        @Override public void cleanup(ScenarioRequest request) {}
                    });
            first = button("first", 50);
            second = button("second", 250);
            scenarios = new Scene2dScenarioRunner(
                    registry, scheduler, clock, (delay, signal) -> () -> {});
            navigation = new Scene2dNavigationRunner(
                    scenarios, session, new Scene2dInputDispatcher(stage, input),
                    scheduler, clock, (delay, signal) -> () -> {}, clock::revision, clock::frame,
                    new Scene2dNavigationRunner.Scenario(
                            "gated-nav", 7, Map.of(), "desktop", "app", "process", "session"),
                    8);
        }

        TextButton button(String id, float x) {
            TextButton actor = new TextButton(id, WidgetStyles.textButton());
            actor.setBounds(x, 50, 160, 40);
            stage.addActor(actor);
            session.semantics().setTestId(actor, id);
            return actor;
        }

        void route(int key, Actor target) {
            input.routes.put(key, target);
        }

        void register(ScenarioLifecycle lifecycle) {
            registry.register(
                    new ScenarioDefinition(ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready", "1.0.0", "test-app", List.of("desktop"),
                            1, Duration.ofSeconds(2)),
                    lifecycle);
        }

        CompletionStage<ScenarioResult> start(Duration timeout) {
            return scenarios.start(
                    new ScenarioRequest(ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready", 42L, Map.of("locale", "en"), "desktop",
                            Deadline.after(clock, timeout)),
                    "test-app", "process-1", "session-1");
        }

        CompletionStage<Scene2dScenarioRunner.Lease> acquire(Duration timeout) {
            return scenarios.acquire(
                    new ScenarioRequest(ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready", 42L, Map.of("locale", "en"), "desktop",
                            Deadline.after(clock, timeout)),
                    "test-app", "process-1", "session-1");
        }

        NavigationRequest request(List<NavigationInput> inputs) {
            List<String> known = List.of("test-id:first", "test-id:second");
            List<NavigationStep> configured = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                NavigationInput navigationInput = inputs.get(index);
                configured.add(new NavigationStep(navigationInput,
                        index + 1L, index + 1L, index + 2L, index + 2L,
                        "test-id:first", "test-id:first", null));
            }
            return new NavigationRequest(1, configured, known, null, null, true, false,
                    8, 16, 65536, 65536, Duration.ofSeconds(2));
        }

        void completedFrame() {
            clock.advance(STEP);
            session.completedFrame(scenarios, navigation, clock.revision(), clock.frame());
            scheduler.drain();
        }

        void nextFrame() {
            completedFrame();
        }

        void drainFrames(int count) {
            for (int index = 0; index < count; index++) {
                completedFrame();
            }
        }

        long rootReads() {
            return stage.rootReads.get();
        }

        long frame() {
            return clock.frame();
        }

        @Override public void close() {
            navigation.close();
            scenarios.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }

        final class RoutingInput extends InputAdapter {
            final Map<Integer, Actor> routes = new HashMap<>();

            @Override public boolean keyDown(int keycode) {
                Actor target = routes.get(keycode);
                if (target != null || routes.containsKey(keycode)) {
                    dispatches.incrementAndGet();
                    stage.setKeyboardFocus(target);
                }
                return true;
            }
        }
    }

    /** Counts one Stage root traversal per built semantic snapshot. */
    private static final class CountingStage extends Stage {
        final AtomicLong rootReads = new AtomicLong();

        CountingStage() {
            super(new FitViewport(800, 600), new NoopBatch());
            getViewport().setScreenBounds(0, 0, 800, 600);
            getViewport().getCamera().position.set(400, 300, 0);
            getViewport().getCamera().update();
        }

        @Override public Group getRoot() {
            rootReads.incrementAndGet();
            return super.getRoot();
        }
    }
}
