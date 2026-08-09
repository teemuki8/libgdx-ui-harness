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
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
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
import java.util.function.Supplier;

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
    private final DeadlineScheduler deadlines;
    private final LongSupplier revision;
    private final LongSupplier frame;
    private final Scenario scenario;
    private final int maxPending;
    private final Object lifecycle = new Object();
    private final ArrayList<Run> active = new ArrayList<>();
    private boolean open = true;

    /**
     * Retained released constructor: adapts the legacy scene2d deadline scheduler to the core
     * {@link DeadlineScheduler} contract without changing scheduling semantics. This is the only
     * constructor taking a functional scheduler, so released lambda call sites stay unambiguous.
     */
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
        this(scenarios, session, input, scheduler, clock, adapt(deadlines),
                revision, frame, scenario, maxPending);
    }

    /** Creates a runner driven by the core deadline scheduler contract. */
    public static Scene2dNavigationRunner withDeadlineScheduler(
            Scene2dScenarioRunner scenarios,
            Scene2dSession session,
            Scene2dInputDispatcher input,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            DeadlineScheduler deadlines,
            LongSupplier revision,
            LongSupplier frame,
            Scenario scenario,
            int maxPending) {
        return new Scene2dNavigationRunner(scenarios, session, input, scheduler, clock, deadlines,
                revision, frame, scenario, maxPending);
    }

    private Scene2dNavigationRunner(
            Scene2dScenarioRunner scenarios,
            Scene2dSession session,
            Scene2dInputDispatcher input,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            DeadlineScheduler deadlines,
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
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
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
            if (!open) {
                throw new IllegalStateException("navigation runner is closed");
            }
            if (active.size() >= maxPending) {
                throw new IllegalStateException("navigation pending bound exceeded");
            }
            active.add(run);
        }
        try {
            run.startScenario();
            run.armDeadline();
        } catch (RuntimeException failure) {
            // A scheduling call threw after the run was admitted: roll the pending run back
            // terminally (cancel the scenario acquisition so a subsequently READY lease is
            // released instead of leaked, free the active slot, close the result) and
            // propagate the original failure to the caller.
            run.schedulingFailed(failure);
            throw failure;
        }
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

    /**
     * Atomically reserves this completed frame for every run active at the call: the recipient
     * snapshot and a per-run reservation counter are taken under the lifecycle lock, the snapshot
     * supplier runs OUTSIDE the lock (at most once per call), and every terminal transition of a
     * reserved recipient waits (releasing the lifecycle monitor) until the reservation has
     * delivered or failed — a terminal cannot invalidate an already-reserved frame, and a run
     * starting after the reservation observes the next frame.
     *
     * @return true when at least one active run consumed the frame
     */
    public boolean completedFrame(
            Supplier<SemanticSnapshot> snapshots, long revision, long frame) {
        Objects.requireNonNull(snapshots, "snapshots");
        Run[] runs;
        synchronized (lifecycle) {
            if (active.isEmpty()) {
                return false;
            }
            runs = active.toArray(Run[]::new);
            for (Run run : runs) {
                run.pendingFrameDeliveries++;
            }
        }
        SemanticSnapshot snapshot;
        try {
            snapshot = snapshots.get();
        } catch (RuntimeException failure) {
            // The supplier failed before any delivery was enqueued: release exactly ONE
            // reservation per recipient. Reservations made by other concurrent completedFrame
            // calls, and deliveries already enqueued by them, are untouched; the deferred
            // terminal (if any) applies only when the last reservation drains. The original
            // failure propagates.
            for (Run run : runs) {
                releaseOneReservation(run);
            }
            throw failure;
        }
        for (int index = 0; index < runs.length; index++) {
            try {
                deliverFrame(runs[index], snapshot);
            } catch (RuntimeException failure) {
                // The enqueue for runs[index] failed: recipients before it were already
                // enqueued and keep their reservations until their delivery callbacks release
                // them; this recipient and the ones after were reserved but never delivered,
                // so release exactly ONE reservation for each. The original failure propagates.
                for (int i = index; i < runs.length; i++) {
                    releaseOneReservation(runs[i]);
                }
                throw failure;
            }
        }
        return true;
    }

    /** Package-private test seam: runs before each frame delivery enqueue; a throwing probe simulates an enqueue rejection. */
    Runnable frameEnqueueProbe = () -> {};
    /** Package-private test seam: pauses a deferred terminal transition after take, before commit. */
    Runnable terminalApplyProbe = () -> {};

    /**
     * Enqueues one reserved frame delivery. The submission future completes exactly once — the
     * observe ran, the queue rejected it, or the dispatch deadline expired — and its completion
     * releases the reservation and applies any deferred terminal transition.
     */
    private void deliverFrame(Run run, SemanticSnapshot snapshot) {
        frameEnqueueProbe.run();
        CompletionStage<?> submission = scheduler.submit(() -> {
            run.observe(snapshot);
            return null;
        }, dispatchDeadline());
        submission.whenComplete((ignored, failure) ->
                deliveryCompleted(run, failure));
    }

    /**
     * Runs when a reserved frame delivery completes (or fails): releases the run's reservation
     * and, once every in-flight reservation has drained, applies the first-wins deferred terminal
     * transition. A failed delivery then reports the failure, which no-ops when the deferred
     * transition already terminalized the run.
     */
    private void deliveryCompleted(Run run, Throwable failure) {
        releaseOneReservation(run);
        if (failure != null) {
            run.fail(failure);
        }
    }

    /**
     * Releases exactly one in-flight frame reservation for the recipient. When the last
     * reservation drains, the first-wins deferred terminal transition is applied outside the
     * lifecycle lock (claimed through execution, so a later terminal request cannot overtake).
     */
    private void releaseOneReservation(Run run) {
        boolean applyDeferred;
        synchronized (lifecycle) {
            if (run.pendingFrameDeliveries > 0) {
                run.pendingFrameDeliveries--;
            }
            applyDeferred = run.pendingFrameDeliveries == 0;
        }
        if (applyDeferred) {
            run.applyDeferredTerminal();
        }
    }

    @Override public void close() {
        Run[] runs;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            runs = active.toArray(Run[]::new);
        }
        for (Run run : runs) {
            run.cancelForClose();
        }
    }

    private Deadline dispatchDeadline() {
        return Deadline.after(clock, INTERNAL_DISPATCH_TIMEOUT);
    }

    private static DeadlineScheduler adapt(Scene2dScenarioDeadlineScheduler legacy) {
        Objects.requireNonNull(legacy, "deadlines");
        return (delay, signal) -> {
            Scene2dScenarioDeadlineScheduler.Cancellation cancellation = legacy.schedule(delay, signal);
            return cancellation::cancel;
        };
    }

    private void observe(Run run, CompletionStage<?> submitted) {
        submitted.whenComplete((ignored, failure) -> {
            if (failure != null) {
                run.fail(failure);
            }
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
        private DeadlineScheduler.Cancellation deadlineCancellation;
        private CompletionStage<Scene2dScenarioRunner.Lease> scenarioStage;
        private Scene2dScenarioRunner.Lease scenarioLease;
        private Observation before;
        private List<String> known = List.of();
        private int nextInput;
        private boolean ready;
        private boolean waitingForFrame;
        private boolean terminal;
        /** In-flight reserved frame deliveries awaiting completion, guarded by the lifecycle monitor. */
        private int pendingFrameDeliveries;
        /** First-wins deferred terminal transition, guarded by the lifecycle monitor; stays non-null while claimed/applying. */
        private Runnable deferredTerminal;
        /** Exclusive take marker for the deferred transition, guarded by the lifecycle monitor. */
        private boolean deferredApplying;

        /**
         * Reserves this terminal transition first-wins under the lifecycle monitor. While frame
         * deliveries are in flight the transition is deferred: the run keeps observing reserved
         * frames, and the last delivery completion (or a supplier/enqueue failure) applies the
         * transition. Never blocks — the render thread must stay free to drain the queued
         * deliveries, so a terminal request on any thread returns immediately.
         *
         * @return true when this caller's transition was reserved; false when the run already
         *         terminalized or another terminal transition won the reservation first
         */
        private boolean reserveTerminal(Runnable transition) {
            boolean applyNow;
            synchronized (lifecycle) {
                if (deferredTerminal != null) {
                    return false;
                }
                deferredTerminal = transition;
                applyNow = pendingFrameDeliveries == 0;
            }
            if (applyNow) {
                applyDeferredTerminal();
            }
            return true;
        }

        /**
         * Takes and applies the first-wins deferred terminal transition, keeping it claimed
         * (non-null) through execution so a concurrent terminal request is rejected first-wins
         * and cannot overtake the outcome. The transition is cleared in a finally only when it
         * is still the same intent after the transition returns.
         */
        private void applyDeferredTerminal() {
            Runnable transition;
            synchronized (lifecycle) {
                if (deferredApplying || deferredTerminal == null) {
                    return;
                }
                deferredApplying = true;
                transition = deferredTerminal;
            }
            // Test seam: the transition stays claimed (non-null) while paused here.
            Scene2dNavigationRunner.this.terminalApplyProbe.run();
            try {
                transition.run();
            } finally {
                synchronized (lifecycle) {
                    deferredApplying = false;
                    if (deferredTerminal == transition) {
                        deferredTerminal = null;
                    }
                }
            }
        }

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
                scenarioStage = scenarios.acquire(scenarioRequest, scenario.applicationId(),
                        scenario.processId(), scenario.sessionId());
            } catch (RuntimeException failure) {
                fail(failure);
                return;
            }
            scenarioStage.whenComplete((lease, failure) -> {
                if (failure != null) {
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException
                            ? failure.getCause() : failure;
                    if (cause instanceof Scene2dScenarioRunner.AcquisitionException acquisition) {
                        ScenarioFailure scenarioFailure = acquisition.result().failure().orElseThrow();
                        if (scenarioFailure == ScenarioFailure.READINESS_DEADLINE) {
                            deadlineReached();
                        } else if (scenarioFailure == ScenarioFailure.CANCELLED) {
                            cancelForClose();
                        } else {
                            fail(acquisition);
                        }
                    } else {
                        fail(cause);
                    }
                } else if (scheduler.isOwnerThread()) {
                    becomeReady(lease);
                } else {
                    Scene2dNavigationRunner.this.observe(this, scheduler.submit(() -> {
                        becomeReady(lease);
                        return null;
                    }, dispatchDeadline()));
                }
            });
        }

        void armDeadline() {
            DeadlineScheduler.Cancellation scheduled =
                    deadlines.schedule(request.deadline(), this::deadlineReached);
            synchronized (this) {
                if (terminal) {
                    scheduled.cancel();
                } else {
                    deadlineCancellation = scheduled;
                }
            }
        }

        void becomeReady(Scene2dScenarioRunner.Lease lease) {
            synchronized (this) {
                if (terminal || ready) {
                    lease.release();
                    return;
                }
                scenarioLease = lease;
                ready = true;
            }
            SemanticSnapshot snapshot = session.snapshot(revision.getAsLong(), frame.getAsLong());
            before = observeState(snapshot, request.modalBoundaryId());
            Capture capture = focusables(snapshot, request.maxActors());
            known = capture.identities();
            if (capture.truncated()) {
                complete(resultWithReason(NavigationReason.TRUNCATED, true));
            } else {
                dispatchNext();
            }
        }

        void observe(SemanticSnapshot snapshot) {
            synchronized (this) {
                if (terminal || !ready || !waitingForFrame) {
                    return;
                }
                if (snapshot.frame() <= before.frame()) {
                    return;
                }
                waitingForFrame = false;
            }
            Observation after = observeState(snapshot, request.modalBoundaryId());
            NavigationInput navigationInput = configuredInputs.get(nextInput - 1);
            if (before.focusIdentity() == null && after.focusIdentity() == null) {
                complete(resultWithReason(NavigationReason.FOCUS_LOST, false));
                return;
            }
            String beforeIdentity = before.focusIdentity() == null
                    ? NavigationStep.NO_FOCUS_IDENTITY : before.focusIdentity();
            steps.add(new NavigationStep(navigationInput, before.frame(), before.revision(),
                    after.frame(), after.revision(), beforeIdentity, after.focusIdentity(),
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
                if (terminal) {
                    return;
                }
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
                if (!terminal) {
                    waitingForFrame = true;
                }
            }
        }

        private NavigationResult validateObserved(boolean expired) {
            NavigationRequest observed = new NavigationRequest(1, steps, known,
                    before == null ? null : before.focusIdentity(),
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
                    ? before.focusIdentity() : steps.isEmpty() ? null
                    : NavigationStep.NO_FOCUS_IDENTITY.equals(steps.get(0).beforeIdentity())
                            ? null : steps.get(0).beforeIdentity();
            NavigationPath path = new NavigationPath(1, defaultFocus, steps, reason);
            return new NavigationResult(1, path, known, List.of(), truncated);
        }

        void deadlineReached() {
            Scene2dNavigationRunner.this.observe(this, scheduler.submit(() -> {
                if (!terminal && deadline.isExpired()) {
                    complete(deadlineResult());
                }
                return null;
            }, dispatchDeadline()));
        }

        void cancelForClose() {
            // A caller-thread close/cancel never blocks: reserved frame deliveries are
            // observed first, and the terminal transition applies when the last one completes.
            reserveTerminal(() -> {
                CompletionStage<Scene2dScenarioRunner.Lease> pending;
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                    pending = scenarioStage;
                }
                if (scenarioLease == null && pending != null) {
                    pending.toCompletableFuture().cancel(false);
                }
                finishAfterCleanup(null, null, true);
            });
        }

        void fail(Throwable failure) {
            reserveTerminal(() -> {
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                }
                finishAfterCleanup(null, failure, false);
            });
        }

        /**
         * Terminally rolls back a run whose scheduling call threw after it was admitted: the
         * pending scenario acquisition is cancelled so a subsequently READY lease is released
         * instead of leaked or used by admitted work, the active slot is freed exactly once,
         * and the result closes with the original failure.
         */
        void schedulingFailed(Throwable failure) {
            reserveTerminal(() -> {
                CompletionStage<Scene2dScenarioRunner.Lease> pending;
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                    pending = scenarioStage;
                }
                if (scenarioLease == null && pending != null) {
                    pending.toCompletableFuture().cancel(false);
                }
                finishAfterCleanup(null, failure, false);
            });
        }

        private void complete(NavigationResult value) {
            reserveTerminal(() -> {
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                }
                finishAfterCleanup(value, null, false);
            });
        }

        private void finishAfterCleanup(
                NavigationResult value, Throwable failure, boolean cancelled) {
            cancelDeadline();
            Scene2dScenarioRunner.Lease lease;
            synchronized (this) {
                lease = scenarioLease;
            }
            CompletionStage<?> cleanup = lease == null
                    ? CompletableFuture.completedFuture(null) : lease.release();
            cleanup.whenComplete((ignored, cleanupFailure) -> {
                if (cleanupFailure != null) {
                    result.completeExceptionally(cleanupFailure);
                } else if (cancelled) {
                    result.cancelDirect();
                } else if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(value);
                }
                finished(this);
            });
        }

        private void cancelDeadline() {
            DeadlineScheduler.Cancellation cancellation;
            synchronized (this) {
                cancellation = deadlineCancellation;
                deadlineCancellation = null;
            }
            if (cancellation != null) {
                cancellation.cancel();
            }
        }
    }

    private static Observation observeState(SemanticSnapshot snapshot, String requestedModal) {
        Map<String, SemanticNode> byId = new HashMap<>(snapshot.nodes());
        SemanticNode focused = snapshot.nodes().values().stream()
                .filter(node -> node.state().focused())
                .findFirst().orElse(null);
        String focusIdentity = focused == null ? null : identity(focused, byId);
        String modal = null;
        for (SemanticNode node = focused; node != null; node = byId.get(node.parentId())) {
            if (Objects.equals(identity(node, byId), requestedModal)) {
                modal = requestedModal;
                break;
            }
        }
        return new Observation(snapshot.frame(), snapshot.revision(), focusIdentity, modal);
    }

    private static Capture focusables(SemanticSnapshot snapshot, int maxActors) {
        Map<String, SemanticNode> byId = new HashMap<>(snapshot.nodes());
        List<String> all = snapshot.nodes().values().stream()
                .filter(node -> node.state().focusable() && node.state().visible())
                .map(node -> identity(node, byId))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        boolean truncated = all.size() > maxActors;
        return new Capture(truncated ? List.copyOf(all.subList(0, maxActors)) : all, truncated);
    }

    private static String identity(SemanticNode node, Map<String, SemanticNode> byId) {
        if (node.testId() != null) {
            return "test-id:" + node.testId();
        }
        ArrayList<String> segments = new ArrayList<>();
        for (SemanticNode current = node; current != null; current = byId.get(current.parentId())) {
            String semantic = semanticSegment(current);
            int ordinal = siblingOrdinal(current, semantic, byId);
            segments.add(semantic + "[" + ordinal + "]");
        }
        java.util.Collections.reverse(segments);
        return "path:/" + String.join("/", segments);
    }

    private static int siblingOrdinal(
            SemanticNode node, String semantic, Map<String, SemanticNode> byId) {
        SemanticNode parent = byId.get(node.parentId());
        if (parent == null) {
            return 0;
        }
        int ordinal = 0;
        for (String childId : parent.childIds()) {
            SemanticNode sibling = byId.get(childId);
            if (sibling == null) {
                continue;
            }
            if (sibling.id().equals(node.id())) {
                return ordinal;
            }
            if (semanticSegment(sibling).equals(semantic)) {
                ordinal++;
            }
        }
        return ordinal;
    }

    private static String semanticSegment(SemanticNode node) {
        String name = node.accessibleName();
        if (name == null) {
            name = node.actorName();
        }
        if (name == null) {
            name = node.text();
        }
        if (name == null) {
            name = "unnamed";
        }
        return "role:" + node.role().name().toLowerCase(java.util.Locale.ROOT)
                + "/name:" + name.replace("%", "%25").replace("/", "%2F");
    }

    private record Capture(List<String> identities, boolean truncated) {}

    private record Observation(
            long frame, long revision, String focusIdentity, String modalBoundaryId) {}

    private final class ResultFuture extends CompletableFuture<NavigationResult> {
        private final Run run;
        ResultFuture(Run run) { this.run = run; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (run) {
                if (run.terminal) {
                    return false;
                }
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
