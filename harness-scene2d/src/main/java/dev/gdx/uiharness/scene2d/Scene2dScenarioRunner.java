package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
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

    private final ScenarioRegistry registry;
    private final RenderThreadScheduler scheduler;
    private final MonotonicClock clock;
    private final Scene2dScenarioDeadlineScheduler deadlineScheduler;
    private final Object lifecycle = new Object();
    private final ArrayList<Run> active = new ArrayList<>();
    private final Map<InputIdentity, String> startStateIdentities = new LinkedHashMap<>();
    private boolean open = true;

    public Scene2dScenarioRunner(
            ScenarioRegistry registry,
            RenderThreadScheduler scheduler,
            MonotonicClock clock,
            Scene2dScenarioDeadlineScheduler deadlineScheduler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler, "deadlineScheduler");
    }

    /** Starts one bounded scenario run; lifecycle work is dispatched to the render thread. */
    public CompletionStage<ScenarioResult> start(
            ScenarioRequest request, String applicationId, String processId, String sessionId) {
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
                Objects.requireNonNull(sessionId, "sessionId"));
        synchronized (lifecycle) {
            if (!open) {
                throw new IllegalStateException("scenario runner is closed");
            }
            active.add(run);
        }
        observeSubmission(run, scheduler.submit(() -> {
            run.begin();
            return null;
        }, dispatchDeadline()));
        run.armDeadline();
        return run.result;
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


    private void finished(Run run) {
        synchronized (lifecycle) {
            active.remove(run);
        }
    }

    private final class Run {
        private final ScenarioRequest request;
        private final ScenarioDefinition definition;
        private final ScenarioLifecycle hooks;
        private final String applicationId;
        private final String processId;
        private final String sessionId;
        private final String configurationDigest;
        private final InputIdentity inputIdentity;
        private final long startedAtNanos = clock.nanoTime();
        private final ResultFuture result = new ResultFuture(this);
        private Phase phase = Phase.QUEUED;
        private long startFrame;
        private long startRevision;
        private long readyFrame;
        private long readyRevision;
        private int setupAttempts;
        private String stateIdentity = "unavailable";

        private Scene2dScenarioDeadlineScheduler.Cancellation deadlineCancellation;
        Run(
                ScenarioRequest request,
                ScenarioDefinition definition,
                ScenarioLifecycle hooks,
                String applicationId,
                String processId,
                String sessionId) {
            this.request = request;
            this.definition = definition;
            this.hooks = hooks;
            this.applicationId = applicationId;
            this.processId = processId;
            this.sessionId = sessionId;
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
            Scene2dScenarioDeadlineScheduler.Cancellation scheduled =
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
                terminate(ScenarioFailure.RESET_REJECTED);
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
            terminate(mismatch);
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
            Scene2dScenarioDeadlineScheduler.Cancellation scheduled;
            synchronized (this) {
                scheduled = deadlineCancellation;
                deadlineCancellation = null;
            }
            if (scheduled != null) {
                scheduled.cancel();
            }
            result.complete(new ScenarioResult(
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
                    Optional.ofNullable(failure)));
            finished(this);
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
}
