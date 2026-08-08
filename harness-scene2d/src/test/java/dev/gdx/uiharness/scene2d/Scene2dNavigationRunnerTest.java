package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.navigation.NavigationInput;
import dev.gdx.uiharness.core.navigation.NavigationReason;
import dev.gdx.uiharness.core.navigation.NavigationRequest;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import dev.gdx.uiharness.core.navigation.NavigationStep;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class Scene2dNavigationRunnerTest {
    @Test void tabAndShiftTabTraverseThroughConfiguredInputAndCorrelatedFrames() {
        try (Fixture f = new Fixture()) {
            f.route(Keys.TAB, f.second);
            CompletionStage<NavigationResult> run = f.inspect(f.request(List.of(NavigationInput.TAB)));
            f.readyFrame();
            f.nextFrame();

            NavigationResult result = run.toCompletableFuture().join();
            assertEquals("test-id:first", result.path().defaultFocusIdentity());
            assertEquals("test-id:second", result.path().steps().get(0).afterIdentity());
            assertTrue(result.path().steps().get(0).afterFrame() > result.path().steps().get(0).beforeFrame());
            assertEquals(1, f.dispatches.get(), "the configured InputProcessor must receive the input");
        }
        try (Fixture f = new Fixture()) {
            f.defaultFocus = f.second;
            f.routeShiftTab(f.first);
            CompletionStage<NavigationResult> run = f.inspect(f.request(List.of(NavigationInput.SHIFT_TAB)));
            f.readyFrame();
            f.nextFrame();
            assertEquals("test-id:first", run.toCompletableFuture().join().path().steps().get(0).afterIdentity());
            assertEquals(1, f.dispatches.get());
        }
    }

    @Test void directionalGridCycleDeadEndAndFocusLossAreDistinct() {
        try (Fixture f = new Fixture()) {
            f.route(Keys.RIGHT, f.second);
            f.route(Keys.DOWN, f.first);
            NavigationResult result = f.complete(f.inspect(f.request(List.of(
                    NavigationInput.RIGHT, NavigationInput.DOWN))), 3);
            assertEquals(NavigationReason.CYCLE, result.path().reason());
            assertEquals(2, f.dispatches.get());
        }
        try (Fixture f = new Fixture()) {
            f.route(Keys.RIGHT, f.first);
            NavigationResult result = f.complete(f.inspect(f.request(List.of(NavigationInput.RIGHT))), 2);
            assertEquals(NavigationReason.DEAD_END, result.path().reason());
        }
        try (Fixture f = new Fixture()) {
            f.route(Keys.RIGHT, null);
            NavigationResult result = f.complete(f.inspect(f.request(List.of(NavigationInput.RIGHT))), 2);
            assertEquals(NavigationReason.FOCUS_LOST, result.path().reason());
        }
    }

    @Test void modalContainmentAndEscapeBackUseRealInput() {
        try (Fixture f = new Fixture()) {
            f.modal = "test-id:dialog";
            f.route(Keys.ESCAPE, f.outside);
            NavigationResult escaped = f.complete(f.inspect(f.request(List.of(NavigationInput.ESCAPE))), 2);
            assertEquals(NavigationReason.MODAL_ESCAPE, escaped.path().reason());
            assertEquals(1, f.dispatches.get());
        }
        try (Fixture f = new Fixture()) {
            f.route(Keys.BACK, f.second);
            NavigationResult back = f.complete(f.inspect(f.request(List.of(NavigationInput.BACK))), 2);
            assertEquals("test-id:second", back.path().steps().get(0).afterIdentity());
            assertEquals(1, f.dispatches.get());
        }
    }

    @Test void unsupportedControllerIsReportedWithoutDispatch() {
        try (Fixture f = new Fixture()) {
            NavigationResult result = f.complete(f.inspect(
                    f.request(List.of(NavigationInput.CONTROLLER_RIGHT), false, 8, Duration.ofSeconds(1))), 1);
            assertEquals(NavigationReason.UNSUPPORTED_CONTROLLER_PATH, result.path().reason());
            assertEquals(0, f.dispatches.get());
        }
    }

    @Test void validationResetsScenarioAndIsRepeatable() {
        try (Fixture f = new Fixture()) {
            f.route(Keys.TAB, f.second);
            NavigationRequest request = f.request(List.of(NavigationInput.TAB));
            NavigationResult first = f.complete(f.validate(request), 2);
            f.stage.setKeyboardFocus(f.outside);
            NavigationResult second = f.complete(f.validate(request), 2);
            assertEquals(first.path().defaultFocusIdentity(), second.path().defaultFocusIdentity());
            assertEquals(first.path().reason(), second.path().reason());
            assertEquals(
                    first.path().steps().stream()
                            .map(step -> List.of(step.input(), step.beforeIdentity(), step.afterIdentity()))
                            .toList(),
                    second.path().steps().stream()
                            .map(step -> List.of(step.input(), step.beforeIdentity(), step.afterIdentity()))
                            .toList());
            assertEquals(2, f.resets.get());
        }
    }

    @Test void exactDeadlineNoFrameCancellationAndCloseTerminateWithoutDispatch() {
        try (Fixture f = new Fixture()) {
            CompletionStage<NavigationResult> run = f.inspect(
                    f.request(List.of(NavigationInput.TAB), true, 8, Duration.ofMillis(16)));
            f.scheduler.drain();
            f.clock.advance(Duration.ofMillis(16));
            f.deadlines.expire();
            f.scheduler.drain();
            assertEquals(NavigationReason.DEADLINE, run.toCompletableFuture().join().path().reason());
            assertEquals(0, f.dispatches.get());
        }
        try (Fixture f = new Fixture()) {
            CompletionStage<NavigationResult> run = f.inspect(f.request(List.of(NavigationInput.TAB)));
            assertTrue(run.toCompletableFuture().cancel(false));
            f.scheduler.drain();
            assertTrue(run.toCompletableFuture().isCancelled());
            assertEquals(0, f.dispatches.get());
        }
        Fixture f = new Fixture();
        CompletionStage<NavigationResult> run = f.inspect(f.request(List.of(NavigationInput.TAB)));
        f.runner.close();
        f.scheduler.drain();
        assertTrue(run.toCompletableFuture().isCancelled());
        assertEquals(0, f.dispatches.get());
        f.close();
    }

    @Test void maxStepsAndPendingSchedulerBoundsAreEnforced() {
        try (Fixture f = new Fixture(1)) {
            f.route(Keys.TAB, f.second);
            NavigationResult result = f.complete(f.inspect(f.request(
                    List.of(NavigationInput.TAB, NavigationInput.TAB), true, 1, Duration.ofSeconds(1))), 2);
            assertEquals(NavigationReason.TRUNCATED, result.path().reason());
            assertEquals(1, result.path().steps().size());
            assertEquals(1, f.dispatches.get());
        }
        try (Fixture f = new Fixture(1)) {
            CompletionStage<NavigationResult> first = f.inspect(f.request(List.of(NavigationInput.TAB)));
            assertThrows(IllegalStateException.class,
                    () -> f.inspect(f.request(List.of(NavigationInput.TAB))));
            first.toCompletableFuture().cancel(false);
            f.scheduler.drain();
        }
    }

    @Test void rejectingDeadlineScheduleReleasesPendingSlotAndScenarioLease() {
        try (Fixture f = new Fixture(1, new ThrowingManualDeadlines(1))) {
            f.route(Keys.TAB, f.second);

            // The scenario acquisition is submitted first; the navigation armDeadline then
            // throws: the original scheduling failure must propagate synchronously.
            assertThrows(IllegalStateException.class,
                    () -> f.inspect(f.request(List.of(NavigationInput.TAB))));

            // The pending scenario acquisition was cancelled terminally: draining cleans the
            // scenario exactly once and never hands a lease to admitted traversal work.
            f.scheduler.drain();
            assertEquals(1, f.cleanups.get(),
                    "the cancelled scenario run must clean exactly once");
            assertEquals(0, f.dispatches.get(),
                    "no traversal input may be dispatched for the failed run");

            // The pending slot was released instead of retained: a new run is admitted,
            // traverses, and cleans its own scenario lease exactly once.
            CompletionStage<NavigationResult> run = f.inspect(f.request(List.of(NavigationInput.TAB)));
            f.readyFrame();
            f.nextFrame();
            NavigationResult result = run.toCompletableFuture().join();
            assertEquals("test-id:second", result.path().steps().get(0).afterIdentity());
            assertEquals(1, f.dispatches.get());
            assertEquals(2, f.cleanups.get(),
                    "the second traversal cleans its scenario lease exactly once");
        }
    }

    @Test void scenarioUiIsLeasedThroughTraversalAndCleanedOnceOnEveryTerminal() {
        try (Fixture f = new Fixture()) {
            f.route(Keys.TAB, f.second);
            CompletionStage<NavigationResult> run = f.inspect(f.request(List.of(NavigationInput.TAB)));
            f.readyFrame();
            assertEquals(0, f.cleanups.get(), "READY must not clean before real input traversal");
            f.nextFrame();
            assertEquals("test-id:second", run.toCompletableFuture().join()
                    .path().steps().get(0).afterIdentity());
            assertEquals(1, f.cleanups.get());
            run.toCompletableFuture().cancel(false);
            assertEquals(1, f.cleanups.get());
        }
    }

    @Test void missingInitialFocusIsExplicitOnlyWhenInputEstablishesIt() {
        try (Fixture f = new Fixture()) {
            f.defaultFocus = null;
            f.route(Keys.TAB, f.second);
            NavigationResult established =
                    f.complete(f.inspect(f.request(List.of(NavigationInput.TAB))), 2);
            assertEquals(null, established.path().defaultFocusIdentity());
            assertEquals("state:no-focus", established.path().steps().get(0).beforeIdentity());
            assertEquals("test-id:second", established.path().steps().get(0).afterIdentity());
        }
        try (Fixture f = new Fixture()) {
            f.defaultFocus = null;
            f.route(Keys.TAB, null);
            NavigationResult lost = f.complete(f.inspect(f.request(List.of(NavigationInput.TAB))), 2);
            assertEquals(NavigationReason.FOCUS_LOST, lost.path().reason());
            assertTrue(lost.path().steps().isEmpty(), "never construct a step with null identity");
        }
    }

    @Test void semanticStructuralIdentityDistinguishesDuplicatesSurvivesReplacementAndCaptureIsBounded() {
        try (Fixture f = new Fixture()) {
            f.session.semantics().clear(f.first);
            f.session.semantics().clear(f.second);
            f.first.setText("Same");
            f.second.setText("Same");
            f.route(Keys.TAB, f.second);
            NavigationResult firstRun =
                    f.complete(f.inspect(f.request(List.of(NavigationInput.TAB))), 2);
            String firstIdentity = firstRun.path().defaultFocusIdentity();
            String secondIdentity = firstRun.path().steps().get(0).afterIdentity();
            assertFalse(firstIdentity.equals(secondIdentity), "duplicate labels need sibling ordinals");

            TextButton replacement = f.button(null, "Same", 20);
            f.first.remove();
            replacement.setZIndex(0);
            f.defaultFocus = replacement;
            NavigationResult replacementRun =
                    f.complete(f.inspect(f.request(List.of())), 1);
            assertEquals(firstIdentity, replacementRun.path().defaultFocusIdentity());
        }
        try (Fixture f = new Fixture()) {
            NavigationResult bounded = f.complete(
                    f.inspect(f.request(List.of(), true, 8, 2, Duration.ofSeconds(1))), 1);
            assertEquals(NavigationReason.TRUNCATED, bounded.path().reason());
            assertTrue(bounded.truncated());
            assertEquals(2, bounded.knownFocusables().size());
        }
    }

    /**
     * Task 6 (#22): the navigation runner's supplier overload is gated on active runs — idle
     * decisions invoke no supplier, an active traversal consumes exactly one supplier frame
     * per decision, and post-completion decisions invoke no supplier. Fails to compile until
     * the runner gains {@code completedFrame(Supplier<SemanticSnapshot>, long, long)}.
     */
    @Test void navigationSupplierFramesAreGatedOnActiveRuns() {
        try (Fixture f = new Fixture()) {
            AtomicInteger supplierCalls = new AtomicInteger();
            f.clock.advance(f.step);
            assertFalse(f.runner.completedFrame(() -> {
                supplierCalls.incrementAndGet();
                return f.session.snapshot(f.clock.revision(), f.clock.frame());
            }, f.clock.revision(), f.clock.frame()));
            assertEquals(0, supplierCalls.get(),
                    "idle navigation runs must not build snapshots");

            f.route(Keys.TAB, f.second);
            CompletionStage<NavigationResult> run = f.inspect(
                    f.request(List.of(NavigationInput.TAB)));
            f.readyFrame(); // scenario acquire observes the first completed frame

            f.clock.advance(f.step);
            long revision = f.clock.revision();
            long frame = f.clock.frame();
            assertTrue(f.runner.completedFrame(() -> {
                supplierCalls.incrementAndGet();
                return f.session.snapshot(revision, frame);
            }, revision, frame),
                    "an active navigation run must consume the supplier frame");
            f.scheduler.drain();
            assertEquals(1, supplierCalls.get(),
                    "one supplier frame must serve the active traversal exactly once");
            NavigationResult result = run.toCompletableFuture().join();
            assertEquals("test-id:second", result.path().steps().get(0).afterIdentity());

            int before = supplierCalls.get();
            f.clock.advance(f.step);
            assertFalse(f.runner.completedFrame(() -> {
                supplierCalls.incrementAndGet();
                return f.session.snapshot(f.clock.revision(), f.clock.frame());
            }, f.clock.revision(), f.clock.frame()),
                    "after the last run finishes, completed frames build no snapshot");
            assertEquals(before, supplierCalls.get(),
                    "post-terminal navigation frames must invoke no supplier");
        }
    }

    /**
     * Task 6 (#22): the reservation-token barrier on the navigation runner. The reserved
     * snapshot is captured on the owning thread BEFORE the reservation (the supplier itself
     * only blocks and must never touch the session off-owner). A closer calls close() while
     * the supplier is blocked; the terminal transition must wait, and once the delivery
     * drains the navigation step carries the reserved frame — proving the reserved snapshot
     * was incorporated rather than merely reported consumed.
     */
    @Test void lastTerminalReservationWinsDespiteConcurrentTerminal() throws Exception {
        try (Fixture f = new Fixture();
                ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            f.route(Keys.TAB, f.second);
            CompletionStage<NavigationResult> run = f.inspect(
                    f.request(List.of(NavigationInput.TAB)));
            f.readyFrame(); // scenario READY; TAB dispatched; the run waits for its frame
            assertFalse(run.toCompletableFuture().isDone());

            f.clock.advance(f.step);
            long revision = f.clock.revision();
            long frame = f.clock.frame();
            SemanticSnapshot reserved = f.session.snapshot(revision, frame);

            CountDownLatch supplierEntered = new CountDownLatch(1);
            CountDownLatch releaseSupplier = new CountDownLatch(1);
            AtomicInteger supplierCalls = new AtomicInteger();
            CompletableFuture<Boolean> reservation = new CompletableFuture<>();
            workers.submit(() -> {
                try {
                    reservation.complete(f.runner.completedFrame(() -> {
                        supplierCalls.incrementAndGet();
                        supplierEntered.countDown();
                        try {
                            releaseSupplier.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("supplier interrupted", interrupted);
                        }
                        return reserved;
                    }, revision, frame));
                } catch (Throwable failure) {
                    reservation.completeExceptionally(failure);
                }
            });
            CompletableFuture<Void> closeDone = new CompletableFuture<>();
            try {
                assertTrue(supplierEntered.await(5, TimeUnit.SECONDS),
                        "the reservation must enter the supplier");
                workers.submit(() -> {
                    f.runner.close();
                    closeDone.complete(null);
                });
                assertFalse(closeDone.isDone(),
                        "close must wait while a navigation reservation is in flight");
            } finally {
                releaseSupplier.countDown();
            }
            assertTrue(reservation.get(5, TimeUnit.SECONDS),
                    "the reserved navigation frame must be delivered");
            assertEquals(1, supplierCalls.get());
            f.scheduler.drain(); // the reserved frame reaches the waiting navigation step
            assertTrue(closeDone.get(5, TimeUnit.SECONDS) == null,
                    "close must complete once the reservation has delivered");
            NavigationResult result = run.toCompletableFuture().join();
            assertEquals(1, result.path().steps().size());
            assertEquals(frame, result.path().steps().get(0).afterFrame(),
                    "the reserved snapshot must be incorporated into the navigation step");
            assertEquals(revision, result.path().steps().get(0).afterRevision(),
                    "the reserved snapshot must be incorporated into the navigation step");
        }
    }

    /**
     * Task 6 (#22): a navigation supplier failure releases the terminal barrier with defined
     * failure semantics — the reservation reports the failure, close() proceeds, and the run
     * terminalizes without observing any frame.
     */
    @Test void failingNavigationSupplierReleasesTheTerminalBarrier() throws Exception {
        try (Fixture f = new Fixture();
                ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            f.route(Keys.TAB, f.second);
            CompletionStage<NavigationResult> run = f.inspect(
                    f.request(List.of(NavigationInput.TAB)));
            f.readyFrame();
            assertFalse(run.toCompletableFuture().isDone());

            CountDownLatch supplierEntered = new CountDownLatch(1);
            CountDownLatch releaseSupplier = new CountDownLatch(1);
            AtomicInteger supplierCalls = new AtomicInteger();
            CompletableFuture<Boolean> reservation = new CompletableFuture<>();
            workers.submit(() -> {
                try {
                    reservation.complete(f.runner.completedFrame(() -> {
                        supplierCalls.incrementAndGet();
                        supplierEntered.countDown();
                        try {
                            releaseSupplier.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("supplier interrupted", interrupted);
                        }
                        throw new IllegalStateException("snapshot build failed");
                    }, f.clock.revision(), f.clock.frame()));
                } catch (Throwable failure) {
                    reservation.completeExceptionally(failure);
                }
            });
            CompletableFuture<Void> closeDone = new CompletableFuture<>();
            try {
                assertTrue(supplierEntered.await(5, TimeUnit.SECONDS),
                        "the reservation must enter the supplier");
                workers.submit(() -> {
                    f.runner.close();
                    closeDone.complete(null);
                });
                assertFalse(closeDone.isDone(),
                        "close must wait while the reservation is in flight");
            } finally {
                releaseSupplier.countDown();
            }
            try {
                reservation.get(5, TimeUnit.SECONDS);
                throw new AssertionError("a failing supplier must fail the reservation");
            } catch (ExecutionException expected) {
                assertEquals("snapshot build failed", expected.getCause().getMessage());
            }
            assertEquals(1, supplierCalls.get());
            assertTrue(closeDone.get(5, TimeUnit.SECONDS) == null,
                    "a failed reservation must release the navigation terminal barrier");
            f.scheduler.drain();
            assertTrue(run.toCompletableFuture().isCancelled(),
                    "the navigation run must terminalize once the barrier releases");
            assertEquals(1, f.cleanups.get(),
                    "the scenario lease cleans exactly once after the terminal");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Duration step = Duration.ofMillis(16);
        final Stage stage = Scene2dTestSupport.stage();
        final ControlledStageClock clock = new ControlledStageClock(stage, step);
        final RenderThreadScheduler scheduler;
        final ManualDeadlines deadlines;
        final Scene2dSession session = new Scene2dSession(stage);
        final ScenarioRegistry registry = new ScenarioRegistry();
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicInteger resets = new AtomicInteger();
        final AtomicInteger cleanups = new AtomicInteger();
        final TextButton first = button("first", "First", 20);
        final TextButton second = button("second", "Second", 220);
        final TextButton outside = button("outside", "Outside", 420);
        Actor defaultFocus = first;
        String modal;
        final RoutingInput input = new RoutingInput();
        final Scene2dScenarioRunner scenarios;
        final Scene2dNavigationRunner runner;

        Fixture() { this(8); }
        Fixture(int maxPending) { this(maxPending, new ManualDeadlines()); }
        Fixture(int maxPending, ManualDeadlines deadlines) {
            scheduler = new RenderThreadScheduler(64);
            this.deadlines = deadlines;
            registry.register(new ScenarioDefinition(1, "navigation", "1", "app", List.of("desktop"),
                    1, Duration.ofMinutes(1)), new ScenarioLifecycle() {
                @Override public void setup(ScenarioRequest request) {}
                @Override public void reset(ScenarioRequest request) {
                    resets.incrementAndGet();
                    stage.setKeyboardFocus(defaultFocus);
                }
                @Override public boolean ready(ScenarioRequest request) { return true; }
                @Override public String startStateIdentity(ScenarioRequest request, SemanticSnapshot snapshot) {
                    return "ready";
                }
                @Override public void cleanup(ScenarioRequest request) { cleanups.incrementAndGet(); }
            });
            scenarios = Scene2dScenarioRunner.withDeadlineScheduler(registry, scheduler, clock, deadlines);
            runner = Scene2dNavigationRunner.withDeadlineScheduler(
                    scenarios, session, new Scene2dInputDispatcher(stage, input), scheduler, clock,
                    deadlines, clock::revision, clock::frame,
                    new Scene2dNavigationRunner.Scenario("navigation", 7, Map.of(), "desktop",
                            "app", "process", "session"), maxPending);
        }

        TextButton button(String id, String label, float x) {
            TextButton actor = new TextButton(label, WidgetStyles.textButton());
            actor.setBounds(x, 50, 160, 40);
            stage.addActor(actor);
            if (id != null) {
                session.semantics().setTestId(actor, id);
            }
            return actor;
        }

        void route(int key, Actor target) { input.routes.put(key, target); }
        void routeShiftTab(Actor target) { input.shiftTab = target; }

        NavigationRequest request(List<NavigationInput> inputs) {
            return request(inputs, true, 8, 16, Duration.ofSeconds(1));
        }

        NavigationRequest request(
                List<NavigationInput> inputs,
                boolean controller,
                int maxSteps,
                Duration deadline) {
            return request(inputs, controller, maxSteps, 16, deadline);
        }

        NavigationRequest request(
                List<NavigationInput> inputs,
                boolean controller,
                int maxSteps,
                int maxActors,
                Duration deadline) {
            List<String> all = List.of("test-id:first", "test-id:second", "test-id:outside");
            List<String> known = all.subList(0, Math.min(maxActors, all.size()));
            List<NavigationStep> configured = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                NavigationInput input = inputs.get(index);
                configured.add(new NavigationStep(input, index + 1L, index + 1L,
                        index + 2L, index + 2L,
                        "test-id:first", "test-id:first", modal));
            }
            return new NavigationRequest(1, configured, known, null, modal, controller, false,
                    maxSteps, maxActors, 65536, 65536, deadline);
        }

        CompletionStage<NavigationResult> inspect(NavigationRequest request) {
            return runner.inspect(request);
        }

        CompletionStage<NavigationResult> validate(NavigationRequest request) {
            return runner.validate(request);
        }

        void readyFrame() { nextFrame(); }
        void nextFrame() {
            clock.advance(step);
            SemanticSnapshot snapshot = session.snapshot(clock.revision(), clock.frame());
            scenarios.completedFrame(snapshot);
            runner.completedFrame(snapshot);
            scheduler.drain();
        }

        NavigationResult complete(CompletionStage<NavigationResult> result, int frames) {
            for (int i = 0; i < frames && !result.toCompletableFuture().isDone(); i++) {
                nextFrame();
            }
            return result.toCompletableFuture().join();
        }

        @Override public void close() {
            runner.close();
            scenarios.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }

        final class RoutingInput extends InputAdapter {
            final Map<Integer, Actor> routes = new java.util.HashMap<>();
            boolean shift;
            Actor shiftTab;
            @Override public boolean keyDown(int keycode) {
                if (keycode == Keys.SHIFT_LEFT || keycode == Keys.SHIFT_RIGHT) {
                    shift = true;
                }
                Actor target = keycode == Keys.TAB && shift ? shiftTab : routes.get(keycode);
                if (target != null || routes.containsKey(keycode) || keycode == Keys.TAB && shiftTab != null) {
                    dispatches.incrementAndGet();
                    stage.setKeyboardFocus(target);
                }
                return true;
            }
            @Override public boolean keyUp(int keycode) {
                if (keycode == Keys.SHIFT_LEFT || keycode == Keys.SHIFT_RIGHT) {
                    shift = false;
                }
                return true;
            }
        }
    }

    private static class ManualDeadlines implements DeadlineScheduler {
        private final List<Entry> entries = new ArrayList<>();
        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            Entry entry = new Entry(delay, signal);
            entries.add(entry);
            return () -> entry.cancelled = true;
        }
        void expire() {
            for (Entry entry : List.copyOf(entries)) {
                if (!entry.cancelled) {
                    entry.signal.run();
                }
            }
        }
        private static final class Entry {
            final Duration delay;
            final Runnable signal;
            boolean cancelled;
            Entry(Duration delay, Runnable signal) {
                this.delay = delay;
                this.signal = signal;
            }
        }
    }

    /** Rejects one {@link #schedule} call with a synchronous failure, then behaves normally. */
    private static final class ThrowingManualDeadlines extends ManualDeadlines {
        private final int throwOnCall;
        private int calls;

        ThrowingManualDeadlines(int throwOnCall) {
            this.throwOnCall = throwOnCall;
        }

        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            if (calls++ == throwOnCall) {
                throw new IllegalStateException("deadline scheduler rejected");
            }
            return super.schedule(delay, signal);
        }
    }
}
