package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Runs application-registered scenario hooks against completed Scene2D frames. */
public final class Scene2dScenarioRunner implements AutoCloseable {
    private static final Duration INTERNAL_DISPATCH_TIMEOUT = Duration.ofMinutes(10);

    /** Exclusive ownership of a scenario after its READY state has been observed. */
    public interface Lease {
        /** Completes after render-thread cleanup, whether released or terminated externally. */
        CompletionStage<ScenarioResult> completion();

        /** Relinquishes ownership and schedules cleanup on the render thread; idempotent. */
        CompletionStage<ScenarioResult> release();
    }

    /** Reports a terminal scenario result when READY could not be acquired. */
    @SuppressWarnings("serial")
    public static final class AcquisitionException extends RuntimeException {
        private final ScenarioResult result;

        private AcquisitionException(ScenarioResult result) {
            super("scenario acquisition failed: " + result.failure().orElse(null));
            this.result = result;
        }

        public ScenarioResult result() {
            return result;
        }
    }
    private final ScenarioRegistry registry;
    private final RenderThreadScheduler scheduler;
    private final MonotonicClock clock;
    private final DeadlineScheduler deadlineScheduler;
    private final Object lifecycle = new Object();
    private final ArrayList<Run> active = new ArrayList<>();
    private final Map<InputIdentity, String> startStateIdentities = new LinkedHashMap<>();
    private boolean open = true;

    /**
     * Retained released constructor: adapts the legacy scene2d deadline scheduler to the core
     * {@link DeadlineScheduler} contract without changing scheduling semantics. This is the only
     * constructor taking a functional scheduler, so released lambda call sites stay unambiguous.
     */
    public Scene2dScenarioRunner(
            ScenarioRegistry registry,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            Scene2dScenarioDeadlineScheduler deadlineScheduler) {
        this(registry, scheduler, clock, adapt(deadlineScheduler));
    }

    /** Creates a runner driven by the core deadline scheduler contract. */
    public static Scene2dScenarioRunner withDeadlineScheduler(
            ScenarioRegistry registry,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            DeadlineScheduler deadlineScheduler) {
        return new Scene2dScenarioRunner(registry, scheduler, clock, deadlineScheduler);
    }

