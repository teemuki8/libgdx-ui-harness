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

    public Scene2dScenarioRunner(
            ScenarioRegistry registry,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            DeadlineScheduler deadlineScheduler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler, "deadlineScheduler");
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
        observeSubmission(run, scheduler.submit(() -> {
            run.begin();
            return null;
        }, dispatchDeadline()));
        run.armDeadline();
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
            synchronized (this) {
                if (phase == Phase.TERMINAL) {
                    scheduled.cancel();
                } else {
                    deadlineCancellation = scheduled;
                }
            }
        }

        private void deadlineReached() {
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return;
                }
            }
            observeSubmission(this, scheduler.submit(() -> {
                if (expired()) {
                    terminate(ScenarioFailure.READINESS_DEADLINE);
                }
                return null;
            }, dispatchDeadline()));
        }

        /** Terminates a competing acquisition without scheduling any hook execution. */
        void rejectBusy() {
            synchronized (this) {
                if (phase != Phase.QUEUED) {
                    return;
                }
                phase = Phase.TERMINAL;
            }
            completeTerminal(ScenarioFailure.SESSION_BUSY, false);
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
                phase = Phase.CANCELLING;
            }
            observeSubmission(this, scheduler.submit(() -> {
                terminate(ScenarioFailure.CANCELLED);
                return null;
            }, dispatchDeadline()));
            return true;
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
            return result;
        }

        private boolean expired() {
            return request.deadline().isExpired()
                    || elapsed().compareTo(definition.maxDuration()) >= 0;
        }
        private void dispatchFailed() {
            synchronized (this) {
                if (phase == Phase.TERMINAL || phase == Phase.CLEANING) {
                    return;
                }
                phase = Phase.TERMINAL;
            }
            completeTerminal(ScenarioFailure.DISPATCH_FAILED, false);
        }


        private void terminate(ScenarioFailure failure) {
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

        private void completeTerminal(ScenarioFailure failure, boolean cleaned) {
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
            releaseIfOwner(this);
            result.complete(value);
            if (!acquisition.isDone()) {
                acquisition.completeExceptionally(new AcquisitionException(value));
            }
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
