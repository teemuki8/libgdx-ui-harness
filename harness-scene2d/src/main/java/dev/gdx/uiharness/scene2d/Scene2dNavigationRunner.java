package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.navigation.NavigationInput;
import dev.gdx.uiharness.core.navigation.NavigationPath;
import dev.gdx.uiharness.core.navigation.NavigationReason;
import dev.gdx.uiharness.core.navigation.NavigationRequest;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import dev.gdx.uiharness.core.navigation.NavigationStep;
import dev.gdx.uiharness.core.navigation.NavigationValidator;
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Traverses Scene2D focus using only application-configured input and completed frames. */
public final class Scene2dNavigationRunner implements AutoCloseable {
    private static final Duration INTERNAL_DISPATCH_TIMEOUT = Duration.ofMinutes(10);

    /** Immutable binding to the registered scenario that establishes traversal state. */
    public record Scenario(
            String scenarioId,
            long seed,
            Map<String, String> configuration,
            String profileId,
            String applicationId,
            String processId,
            String sessionId) {
        public Scenario {
            Objects.requireNonNull(scenarioId, "scenarioId");
            configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(processId, "processId");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    private final Scene2dScenarioRunner scenarios;
    private final Scene2dSession session;
    private final Scene2dInputDispatcher input;
    private final RenderThreadScheduler scheduler;
    private final MonotonicClock clock;
    private final Scene2dScenarioDeadlineScheduler deadlines;
    private final LongSupplier revision;
    private final LongSupplier frame;
    private final Scenario scenario;
    private final int maxPending;
    private final Object lifecycle = new Object();
    private final ArrayList<Run> active = new ArrayList<>();
    private boolean open = true;

    public Scene2dNavigationRunner(
            Scene2dScenarioRunner scenarios,
            Scene2dSession session,
            Scene2dInputDispatcher input,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            Scene2dScenarioDeadlineScheduler deadlines,
            LongSupplier revision,
            LongSupplier frame,
            Scenario scenario,
            int maxPending) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.session = Objects.requireNonNull(session, "session");
        this.input = Objects.requireNonNull(input, "input");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.frame = Objects.requireNonNull(frame, "frame");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        if (maxPending < 1) throw new IllegalArgumentException("maxPending must be positive");
        this.maxPending = maxPending;
    }

    /** Inspects a path after starting the bound, application-registered scenario. */
    public CompletionStage<NavigationResult> inspect(NavigationRequest request) {
        return start(request);
    }

    /** Resets through the same registered scenario and validates the resulting observations. */
    public CompletionStage<NavigationResult> validate(NavigationRequest request) {
        return start(request);
    }

    private CompletionStage<NavigationResult> start(NavigationRequest request) {
        Objects.requireNonNull(request, "request");
        Run run = new Run(request);
        synchronized (lifecycle) {
            if (!open) throw new IllegalStateException("navigation runner is closed");
            if (active.size() >= maxPending) throw new IllegalStateException("navigation pending bound exceeded");
            active.add(run);
        }
        run.startScenario();
        run.armDeadline();
        return run.result;
    }

    /** Supplies one evaluator-complete semantic frame captured on the render thread. */
    public void completedFrame(SemanticSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Run[] runs;
        synchronized (lifecycle) {
            runs = active.toArray(Run[]::new);
        }
        for (Run run : runs) {
            observe(run, scheduler.submit(() -> {
                run.observe(snapshot);
                return null;
            }, dispatchDeadline()));
        }
    }

    @Override public void close() {
        Run[] runs;
        synchronized (lifecycle) {
            if (!open) return;
            open = false;
            runs = active.toArray(Run[]::new);
        }
        for (Run run : runs) run.cancelForClose();
    }

    private Deadline dispatchDeadline() {
        return Deadline.after(clock, INTERNAL_DISPATCH_TIMEOUT);
    }

    private void observe(Run run, CompletionStage<?> submitted) {
        submitted.whenComplete((ignored, failure) -> {
            if (failure != null) run.fail(failure);
        });
    }

    private void finished(Run run) {
        synchronized (lifecycle) {
            active.remove(run);
        }
    }

    private final class Run {
        private final NavigationRequest request;
        private final List<NavigationInput> configuredInputs;
        private final ArrayList<NavigationStep> steps = new ArrayList<>();
        private final ResultFuture result = new ResultFuture(this);
        private final Deadline deadline;
        private Scene2dScenarioDeadlineScheduler.Cancellation deadlineCancellation;
        private CompletionStage<ScenarioResult> scenarioStage;
        private Observation before;
        private List<String> known = List.of();
        private int nextInput;
        private boolean ready;
        private boolean waitingForFrame;
        private boolean terminal;

        Run(NavigationRequest request) {
            this.request = request;
            configuredInputs = request.steps().stream().map(NavigationStep::input).toList();
            deadline = Deadline.after(clock, request.deadline());
        }

        void startScenario() {
            ScenarioRequest scenarioRequest = new ScenarioRequest(
                    ScenarioDefinitionVersion.SCHEMA_VERSION,
                    scenario.scenarioId(), scenario.seed(), scenario.configuration(),
                    scenario.profileId(), deadline);
            try {
                scenarioStage = scenarios.start(scenarioRequest, scenario.applicationId(),
                        scenario.processId(), scenario.sessionId());
            } catch (RuntimeException failure) {
                fail(failure);
                return;
            }
            scenarioStage.whenComplete((outcome, failure) -> {
                if (failure != null) {
                    fail(failure);
                } else if (outcome.failure().isPresent()) {
                    if (outcome.failure().orElseThrow() == ScenarioFailure.READINESS_DEADLINE) {
                        deadlineReached();
                    } else if (outcome.failure().orElseThrow() == ScenarioFailure.CANCELLED) {
                        cancelForClose();
                    } else {
                        fail(new IllegalStateException("scenario failed: " + outcome.failure().orElseThrow()));
                    }
                } else if (scheduler.isOwnerThread()) {
                    becomeReady();
                } else {
                    Scene2dNavigationRunner.this.observe(this, scheduler.submit(() -> {
                        becomeReady();
                        return null;
                    }, dispatchDeadline()));
                }
            });
        }

        void armDeadline() {
            Scene2dScenarioDeadlineScheduler.Cancellation scheduled =
                    deadlines.schedule(request.deadline(), this::deadlineReached);
            synchronized (this) {
                if (terminal) scheduled.cancel();
                else deadlineCancellation = scheduled;
            }
        }

        void becomeReady() {
            synchronized (this) {
                if (terminal || ready) return;
                ready = true;
            }
            SemanticSnapshot snapshot = session.snapshot(revision.getAsLong(), frame.getAsLong());
            before = observeState(snapshot, request.modalBoundaryId());
            known = focusables(snapshot);
            dispatchNext();
        }

        void observe(SemanticSnapshot snapshot) {
            synchronized (this) {
                if (terminal || !ready || !waitingForFrame) return;
                if (snapshot.frame() <= before.frame()) return;
                waitingForFrame = false;
            }
            Observation after = observeState(snapshot, request.modalBoundaryId());
            NavigationInput navigationInput = configuredInputs.get(nextInput - 1);
            steps.add(new NavigationStep(navigationInput, before.frame(), before.revision(),
                    after.frame(), after.revision(), before.focusIdentity(), after.focusIdentity(),
                    after.modalBoundaryId()));
            before = after;
            NavigationResult current = validateObserved(false);
            NavigationReason reason = current.path().reason();
            if (reason == NavigationReason.CYCLE || reason == NavigationReason.DEAD_END
                    || reason == NavigationReason.FOCUS_LOST || reason == NavigationReason.MODAL_ESCAPE) {
                complete(current);
            } else {
                dispatchNext();
            }
        }

        private void dispatchNext() {
            synchronized (this) {
                if (terminal) return;
            }
            if (deadline.isExpired()) {
                complete(deadlineResult());
                return;
            }
            if (nextInput >= request.maxSteps() && nextInput < configuredInputs.size()) {
                complete(resultWithReason(NavigationReason.TRUNCATED, true));
                return;
            }
            if (nextInput >= configuredInputs.size()) {
                complete(validateObserved(false));
                return;
            }
            NavigationInput navigationInput = configuredInputs.get(nextInput);
            if (navigationInput.isController() && !request.controllerSupported()) {
                complete(resultWithReason(NavigationReason.UNSUPPORTED_CONTROLLER_PATH, false));
                return;
            }
            final boolean accepted;
            try {
                accepted = input.dispatch(navigationInput);
            } catch (RuntimeException failure) {
                fail(failure);
                return;
            }
            if (!accepted) {
                complete(resultWithReason(NavigationReason.UNSUPPORTED_CONTROLLER_PATH, false));
                return;
            }
            nextInput++;
            synchronized (this) {
                if (!terminal) waitingForFrame = true;
            }
        }

        private NavigationResult validateObserved(boolean expired) {
            NavigationRequest observed = new NavigationRequest(1, steps, known,
                    request.modalBoundaryId(), request.controllerSupported(), expired,
                    request.maxSteps(), request.maxActors(), request.maxResultBytes(),
                    request.maxEvidenceBytes(), request.deadline());
            return new NavigationValidator().validate(observed);
        }

        private NavigationResult deadlineResult() {
            return resultWithReason(NavigationReason.DEADLINE, false);
        }

        private NavigationResult resultWithReason(NavigationReason reason, boolean truncated) {
            String defaultFocus = steps.isEmpty() && before != null
                    ? before.focusIdentity() : steps.isEmpty() ? null : steps.get(0).beforeIdentity();
            NavigationPath path = new NavigationPath(1, defaultFocus, steps, reason);
            return new NavigationResult(1, path, known, List.of(), truncated);
        }

        void deadlineReached() {
            Scene2dNavigationRunner.this.observe(this, scheduler.submit(() -> {
                if (!terminal && deadline.isExpired()) complete(deadlineResult());
                return null;
            }, dispatchDeadline()));
        }

        void cancelForClose() {
            CompletionStage<ScenarioResult> pending;
            synchronized (this) {
                if (terminal) return;
                terminal = true;
                pending = scenarioStage;
            }
            if (pending != null) pending.toCompletableFuture().cancel(false);
            cancelDeadline();
            result.cancelDirect();
            finished(this);
        }

        void fail(Throwable failure) {
            synchronized (this) {
                if (terminal) return;
                terminal = true;
            }
            cancelDeadline();
            result.completeExceptionally(failure);
            finished(this);
        }

        private void complete(NavigationResult value) {
            synchronized (this) {
                if (terminal) return;
                terminal = true;
            }
            cancelDeadline();
            result.complete(value);
            finished(this);
        }

        private void cancelDeadline() {
            Scene2dScenarioDeadlineScheduler.Cancellation cancellation;
            synchronized (this) {
                cancellation = deadlineCancellation;
                deadlineCancellation = null;
            }
            if (cancellation != null) cancellation.cancel();
        }
    }

    private static Observation observeState(SemanticSnapshot snapshot, String requestedModal) {
        Map<String, SemanticNode> byId = new HashMap<>();
        byId.putAll(snapshot.nodes());
        SemanticNode focused = snapshot.nodes().values().stream()
                .filter(node -> node.state().focused())
                .findFirst().orElse(null);
        String focusIdentity = focused == null ? null : identity(focused);
        String modal = null;
        for (SemanticNode node = focused; node != null; node = byId.get(node.parentId())) {
            if (Objects.equals(identity(node), requestedModal)) {
                modal = requestedModal;
                break;
            }
        }
        return new Observation(snapshot.frame(), snapshot.revision(), focusIdentity, modal);
    }

    private static List<String> focusables(SemanticSnapshot snapshot) {
        return snapshot.nodes().values().stream()
                .filter(node -> node.state().focusable() && node.state().visible())
                .map(Scene2dNavigationRunner::identity)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String identity(SemanticNode node) {
        if (node.testId() != null) return "test-id:" + node.testId();
        String name = node.accessibleName();
        if (name == null) name = node.actorName();
        if (name == null) name = node.text();
        if (name == null) name = "unnamed";
        return "role:" + node.role().name().toLowerCase(java.util.Locale.ROOT) + "/name:" + name;
    }

    private record Observation(
            long frame, long revision, String focusIdentity, String modalBoundaryId) {}

    private final class ResultFuture extends CompletableFuture<NavigationResult> {
        private final Run run;
        ResultFuture(Run run) { this.run = run; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (run) {
                if (run.terminal) return false;
            }
            run.cancelForClose();
            return true;
        }
        void cancelDirect() { super.cancel(false); }
    }

    /** Avoids coupling the scenario package's public schema constant into constructor call sites. */
    private static final class ScenarioDefinitionVersion {
        static final int SCHEMA_VERSION = 1;
        private ScenarioDefinitionVersion() {}
    }
}