    private Scene2dScenarioRunner(
            ScenarioRegistry registry,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            DeadlineScheduler deadlineScheduler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler, "deadlineScheduler");
    }

    private static DeadlineScheduler adapt(Scene2dScenarioDeadlineScheduler legacy) {
        Objects.requireNonNull(legacy, "deadlineScheduler");
        return (delay, signal) -> {
            Scene2dScenarioDeadlineScheduler.Cancellation cancellation = legacy.schedule(delay, signal);
            return cancellation::cancel;
        };
    }

    /** Starts one bounded scenario run and releases it as soon as READY is observed. */
    public CompletionStage<ScenarioResult> start(
            ScenarioRequest request, String applicationId, String processId, String sessionId) {
        return launch(request, applicationId, processId, sessionId, true).result;
    }

    /**
     * Acquires exclusive ownership at READY. Setup, reset, readiness, and state identity are
     * evaluated once; cleanup is deferred until release or an external terminal condition.
     */
    public CompletionStage<Lease> acquire(
            ScenarioRequest request, String applicationId, String processId, String sessionId) {
        return launch(request, applicationId, processId, sessionId, false).acquisition;
    }

    private Run launch(
            ScenarioRequest request,
            String applicationId,
            String processId,
            String sessionId,
            boolean releaseAtReady) {
        Objects.requireNonNull(request, "request");
        ScenarioRegistry.RegisteredScenario registered = registry.require(request.scenarioId());
        ScenarioDefinition definition = registered.definition();
        if (!definition.applicationId().equals(applicationId)) {
            throw new IllegalArgumentException("scenario is incompatible with application: " + applicationId);
        }
        if (!definition.supportedProfileIds().contains(request.profileId())) {
            throw new IllegalArgumentException("unsupported scenario profile: " + request.profileId());
        }
        Run run = new Run(
                request,
                definition,
                registered.lifecycle(),
                Objects.requireNonNull(applicationId, "applicationId"),
                Objects.requireNonNull(processId, "processId"),
                Objects.requireNonNull(sessionId, "sessionId"),
                releaseAtReady);
        boolean rejected;
        synchronized (lifecycle) {
            if (!open) {
                throw new IllegalStateException("scenario runner is closed");
            }
            rejected = !active.isEmpty();
            if (!rejected) {
                active.add(run);
            }
        }
        if (rejected) {
            // A competing run owns the single active lease: reject with bounded evidence and
            // never execute hooks for the loser.
            run.rejectBusy();
            return run;
        }
        // Arm the deadline before any begin submission: while the deadline scheduler blocks
        // (or a concurrent render drain runs), no accepted begin can transition QUEUED ->
        // STARTING, so a rejecting arm can never leave hook execution behind.
        try {
            run.armDeadline();
        } catch (RuntimeException failure) {
            // The deadline scheduler rejected the arm: terminalize before any begin
            // submission exists and propagate the original failure.
            run.schedulingFailed();
            throw failure;
        }
        CompletionStage<?> submission;
        try {
            submission = scheduler.submit(() -> {
                run.begin();
                return null;
            }, dispatchDeadline());
        } catch (RuntimeException failure) {
            // The render scheduler rejected the begin submission after the deadline was armed:
            // terminalize (which cancels the armed deadline outside the run monitor) and
            // propagate the original failure.
            run.schedulingFailed();
            throw failure;
        }
        observeSubmission(run, submission);
        return run;
    }

    /** Observes a completed semantic frame and evaluates every active run on the render thread. */
    public void completedFrame(SemanticSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Run[] runs;
        synchronized (lifecycle) {
            runs = active.toArray(Run[]::new);
        }
        for (Run run : runs) {
            observeSubmission(run, scheduler.submit(() -> {
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
                deliveryCompleted(run, failure != null));
    }

    /**
     * Runs when a reserved frame delivery completes (or fails): releases the run's reservation
     * and, once every in-flight reservation has drained, applies the first-wins deferred terminal
     * transition. A failed delivery then reports the dispatch failure, which no-ops when the
     * deferred transition already terminalized the run.
     */
    private void deliveryCompleted(Run run, boolean failed) {
        releaseOneReservation(run);
        if (failed) {
            run.dispatchFailed();
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
            run.result.cancel(false);
        }
    }

    private Deadline dispatchDeadline() {
        return Deadline.after(clock, INTERNAL_DISPATCH_TIMEOUT);
    }
    private void observeSubmission(Run run, CompletionStage<?> submission) {
        submission.whenComplete((ignored, failure) -> {
            if (failure != null) {
                run.dispatchFailed();
            }
        });
    }


    /**
     * Releases the single active lease only when the identical {@link Run} still owns it. A stale
     * release from an already-terminal run cannot clear its successor, keeping {@code active}
     * bounded to one owner.
     */
    private void releaseIfOwner(Run run) {
        synchronized (lifecycle) {
            active.remove(run);
        }
    }

    private final class Run implements Lease {
        private final ScenarioRequest request;
        private final ScenarioDefinition definition;
        private final ScenarioLifecycle hooks;
        private final String applicationId;
        private final String processId;
        private final String sessionId;
        private final boolean releaseAtReady;
        private final String configurationDigest;
        private final InputIdentity inputIdentity;
        private final long startedAtNanos = clock.nanoTime();
        private final ResultFuture result = new ResultFuture(this);
        private final AcquisitionFuture acquisition = new AcquisitionFuture(this);
        private Phase phase = Phase.QUEUED;
        private long startFrame;
        private long startRevision;
        private long readyFrame;
        private long readyRevision;
        private int setupAttempts;
        private String stateIdentity = "unavailable";
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
            Scene2dScenarioRunner.this.terminalApplyProbe.run();
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

        private DeadlineScheduler.Cancellation deadlineCancellation;
        Run(
                ScenarioRequest request,
                ScenarioDefinition definition,
                ScenarioLifecycle hooks,
                String applicationId,
                String processId,
                String sessionId,
                boolean releaseAtReady) {
            this.request = request;
            this.definition = definition;
            this.hooks = hooks;
            this.applicationId = applicationId;
            this.processId = processId;
            this.sessionId = sessionId;
            this.releaseAtReady = releaseAtReady;
            configurationDigest = digest(request.configuration());
            inputIdentity = new InputIdentity(
                    request.scenarioId(), request.seed(), configurationDigest, request.profileId());
        }

        void armDeadline() {
            Duration delay = request.deadline().remaining();
            Duration maximumRemaining = definition.maxDuration().minus(elapsed());
            if (maximumRemaining.isNegative()) {
                maximumRemaining = Duration.ZERO;
            }
            if (maximumRemaining.compareTo(delay) < 0) {
                delay = maximumRemaining;
            }
            DeadlineScheduler.Cancellation scheduled =
                    deadlineScheduler.schedule(delay, this::deadlineReached);
            boolean cancelNow;
            synchronized (this) {
                // A scheduler may invoke the signal inline during schedule (already-expired
                // deadline): the run then leaves QUEUED before the token is stored, so the
                // fresh token must be invalidated instead of retained.
                cancelNow = phase != Phase.QUEUED;
                if (!cancelNow) {
                    deadlineCancellation = scheduled;
                }
            }
            if (cancelNow) {
                // The run already reached a terminal state while the token was being armed:
                // invalidate it only after leaving the run monitor, so a cancellation that
                // synchronously reenters the runner never runs under monitor ownership.
                scheduled.cancel();
            }
        }

        private void deadlineReached() {
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return;
                }
                if (!expired()) {
                    return;
                }
            }
            // The deadline transition participates in the reservation protocol: while frame
            // deliveries are in flight it defers, so the reserved frames are observed before
            // the run terminalizes; otherwise it publishes atomically on this thread.
            reserveTerminal(() -> {
                boolean publish;
                synchronized (this) {
                    if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                        return;
                    }
                    if (!expired()) {
                        return;
                    }
                    // The deadline thread atomically publishes the terminal result so a paused
                    // or stopped render loop can never leave the call hanging; the run keeps
                    // owning the active slot and hook cleanup is deferred to the render thread.
                    phase = Phase.CLEANING;
                    publish = true;
                }
                if (publish) {
                    publishTerminal(ScenarioFailure.READINESS_DEADLINE, false);
                    // The deferred cleanup submission is the only failure path that may release
                    // the active owner: unrelated rejected submissions during the terminal window
                    // must not admit a successor before the accepted cleanup mutates the Stage.
                    CompletionStage<?> cleanup = scheduler.submit(() -> {
                        deferredCleanup();
                        return null;
                    }, dispatchDeadline());
                    cleanup.whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            cleanupFailed();
                        }
                    });
                }
            });
        }

        /**
         * Runs the deferred cleanup hook exactly once on the render thread after the deadline
         * thread published the terminal result, then releases the active owner slot so the next
         * acquisition can proceed. The already-published result is never republished (no double
         * result); a failing cleanup hook cannot change the immutable published outcome.
         */
        private void deferredCleanup() {
            try {
                hooks.cleanup(request);
            } catch (RuntimeException cleanupFailure) {
                // The terminal result was published when the deadline fired; cleanup failure
                // cannot be republished, but the active slot must still be released.
            }
            releaseIfOwner(this);
        }

        /** Terminates a competing acquisition without scheduling any hook execution. */
        void rejectBusy() {
            reserveTerminal(() -> {
                synchronized (this) {
                    if (phase != Phase.QUEUED) {
                        return;
                    }
                    phase = Phase.TERMINAL;
                }
                completeTerminal(ScenarioFailure.SESSION_BUSY, false);
            });
        }

        void begin() {
            synchronized (this) {
                if (phase != Phase.QUEUED) {
                    return;
                }
                phase = Phase.STARTING;
                setupAttempts = 1;
            }
            try {
                hooks.setup(request);
            } catch (RuntimeException failure) {
                terminate(ScenarioFailure.SETUP_REJECTED);
                return;
            }
            try {
                hooks.reset(request);
            } catch (RuntimeException failure) {
                terminate(ScenarioFailure.RESET_REJECTED);
                return;
            }
            synchronized (this) {
                if (phase == Phase.STARTING) {
                    phase = Phase.WAITING_FOR_FRAME;
                }
            }
        }

        void observe(SemanticSnapshot snapshot) {
            synchronized (this) {
                if (phase != Phase.WAITING_FOR_FRAME) {
                    return;
                }
                if (startFrame == 0 && startRevision == 0) {
                    startFrame = snapshot.frame();
                    startRevision = snapshot.revision();
                }
            }
            if (expired()) {
                terminate(ScenarioFailure.READINESS_DEADLINE);
                return;
            }
            final boolean ready;
            try {
                ready = hooks.ready(request);
            } catch (RuntimeException failure) {
                terminate(ScenarioFailure.READINESS_REJECTED);
                return;
            }
            if (!ready) {
                return;
            }
            final String observedIdentity;
            try {
                observedIdentity = Objects.requireNonNull(
                        hooks.startStateIdentity(request, snapshot), "startStateIdentity");
                if (observedIdentity.isBlank()) {
                    throw new IllegalArgumentException("startStateIdentity must not be blank");
                }
            } catch (RuntimeException failure) {
                terminate(ScenarioFailure.NONDETERMINISTIC_INITIAL_STATE);
                return;
            }
            synchronized (this) {
                stateIdentity = observedIdentity;
                readyFrame = snapshot.frame();
                readyRevision = snapshot.revision();
            }
            ScenarioFailure mismatch = null;
            synchronized (lifecycle) {
                String previous = startStateIdentities.putIfAbsent(inputIdentity, observedIdentity);
                if (previous != null && !previous.equals(observedIdentity)) {
                    mismatch = ScenarioFailure.NONDETERMINISTIC_INITIAL_STATE;
                }
            }
            if (mismatch != null || releaseAtReady) {
                terminate(mismatch);
                return;
            }
            synchronized (this) {
                if (phase != Phase.WAITING_FOR_FRAME) {
                    return;
                }
                phase = Phase.READY;
            }
            acquisition.complete(this);
        }

        boolean requestCancellation() {
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return false;
                }
            }
            // Caller-thread cancellations never block: while reserved frame deliveries are in
            // flight the CANCELLED transition is deferred until the last delivery completes,
            // so the reserved frames are observed before the run terminalizes.
            return reserveTerminal(() -> {
                synchronized (this) {
                    if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                        return;
                    }
                    phase = Phase.CANCELLING;
                }
                observeSubmission(this, scheduler.submit(() -> {
                    terminate(ScenarioFailure.CANCELLED);
                    return null;
                }, dispatchDeadline()));
            });
        }

        @Override public CompletionStage<ScenarioResult> completion() {
            return result;
        }

        @Override public CompletionStage<ScenarioResult> release() {
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return result;
                }
                if (phase != Phase.READY) {
                    throw new IllegalStateException("scenario lease is not ready");
                }
            }
            // A caller-thread release never blocks: reserved frame deliveries are observed
            // first, and the CANCELLING transition applies when the last one completes.
            reserveTerminal(() -> {
                synchronized (this) {
                    if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                        return;
                    }
                    if (phase != Phase.READY) {
                        throw new IllegalStateException("scenario lease is not ready");
                    }
                    phase = Phase.CANCELLING;
                }
                if (scheduler.isDraining()) {
                    // Already on the render thread inside a drain: cleanup runs inline so the
                    // releasing call site observes the terminal result without another frame.
                    terminate(null);
                } else {
                    observeSubmission(this, scheduler.submit(() -> {
                        terminate(null);
                        return null;
                    }, dispatchDeadline()));
                }
            });
            return result;
        }

        private boolean expired() {
            return request.deadline().isExpired()
                    || elapsed().compareTo(definition.maxDuration()) >= 0;
        }
        private void dispatchFailed() {
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    // A terminal or cleaning run is released only by its own terminal path or
                    // by its deferred cleanup submission; an unrelated rejected submission must
                    // never release the active owner while accepted cleanup is still queued.
                    return;
                }
            }
            reserveTerminal(() -> {
                synchronized (this) {
                    if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                        return;
                    }
                    phase = Phase.TERMINAL;
                }
                completeTerminal(ScenarioFailure.DISPATCH_FAILED, false);
            });
        }

        /**
         * Terminally rolls back a run whose scheduling call threw synchronously after the run
         * was admitted to {@code active}: the run never executes hooks, its active owner slot
         * is released exactly once, and the result and acquisition futures close so nothing is
         * left pending. The original failure still propagates to the synchronous launch caller.
         */
        void schedulingFailed() {
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return;
                }
            }
            reserveTerminal(() -> {
                synchronized (this) {
                    if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                        return;
                    }
                    phase = Phase.TERMINAL;
                }
                completeTerminal(ScenarioFailure.DISPATCH_FAILED, false);
            });
        }

        /**
         * Runs when the deferred cleanup submission itself is rejected: the cleanup hook will
         * never run, so the active owner slot is released exactly once without republishing the
         * already-published terminal result.
         */
        private void cleanupFailed() {
            releaseIfOwner(this);
        }


        private void terminate(ScenarioFailure failure) {
            boolean claimed;
            synchronized (lifecycle) {
                claimed = deferredApplying;
            }
            if (claimed) {
                // The caller is already inside the first-wins deferred terminal application
                // (e.g. a release transition completing its lease during a drain): applying
                // directly avoids competing with the still-claimed intent.
                applyTerminate(failure);
            } else {
                reserveTerminal(() -> applyTerminate(failure));
            }
        }

        private void applyTerminate(ScenarioFailure failure) {
            if (!scheduler.isOwnerThread()) {
                // Cleanup hooks must run on the render thread: a deferred application from an
                // off-thread release (a supplier/enqueue failure or a rejected delivery) routes
                // the termination to the owner. If the routing itself fails — a synchronous
                // throw or a rejected submission — fall back to a bounded, thread-safe terminal
                // that runs no lifecycle or Stage hooks, as part of this same winning intent.
                ScenarioFailure deferredFailure = failure;
                CompletionStage<?> submission;
                try {
                    submission = scheduler.submit(() -> {
                        applyTerminate(deferredFailure);
                        return null;
                    }, dispatchDeadline());
                } catch (RuntimeException routingFailure) {
                    terminalDispatchFallback();
                    return;
                }
                submission.whenComplete((ignored, routingFailure) -> {
                    if (routingFailure != null) {
                        terminalDispatchFallback();
                    }
                });
                return;
            }
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return;
                }
                phase = Phase.CLEANING;
            }
            boolean cleaned = true;
            try {
                hooks.cleanup(request);
            } catch (RuntimeException cleanupFailure) {
                cleaned = false;
                failure = ScenarioFailure.CLEANUP_FAILED;
            }
            synchronized (this) {
                phase = Phase.TERMINAL;
            }
            completeTerminal(failure, cleaned);
        }

        /**
         * Bounded terminal fallback when an off-owner termination could not be routed to the
         * render thread: no lifecycle or Stage hooks run here. The run terminalizes with
         * DISPATCH_FAILED (cleanupCompleted=false), the armed deadline is cancelled, the active
         * owner slot is released exactly once, and the result/acquisition futures close.
         */
        private void terminalDispatchFallback() {
            boolean publish;
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return;
                }
                phase = Phase.TERMINAL;
                publish = true;
            }
            if (publish) {
                completeTerminal(ScenarioFailure.DISPATCH_FAILED, false);
            }
        }

        /**
         * Publishes the terminal result and acquisition outcome exactly once. Does not release
         * the active owner slot: the normal render-thread paths call {@link #completeTerminal},
         * while the deadline path publishes first and releases only after the deferred cleanup
         * drains on the render thread.
         */
        private void publishTerminal(ScenarioFailure failure, boolean cleaned) {
            DeadlineScheduler.Cancellation scheduled;
            synchronized (this) {
                scheduled = deadlineCancellation;
                deadlineCancellation = null;
            }
            if (scheduled != null) {
                scheduled.cancel();
            }
            ScenarioResult value = new ScenarioResult(
                    ScenarioDefinition.SCHEMA_VERSION,
                    definition.id(),
                    definition.definitionVersion(),
                    configurationDigest,
                    request.seed(),
                    applicationId,
                    processId,
                    sessionId,
                    startFrame,
                    startRevision,
                    readyFrame,
                    readyRevision,
                    request.profileId(),
                    stateIdentity,
                    elapsed(),
                    setupAttempts,
                    cleaned,
                    Optional.ofNullable(failure));
            result.complete(value);
            if (!acquisition.isDone()) {
                acquisition.completeExceptionally(new AcquisitionException(value));
            }
        }

        private void completeTerminal(ScenarioFailure failure, boolean cleaned) {
            releaseIfOwner(this);
            publishTerminal(failure, cleaned);
        }

        private Duration elapsed() {
            return Duration.ofNanos(Math.max(0L, clock.nanoTime() - startedAtNanos));
        }
    }

    private static String digest(Map<String, String> configuration) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            update(digest, entry.getKey());
            update(digest, entry.getValue());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record InputIdentity(
            String scenarioId, long seed, String configurationDigest, String profileId) {}

    private enum Phase {
        QUEUED,
        STARTING,
        WAITING_FOR_FRAME,
        READY,
        CANCELLING,
        CLEANING,
        TERMINAL
    }

    private final class ResultFuture extends CompletableFuture<ScenarioResult> {
        private final Run cancellation;

        ResultFuture(Run cancellation) {
            this.cancellation = cancellation;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return cancellation.requestCancellation();
        }
    }

    private final class AcquisitionFuture extends CompletableFuture<Lease> {
        private final Run cancellation;

        AcquisitionFuture(Run cancellation) {
            this.cancellation = cancellation;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return cancellation.requestCancellation();
        }
    }
}
