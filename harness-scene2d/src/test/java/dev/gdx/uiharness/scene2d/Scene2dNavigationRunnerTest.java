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
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
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

    private static final class Fixture implements AutoCloseable {
        final Duration step = Duration.ofMillis(16);
        final Stage stage = Scene2dTestSupport.stage();
        final ControlledStageClock clock = new ControlledStageClock(stage, step);
        final RenderThreadScheduler scheduler;
        final ManualDeadlines deadlines = new ManualDeadlines();
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
        Fixture(int maxPending) {
            scheduler = new RenderThreadScheduler(64);
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
            scenarios = new Scene2dScenarioRunner(registry, scheduler, clock, deadlines);
            runner = new Scene2dNavigationRunner(
                    scenarios, session, new Scene2dInputDispatcher(stage, input), scheduler, clock,
                    deadlines, clock::revision, clock::frame,
                    new Scene2dNavigationRunner.Scenario("navigation", 7, Map.of(), "desktop",
                            "app", "process", "session"), maxPending);
        }

        TextButton button(String id, String label, float x) {
            TextButton actor = new TextButton(label, WidgetStyles.textButton());
            actor.setBounds(x, 50, 160, 40);
            stage.addActor(actor);
            if (id != null) session.semantics().setTestId(actor, id);
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
            for (int i = 0; i < frames && !result.toCompletableFuture().isDone(); i++) nextFrame();
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
                if (keycode == Keys.SHIFT_LEFT || keycode == Keys.SHIFT_RIGHT) shift = true;
                Actor target = keycode == Keys.TAB && shift ? shiftTab : routes.get(keycode);
                if (target != null || routes.containsKey(keycode) || keycode == Keys.TAB && shiftTab != null) {
                    dispatches.incrementAndGet();
                    stage.setKeyboardFocus(target);
                }
                return true;
            }
            @Override public boolean keyUp(int keycode) {
                if (keycode == Keys.SHIFT_LEFT || keycode == Keys.SHIFT_RIGHT) shift = false;
                return true;
            }
        }
    }

    private static final class ManualDeadlines implements Scene2dScenarioDeadlineScheduler {
        private final List<Entry> entries = new ArrayList<>();
        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            Entry entry = new Entry(delay, signal);
            entries.add(entry);
            return () -> entry.cancelled = true;
        }
        void expire() {
            for (Entry entry : List.copyOf(entries)) if (!entry.cancelled) entry.signal.run();
        }
        private static final class Entry {
            final Duration delay;
            final Runnable signal;
            boolean cancelled;
            Entry(Duration delay, Runnable signal) { this.delay = delay; this.signal = signal; }
        }
    }
}
